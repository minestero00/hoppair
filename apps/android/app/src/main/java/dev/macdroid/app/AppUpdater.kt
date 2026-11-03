package dev.macdroid.app

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

internal const val UPDATE_FEED = "https://minestero00.github.io/hoppair/release.json"
internal const val MAX_UPDATE_BYTES = 128L * 1024 * 1024
private const val MAX_UPDATE_FEED = 64 * 1024
private const val UPDATE_INTERVAL = 15 * 60 * 1000L
private const val CACHE_LIFETIME = 24 * 60 * 60 * 1000L

internal data class AndroidRelease(val version: String, val versionCode: Long, val url: String,
    val sha256: String, val size: Long, val minSdk: Int, val tag: String = version)

internal object UpdatePolicy {
    fun validate(release: AndroidRelease, currentCode: Long, sdk: Int) {
        require(listOf(release.version, release.tag).all { it.length <= 40 && it.matches(Regex("""[0-9]+\.[0-9]+\.[0-9]+""")) }) { "Invalid update version." }
        require(release.versionCode > currentCode && release.versionCode <= Int.MAX_VALUE) { "This update is not newer than your installed app." }
        require(release.minSdk in 28..sdk) { "This update requires a newer Android version." }
        require(release.size in 1..MAX_UPDATE_BYTES && release.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid update verification details." }
        require(release.url == "https://github.com/minestero00/hoppair/releases/download/v${release.tag}/Hoppair-${release.version}-android.apk") { "Untrusted update address." }
    }
    fun parse(json: JSONObject, currentCode: Long, sdk: Int): AndroidRelease? {
        val android = json.getJSONObject("android")
        fun integer(name: String): Long {
            val value = android.get(name)
            require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble()) { "Invalid update details." }
            return value.toLong()
        }
        // Older release manifests advertise downloads without claiming updater verification support.
        if (!android.has("versionCode")) return null
        val code = integer("versionCode")
        if (code <= currentCode) return null
        val minSdk = integer("minSdk")
        require(minSdk in 28..Int.MAX_VALUE.toLong()) { "Invalid update details." }
        fun string(source: JSONObject, name: String): String = (source.get(name) as? String) ?: error("Invalid update details.")
        val tag = string(json, "version")
        val version = if (android.has("versionName")) string(android, "versionName") else tag
        return AndroidRelease(version, code, string(android, "url"), string(android, "sha256").lowercase(),
            integer("size"), minSdk.toInt(), tag).also { validate(it, currentCode, sdk) }
    }
    fun redirectAllowed(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.userInfo == null && uri.fragment == null && uri.port in listOf(-1, 443) &&
            uri.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")
    }.getOrDefault(false)
    fun verifyIdentity(archive: UpdateArchive, installed: UpdateArchive, release: AndroidRelease, sdk: Int) {
        require(archive.packageName == "dev.macdroid.app" && archive.packageName == installed.packageName) { "The downloaded app has a different package identity." }
        require(archive.versionCode == release.versionCode && archive.versionName == release.version && archive.versionCode > installed.versionCode) { "The downloaded app has an unexpected version." }
        require(archive.minSdk == release.minSdk && archive.minSdk <= sdk) { "The downloaded app requires a different Android version." }
        require(archive.signers.isNotEmpty() && archive.signers == installed.signers) { "The downloaded app was signed with a different key. Your installed app and shelf have been kept." }
    }
}

internal data class UpdateArchive(val packageName: String, val versionCode: Long, val versionName: String?, val minSdk: Int, val signers: Set<String>)
internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

/** A finite, streaming transfer. The caller removes its owned partial file on any failure. */
internal fun copyVerifiedUpdate(input: InputStream, output: OutputStream, release: AndroidRelease,
    progress: (Int) -> Unit = {}, checkCancelled: () -> Unit = {}) {
    require(release.size in 1..MAX_UPDATE_BYTES)
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var count = 0L
    var lastProgress = -1
    while (true) {
        checkCancelled()
        var read = input.read(buffer, 0, minOf(buffer.size.toLong(), release.size + 1 - count).toInt())
        if (read < 0) break
        if (read == 0) {
            val byte = input.read()
            if (byte < 0) break
            buffer[0] = byte.toByte(); read = 1
        }
        count += read
        require(count <= release.size) { "The update is larger than its verified size." }
        digest.update(buffer, 0, read); output.write(buffer, 0, read)
        val percentage = (count * 100 / release.size).toInt()
        if (percentage != lastProgress) { progress(percentage); lastProgress = percentage }
    }
    require(count == release.size) { "The update download was interrupted. Please try again." }
    val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    require(hash.equals(release.sha256, ignoreCase = true)) { "The update could not be verified. Please try again later." }
}

