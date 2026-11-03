package dev.macdroid.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class ShelfState(
    val entries: List<ShelfEntry> = emptyList(), val paired: Boolean = false,
    val online: Boolean = false, val status: String = "Connect your Mac to get started",
    val error: String? = null, val busy: Boolean = false, val cameraRequest: String? = null,
    val host: String = "", val peerName: String = "Mac", val deviceName: String = "", val defaultDeviceName: String = "Android phone"
)

internal class ShelfViewModel(application: Application) : AndroidViewModel(application) {
    private val state = MutableStateFlow(ShelfState())
    val ui = state.asStateFlow()
    private val preferences = application.getSharedPreferences("device-identity", 0)
    private val defaultName = cleanDeviceName(runCatching { android.provider.Settings.Global.getString(application.contentResolver, "device_name") }.getOrNull()
        ?: "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}").ifBlank { "Android phone" }
    private val mutex = Mutex()
    private var pendingOperations = 0
    private val vault = PairingVault(application)
    private var pairing: Pairing? = null
    private var syncClient: BridgeClient? = null
    private fun closeConnection() { syncClient?.close(); syncClient = null }
    private fun connection(pair: Pairing) = syncClient ?: BridgeClient(pair).also { syncClient = it }
    private var repository: ShelfRepository? = null
    init {
        viewModelScope.launch {
            mutex.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        state.update { it.copy(deviceName = preferences.getString("name", "").orEmpty(), defaultDeviceName = defaultName,
                            peerName = preferences.getString("peer", "Mac").orEmpty().ifBlank { "Mac" }) }
                        repository = ShelfRepository(application)
                        pairing = vault.load()?.let(Pairing::parse)
                        state.update { it.copy(entries = repository!!.entries, paired = pairing != null, host = pairing?.host ?: "",
                            status = if (pairing != null) "Reconnecting to your Mac…" else it.status) }
                    } catch (error: Exception) { fail(error) }
                }
            }
        }
    }
    private fun fail(error: Exception) {
        if (error is CancellationException) throw error
        state.update { it.copy(error = friendly(error), busy = false) }
    }
    private fun friendly(error: Exception): String = when (error) {
        is java.net.ConnectException, is java.net.SocketTimeoutException, is java.net.UnknownHostException, is java.net.NoRouteToHostException -> if (pairing?.relay != null) "Check the internet connection on both devices and open Hoppair. Reconnecting automatically." else "Open Hoppair on your Mac and use the same network. Reconnecting automatically."
        is java.io.EOFException -> "Connection interrupted. Reconnecting automatically."
        is javax.crypto.AEADBadTagException -> "Could not verify this connection. Scan your Mac’s pairing QR code again."
        is java.net.URISyntaxException -> "Use a Hoppair pairing QR code or pairing link."
        else -> error.localizedMessage ?: "Could not complete the action. Please try again shortly."
    }
    fun renameDevice(name: String) {
        val clean = cleanDeviceName(name)
        preferences.edit().putString("name", clean).apply()
        state.update { it.copy(deviceName = clean) }
    }
    private fun ownName() = state.value.deviceName.trim().ifBlank { defaultName }
    fun cameraDenied() { state.update { it.copy(error = "Camera access is needed to scan a QR code. Allow it in your phone settings or choose “Use a pairing link”.") } }
    fun clearError() { state.update { it.copy(error = null) } }
    private fun operation(onSuccess: () -> Unit = {}, block: () -> Unit) {
        pendingOperations++
        state.update { it.copy(busy = true) }
        viewModelScope.launch {
            mutex.withLock {
                state.update { it.copy(busy = true) }
                try {
                    withContext(Dispatchers.IO) {
                        checkNotNull(repository) { "Could not open local storage. Please restart the app." }
                        block()
                        state.update { it.copy(entries = repository!!.entries) }
                    }
                    onSuccess()
                } catch (error: Exception) { fail(error) }
                finally { pendingOperations--; state.update { it.copy(entries = repository?.entries ?: it.entries, busy = pendingOperations > 0) } }
            }
        }
    }
    fun pair(link: String, onSuccess: () -> Unit = {}) = operation(onSuccess) {
        val candidate = Pairing.parse(link)
        require(pairing == null || pairing!!.key.contentEquals(candidate.key)) { "This preview supports one Mac. You can update its connection address." }
        BridgeClient(candidate).use { it.call("list") } // Do not replace a working pairing until the peer authenticates.
        vault.save(link.trim()); closeConnection(); pairing = candidate
        state.update { it.copy(paired = true, online = false, host = candidate.host, status = "Loading your shelf…") }
    }
    fun addText(text: String, onSuccess: () -> Unit = {}) = operation(onSuccess) {
        repository!!.addText(text)
        state.update { it.copy(status = "Queued for transfer") }
    }
    fun addFiles(uris: List<Uri>) = operation {
        for (uri in uris) repository!!.addFile(uri)
        state.update { it.copy(status = "Queued for transfer") }
    }
    suspend fun sync() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val pair = pairing ?: return@withContext
                val repo = repository ?: return@withContext
                try {
                    val client = connection(pair)
                    val syncContext = currentCoroutineContext()
                    client.setCancellationCheck { syncContext.ensureActive() }
                    val response = client.call("list")
                    repo.merge(response.getJSONArray("items"), client)
                    var rejected: String? = null
                    for (entry in repo.entries.filter { it.pending }) {
                        try {
                            val bytes = if (entry.kind == "file") repo.file(entry.id).readBytes() else null
                            client.call("put", entry.json, bytes)
                            repo.acknowledge(entry.id)
                        } catch (error: PeerRejected) { rejected = friendly(error); break }
                    }
                    val peerName = cleanDeviceName(response.optString("deviceName", "Mac")).trim().ifBlank { "Mac" }
                    if (response.optBoolean("syncAcknowledgement", false)) client.call("syncAck", deviceName = ownName())
                    if (peerName != state.value.peerName) preferences.edit().putString("peer", peerName).apply()
                    val camera = if (response.isNull("cameraRequest")) null else response.optString("cameraRequest").takeIf { it.isNotEmpty() }
                    state.update { it.copy(entries = repo.entries, online = true, peerName = peerName, status = rejected ?: "Everything is up to date", cameraRequest = camera) }
                } catch (error: Exception) {
                    closeConnection()
                    if (error is CancellationException) throw error
                    state.update { it.copy(entries = repo.entries, online = false, status = friendly(error)) }
                } finally { syncClient?.setCancellationCheck {} }
            }
        }
    }
    suspend fun pauseConnection() = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            closeConnection()
            state.update { it.copy(online = false, status = if (it.paired) "Reconnecting when Hoppair opens" else it.status) }
        }
    }
    fun dismissCamera() = operation {
        val id = state.value.cameraRequest ?: return@operation
        try { connection(checkNotNull(pairing)).call("cameraAck", id = id) }
        catch (error: Exception) { closeConnection(); throw error }
        state.update { it.copy(cameraRequest = null) }
    }
    fun contentFile(entry: ShelfEntry) = repository?.file(entry.id)
}

internal fun cleanDeviceName(value: String): String = value.asSequence().filter {
    !it.isISOControl() && it !in '\u202A'..'\u202E' && it !in '\u2066'..'\u2069'
}.take(64).joinToString("")
