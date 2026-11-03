package dev.macdroid.app

import android.app.Application
import android.content.ContextWrapper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** No external feed, unknown-source settings, or Android installer is launched. */
@RunWith(AndroidJUnit4::class)
class UpdateLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base = instrumentation.targetContext
    private val application get() = base.applicationContext as Application
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for updater state." }
            Thread.sleep(10)
        }
    }
    private fun model(store: ViewModelStore, backend: UpdateBackend): AppUpdateViewModel {
        var result: AppUpdateViewModel? = null
        onMain {
            val provider = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = AppUpdateViewModel(application, backend) as T
            })
            result = provider[AppUpdateViewModel::class.java]
        }
        return checkNotNull(result)
    }
    private fun fixtureRelease(bytes: ByteArray) = AndroidRelease("0.1.7", BuildConfig.VERSION_CODE + 1L,
        "https://github.com/minestero00/hoppair/releases/download/v0.1.7/Hoppair-0.1.7-android.apk", sha256(bytes), bytes.size.toLong(), 28)
    private fun response(url: String, body: ResponseBody) = Response.Builder().request(Request.Builder().url(url).build())
        .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()

    @Test fun cancellingActualStreamingServiceRemovesPartialAndDoesNotBecomeAnInstallRequest() {
        val root = File(base.cacheDir, "update-cancel-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getCacheDir() = root }
        val blocked = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val store = ViewModelStore()
        val bytes = ByteArray(128 * 1024) { 42 }
        val release = fixtureRelease(bytes)
        val input = object : InputStream() {
            private var offset = 0
            override fun read(): Int = if (offset >= bytes.size) -1 else bytes[offset++].toInt() and 255
            override fun read(buffer: ByteArray, start: Int, length: Int): Int {
                if (offset > 0) {
                    blocked.countDown()
                    check(releaseRead.await(8, TimeUnit.SECONDS)) { "Fixture stream was not released." }
                }
                if (offset >= bytes.size) return -1
                val count = minOf(length, 8192, bytes.size - offset)
                bytes.copyInto(buffer, start, offset, offset + count); offset += count
                return count
            }
        }
        val service = AndroidUpdateService(context) { url ->
            if (url == UPDATE_FEED) {
                val feed = JSONObject().put("version", release.tag).put("android", JSONObject().put("versionName", release.version)
                    .put("versionCode", release.versionCode).put("url", release.url).put("sha256", release.sha256).put("size", release.size).put("minSdk", 28))
                response(url, feed.toString().toResponseBody())
            } else {
                assertEquals(release.url, url)
                response(url, object : ResponseBody() {
                    private val buffered = input.source().buffer()
                    override fun contentType(): MediaType? = null
                    override fun contentLength() = bytes.size.toLong()
                    override fun source(): BufferedSource = buffered
                })
            }
        }
        try {
            val vm = model(store, service)
            onMain { vm.check() }
            waitFor { vm.ui.value.stage == UpdateStage.AVAILABLE }
            onMain { vm.update() }
            assertTrue(blocked.await(8, TimeUnit.SECONDS))
            assertTrue(service.cache.directory.listFiles().orEmpty().any { it.extension == "part" && it.length() > 0 })
            onMain { vm.cancel() }
            releaseRead.countDown()
            waitFor { service.cache.directory.listFiles().orEmpty().none { it.extension in setOf("part", "apk") } }
            assertEquals(UpdateStage.AVAILABLE, vm.ui.value.stage)
            assertFalse(vm.ui.value.installRequested)
            onMain { assertNull(vm.consumeInstallRequest()); vm.permissionReturned(true) }
            assertFalse(vm.ui.value.installRequested)
        } finally { releaseRead.countDown(); onMain { store.clear() }; root.deleteRecursively() }
    }

    @Test fun permissionDenialAndCancellationRequireNewConsentAndRetainedVmKeepsVerifiedFile() {
        val root = File(base.cacheDir, "update-consent-test-${UUID.randomUUID()}").apply { mkdirs() }
        val backend = ReadyBackend(root)
        val store = ViewModelStore()
        try {
            val vm = model(store, backend)
            onMain { vm.permissionReturned(true); assertNull(vm.consumeInstallRequest()); vm.check() }
            waitFor { vm.ui.value.stage == UpdateStage.AVAILABLE }
            onMain { vm.update() }
            waitFor { vm.ui.value.stage == UpdateStage.READY && vm.ui.value.installRequested }
            var ready: File? = null
            onMain { ready = vm.consumeInstallRequest(); vm.permissionRequired(); vm.permissionReturned(false) }
            assertTrue(checkNotNull(ready).isFile)
            assertFalse(vm.ui.value.installRequested)
            onMain { vm.permissionReturned(true); assertNull(vm.consumeInstallRequest()) }
            assertFalse(vm.ui.value.installRequested)
            onMain { vm.update() }
            waitFor { vm.ui.value.stage == UpdateStage.READY && vm.ui.value.installRequested }
            onMain { assertEquals(ready, vm.consumeInstallRequest()); vm.permissionRequired() }
            onMain { vm.cancel(); vm.permissionReturned(true) }
            assertEquals(UpdateStage.AVAILABLE, vm.ui.value.stage)
            assertFalse(vm.ui.value.installRequested)
            waitFor { !checkNotNull(ready).exists() }
            onMain { vm.update() }
            waitFor { vm.ui.value.stage == UpdateStage.READY && vm.ui.value.installRequested }
            onMain { assertEquals(ready, vm.consumeInstallRequest()); vm.permissionRequired() }
            // A replacement owner using the retained store gets the actual same ViewModel and consent state.
            val retained = model(store, ReadyBackend(root))
            assertSame(vm, retained)
            assertEquals(UpdateStage.PERMISSION, retained.ui.value.stage)
            onMain { retained.permissionReturned(true) }
            assertTrue(retained.ui.value.installRequested)
            onMain { assertEquals(ready, retained.consumeInstallRequest()); retained.installerStarted(); retained.installerReturned(false) }
            waitFor { !checkNotNull(ready).exists() }
            assertEquals(UpdateStage.AVAILABLE, vm.ui.value.stage)
            assertFalse(vm.ui.value.installRequested)
            onMain { vm.permissionReturned(true); assertNull(vm.consumeInstallRequest()) }
        } finally { onMain { store.clear() }; root.deleteRecursively() }
    }

    @Test fun processRecreationDoesNotResumeInstallationAndStartupPrunesInstalledOrStaleApks() {
        val root = File(base.cacheDir, "update-restart-test-${UUID.randomUUID()}").apply { mkdirs() }
        val backend = ReadyBackend(root)
        val firstStore = ViewModelStore()
        val nextStore = ViewModelStore()
        val installedStore = ViewModelStore()
        try {
            val first = model(firstStore, backend)
            onMain { first.check() }
            waitFor { first.ui.value.stage == UpdateStage.AVAILABLE }
            onMain { first.update() }
            waitFor { first.ui.value.stage == UpdateStage.READY && first.ui.value.installRequested }
            val ready = backend.cache.ready(backend.release)
            onMain { firstStore.clear() }
            assertTrue(ready.exists())
            val recreated = model(nextStore, backend)
            onMain { recreated.permissionReturned(true); assertNull(recreated.consumeInstallRequest()) }
            assertEquals(UpdateStage.IDLE, recreated.ui.value.stage)
            assertFalse(recreated.ui.value.installRequested)
            onMain { recreated.check() }
            waitFor { recreated.ui.value.stage == UpdateStage.AVAILABLE }
            assertTrue(ready.exists())
            val stale = backend.cache.ready(backend.release.copy(versionCode = backend.release.versionCode + 1)).apply { writeText("stale"); setLastModified(1) }
            backend.installedCode = backend.release.versionCode
            onMain { nextStore.clear() }
            model(installedStore, backend)
            waitFor { !ready.exists() && !stale.exists() }
        } finally { onMain { firstStore.clear(); nextStore.clear(); installedStore.clear() }; root.deleteRecursively() }
    }

    private inner class ReadyBackend(root: File) : UpdateBackend {
        override val cache = UpdateCache(root)
        val bytes = "Never an installable APK; only a ViewModel state fixture".toByteArray()
        val release = fixtureRelease(bytes)
        @Volatile var installedCode = BuildConfig.VERSION_CODE.toLong()
        override fun installed() = UpdateArchive(base.packageName, installedCode, BuildConfig.VERSION_NAME, 28, setOf("fixture-signer"))
        override fun cancel() {}
        override fun check(checkCancelled: () -> Unit): AndroidRelease { checkCancelled(); return release }
        override fun download(release: AndroidRelease, progress: (Int) -> Unit, checkCancelled: () -> Unit): File {
            checkCancelled(); cache.partial().let { cache.delete(it) }
            return cache.ready(release).apply { writeBytes(bytes); progress(100) }
        }
    }
}