internal class UpdateCache(cacheRoot: File) {
    val directory = File(cacheRoot, "updates")
    private val ownedName = Regex("""hoppair-update-(?:[0-9]+-[a-f0-9]{64}\.apk|[a-f0-9-]{36}\.part)""")
    fun ready(release: AndroidRelease): File = File(directory, "hoppair-update-${release.versionCode}-${release.sha256.lowercase()}.apk")
    fun partial(): File { check(directory.mkdirs() || directory.isDirectory); return File(directory, "hoppair-update-${UUID.randomUUID()}.part") }
    fun delete(file: File?) {
        if (file != null && file.parentFile?.canonicalFile == directory.canonicalFile && ownedName.matches(file.name)) file.delete()
    }
    fun prune(installedCode: Long, now: Long = System.currentTimeMillis()) {
        directory.listFiles()?.filter { it.isFile && ownedName.matches(it.name) }?.forEach { file ->
            val code = file.name.removePrefix("hoppair-update-").substringBefore('-').toLongOrNull()
            if (file.extension == "part" || (code != null && code <= installedCode) || now - file.lastModified() >= CACHE_LIFETIME) delete(file)
        }
    }
}

internal interface UpdateBackend {
    val cache: UpdateCache
    fun cancel()
    fun installed(): UpdateArchive
    fun check(checkCancelled: () -> Unit): AndroidRelease?
    fun download(release: AndroidRelease, progress: (Int) -> Unit, checkCancelled: () -> Unit): File
}

internal class AndroidUpdateService(private val context: Context, private val transport: ((String) -> Response)? = null) : UpdateBackend {
    override val cache = UpdateCache(context.cacheDir)
    @Volatile private var active: Call? = null
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(5, TimeUnit.MINUTES).build()
    override fun cancel() { active?.cancel() }
    private fun fetch(url: String, checkCancelled: () -> Unit = {}): Response {
        checkCancelled()
        transport?.let { return it(url) }
        val call = http.newCall(Request.Builder().url(url).header("Accept-Encoding", "identity").build())
        active = call
        checkCancelled()
        return call.execute()
    }
    override fun check(checkCancelled: () -> Unit): AndroidRelease? = fetch(UPDATE_FEED, checkCancelled).use { response ->
        require(response.code == 200) { "Could not check for updates. Please try again." }
        val body = checkNotNull(response.body)
        require(body.contentLength() <= MAX_UPDATE_FEED) { "The update information is too large." }
        val bytes = body.byteStream().use { it.readBytesBounded(MAX_UPDATE_FEED) }
        UpdatePolicy.parse(JSONObject(String(bytes, Charsets.UTF_8)), installed().versionCode, Build.VERSION.SDK_INT)
    }
    override fun installed(): UpdateArchive = archive(context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES))
    private fun archive(info: PackageInfo): UpdateArchive = UpdateArchive(info.packageName, info.longVersionCode, info.versionName,
        checkNotNull(info.applicationInfo).minSdkVersion, info.signingInfo?.apkContentsSigners.orEmpty().map { sha256(it.toByteArray()) }.toSet())
    fun verify(file: File, release: AndroidRelease) {
        UpdatePolicy.validate(release, installed().versionCode, Build.VERSION.SDK_INT)
        require(file.isFile && file.length() == release.size) { "The update file is incomplete. Please retry." }
        file.inputStream().use { input -> copyVerifiedUpdate(input, object : OutputStream() { override fun write(byte: Int) {} ; override fun write(bytes: ByteArray, offset: Int, length: Int) {} }, release) }
        val packageInfo = context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("The download is not a signed Android app.")
        UpdatePolicy.verifyIdentity(archive(packageInfo), installed(), release, Build.VERSION.SDK_INT)
    }
    override fun download(release: AndroidRelease, progress: (Int) -> Unit, checkCancelled: () -> Unit): File {
        UpdatePolicy.validate(release, installed().versionCode, Build.VERSION.SDK_INT)
        val ready = cache.ready(release)
        if (ready.exists()) {
            try { verify(ready, release); return ready } catch (_: Exception) { cache.delete(ready) }
        }
        val partial = cache.partial()
        try {
            var url = release.url
            var response = fetch(url, checkCancelled)
            try {
                var redirects = 0
                while (response.code in listOf(301, 302, 303, 307, 308)) {
                    require(++redirects <= 5) { "The update download redirected too many times." }
                    val location = response.header("Location") ?: error("The update download moved unexpectedly.")
                    val next = URI(url).resolve(location).toString()
                    require(UpdatePolicy.redirectAllowed(next)) { "The update download moved to an untrusted address." }
                    response.close(); url = next; response = fetch(url, checkCancelled)
                }
                require(response.code == 200) { "Could not download the update. Please try again." }
                val body = checkNotNull(response.body)
                require(body.contentLength() == -1L || body.contentLength() == release.size) { "The update download has an unexpected size." }
                partial.outputStream().use { output ->
                    body.byteStream().use { input -> copyVerifiedUpdate(input, output, release, progress, checkCancelled) }
                    output.fd.sync()
                }
            } finally { response.close() }
            checkCancelled(); verify(partial, release); checkCancelled()
            check(partial.renameTo(ready)) { "Could not save the verified update. Please retry." }
            return ready
        } finally { cache.delete(partial); active = null }
    }
}

internal enum class UpdateStage { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, VERIFYING, READY, PERMISSION, INSTALLING, ERROR }
internal data class UpdateState(val stage: UpdateStage = UpdateStage.IDLE, val release: AndroidRelease? = null,
    val progress: Int = 0, val error: String? = null, val sheet: Boolean = false, val installRequested: Boolean = false)

internal class AppUpdateViewModel(application: Application, private val service: UpdateBackend) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, AndroidUpdateService(application))
    private val state = MutableStateFlow(UpdateState())
    val ui = state.asStateFlow()
    private var operation: Job? = null
    private var readyFile: File? = null
    private var lastCheck: Long? = null
    private var permissionConsent = false
    private val initialization = viewModelScope.async(Dispatchers.IO) { service.cache.prune(service.installed().versionCode) }
    fun check(manual: Boolean = true) {
        if (manual) state.update { it.copy(sheet = true) }
        if (operation?.isCompleted == false || state.value.stage in setOf(UpdateStage.READY, UpdateStage.PERMISSION, UpdateStage.INSTALLING)) return
        val now = SystemClock.elapsedRealtime()
        if (!manual && (BuildConfig.DEBUG || lastCheck?.let { now - it < UPDATE_INTERVAL } == true)) return
        lastCheck = now
        state.update { it.copy(stage = UpdateStage.CHECKING, error = null) }
        operation = viewModelScope.launch {
            try {
                initialization.await()
                val context = currentCoroutineContext()
                val release = withContext(Dispatchers.IO) { service.check { context.ensureActive() } }
                state.update { it.copy(stage = if (release == null) UpdateStage.CURRENT else UpdateStage.AVAILABLE, release = release) }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (error is CancellationException) throw error
                state.update { it.copy(stage = UpdateStage.ERROR, error = "Could not check for updates. ${error.message.orEmpty().take(180)}") }
            }
        }
    }
    fun show() { state.update { it.copy(sheet = true) } }
    fun dismiss() { state.update { it.copy(sheet = false) } }
    fun update() {
        val release = state.value.release ?: return
        if (operation?.isCompleted == false) return
        state.update { it.copy(stage = UpdateStage.DOWNLOADING, progress = 0, error = null, sheet = true, installRequested = false) }
        operation = viewModelScope.launch {
            try {
                initialization.await()
                val currentJob = currentCoroutineContext()
                readyFile = withContext(Dispatchers.IO) {
                    service.download(release, { progress ->
                        currentJob.ensureActive()
                        state.update { it.copy(progress = progress, stage = if (progress == 100) UpdateStage.VERIFYING else UpdateStage.DOWNLOADING) }
                    }, { currentJob.ensureActive() })
                }
                state.update { it.copy(stage = UpdateStage.READY, installRequested = true) }
            } catch (error: Exception) {
                if (error is CancellationException || !currentCoroutineContext().isActive) {
                    withContext(NonCancellable + Dispatchers.IO) { service.cache.delete(service.cache.ready(release)) }
                    throw CancellationException("Update cancelled", error)
                }
                state.update { it.copy(stage = UpdateStage.ERROR, error = error.message?.take(240) ?: "Could not download the update. Please retry.", installRequested = false) }
            }
        }
    }
    /** Only the explicit Update action creates this request; incoming intents cannot do so. */
    fun consumeInstallRequest(): File? {
        if (state.value.stage != UpdateStage.READY || !state.value.installRequested) return null
        state.update { it.copy(installRequested = false) }
        return readyFile
    }
    fun permissionRequired() { permissionConsent = true; state.update { it.copy(stage = UpdateStage.PERMISSION) } }
    fun permissionReturned(granted: Boolean) {
        if (!permissionConsent || state.value.stage != UpdateStage.PERMISSION) return
        permissionConsent = false
        state.update { it.copy(stage = UpdateStage.READY, installRequested = granted,
            error = if (granted) null else "Allow Hoppair to install its update in Android settings, then tap Install update.") }
    }
    fun installerStarted() { state.update { it.copy(stage = UpdateStage.INSTALLING, installRequested = false) } }
    fun installerReturned(succeeded: Boolean) { cancel(if (succeeded) "Installation finished." else "Installation was closed. Your current app and shelf are unchanged.") }
    fun installationFailed() { cancel("Could not open the Android installer. Please try again.") }
    fun cancel(message: String? = null) {
        val previous = operation
        service.cancel(); previous?.cancel(); permissionConsent = false
        val file = readyFile; readyFile = null
        operation = viewModelScope.launch {
            previous?.join()
            withContext(Dispatchers.IO) { service.cache.delete(file) }
        }
        state.update { it.copy(stage = if (it.release != null) UpdateStage.AVAILABLE else UpdateStage.IDLE,
            progress = 0, error = message, installRequested = false) }
    }
    override fun onCleared() { service.cancel(); super.onCleared() }
}
