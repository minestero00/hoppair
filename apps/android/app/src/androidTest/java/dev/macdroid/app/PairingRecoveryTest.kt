package dev.macdroid.app

import android.app.Application
import android.content.ContextWrapper
import android.util.Base64
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class PairingRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val oldLink = link(49279, 7)

    @Test fun cancelledReplacementKeepsOriginalPairingAndOutbox() = fixture { app, model ->
        var completed = false
        onMain { model.pair(link(49278, 8)) { completed = true } }
        waitFor { model.ui.value.replacement != null && !model.ui.value.busy }
        val request = checkNotNull(model.ui.value.replacement)
        assertEquals(oldLink, PairingVault(app).load())
        assertFalse(completed)
        onMain { model.cancelPairingReplacement(); model.confirmPairingReplacement(request.id) }
        assertNull(model.ui.value.replacement)
        assertNull(model.ui.value.error)
        assertEquals(oldLink, PairingVault(app).load())
        assertEquals("Private queued note", model.ui.value.entries.single().text)
        assertTrue(model.ui.value.entries.single().pending)
    }

    @Test fun approvedReplacementProbesWithoutSharingThenSyncsToNewMac() = fixture { app, model ->
        val actions = mutableListOf<String>()
        peer(3) { request ->
            actions += request.getString("action")
            if (request.getString("action") == "listPage") JSONObject().put("items", JSONArray()).put("deviceName", "New fixture Mac")
            else { assertEquals("Private queued note", request.getJSONObject("item").getString("text")); JSONObject() }
        }.use { server ->
            val candidate = link(server.port, 8)
            var completed = false
            onMain { model.pair(candidate) { completed = true } }
            waitFor { model.ui.value.replacement != null && !model.ui.value.busy }
            assertEquals(oldLink, PairingVault(app).load())
            assertTrue(actions.isEmpty())
            onMain { model.confirmPairingReplacement(checkNotNull(model.ui.value.replacement).id) }
            waitFor { completed }
            assertEquals(candidate, PairingVault(app).load())
            assertEquals(listOf("listPage"), actions)
            assertFalse(model.ui.value.online)
            assertEquals("New fixture Mac", model.ui.value.peerName)
            assertTrue(model.ui.value.entries.single().pending)
            model.sync()
            assertEquals(listOf("listPage", "listPage", "put"), actions)
            assertTrue(model.ui.value.online)
            assertFalse(model.ui.value.entries.single().pending)
        }
    }

    @Test fun rejectedReplacementLeavesSavedConnectionAndItemsUntouched() = fixture { app, model ->
        peer(1) { JSONObject().put("ok", false).put("error", "Fixture rejected pairing") }.use { server ->
            var completed = false
            onMain { model.pair(link(server.port, 8)) { completed = true } }
            waitFor { model.ui.value.replacement != null && !model.ui.value.busy }
            onMain { model.confirmPairingReplacement(checkNotNull(model.ui.value.replacement).id) }
            waitFor { model.ui.value.error != null && !model.ui.value.busy }
            assertFalse(completed)
            assertEquals(oldLink, PairingVault(app).load())
            assertEquals("Private queued note", model.ui.value.entries.single().text)
            assertTrue(model.ui.value.entries.single().pending)
        }
    }

    @Test fun cancelledProbeCannotReplaceSavedPairing() = fixture { app, model ->
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        peer(1) {
            entered.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            JSONObject().put("items", JSONArray())
        }.use { server ->
            var completed = false
            onMain { model.pair(link(server.port, 8)) { completed = true } }
            waitFor { model.ui.value.replacement != null && !model.ui.value.busy }
            onMain { model.confirmPairingReplacement(checkNotNull(model.ui.value.replacement).id) }
            check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            onMain { model.viewModelScope.cancel() }
            release.countDown()
            waitFor { !model.ui.value.busy }
            assertFalse(completed)
            assertEquals(oldLink, PairingVault(app).load())
            assertTrue(model.ui.value.entries.single().pending)
        }
    }

    @Test fun newerInviteInvalidatesOlderConfirmation() = fixture { app, model ->
        onMain { model.pair(link(49278, 8)) }
        waitFor { model.ui.value.replacement != null && !model.ui.value.busy }
        val first = checkNotNull(model.ui.value.replacement).id
        onMain { model.pair(link(49277, 9)) }
        waitFor { model.ui.value.replacement?.id != first && !model.ui.value.busy }
        val second = checkNotNull(model.ui.value.replacement)
        onMain { model.confirmPairingReplacement(first) }
        assertEquals(second, model.ui.value.replacement)
        assertEquals(oldLink, PairingVault(app).load())
        onMain { model.cancelPairingReplacement() }
    }

    @Test fun obsoleteProbeFailureDoesNotCoverNewInvitationWithAnError() = fixture { app, model ->
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        peer(1) {
            entered.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            JSONObject().put("ok", false).put("error", "Obsolete probe failed")
        }.use { server ->
            onMain { model.pair(link(server.port, 8)) }
            waitFor { model.ui.value.replacement != null && !model.ui.value.busy }
            onMain { model.confirmPairingReplacement(checkNotNull(model.ui.value.replacement).id) }
            check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            onMain { model.pair(link(49277, 9)) }
            release.countDown()
            waitFor { model.ui.value.replacement != null && !model.ui.value.busy }
            assertNull(model.ui.value.error)
            assertEquals(oldLink, PairingVault(app).load())
            onMain { model.cancelPairingReplacement() }
        }
    }

    @Test fun savedHistoryAndFileRemainAfterReplacementAndSync() = fixture(history = true) { app, model ->
        peer(3) { request ->
            if (request.getString("action") == "listPage") JSONObject().put("items", JSONArray()) else JSONObject()
        }.use { server ->
            var completed = false
            onMain { model.pair(link(server.port, 8)) { completed = true } }
            waitFor { model.ui.value.replacement != null && !model.ui.value.busy }
            onMain { model.confirmPairingReplacement(checkNotNull(model.ui.value.replacement).id) }
            waitFor { completed }
            model.sync()
            assertEquals(3, model.ui.value.entries.size)
            assertTrue(model.ui.value.entries.any { it.text == "Saved history" && !it.pending })
            val file = model.ui.value.entries.single { it.kind == "file" }
            assertArrayEquals(byteArrayOf(1, 2, 3), checkNotNull(model.contentFile(file)).readBytes())
            assertEquals(3, ShelfRepository(app).entries.size)
        }
    }

    @Test fun invalidCandidateSnapshotCannotReplaceSavedPairing() = fixture { app, model ->
        peer(1) { JSONObject().put("items", JSONArray().put(JSONObject().put("id", "invalid"))) }.use { server ->
            onMain { model.pair(link(server.port, 8)) }
            waitFor { model.ui.value.replacement != null && !model.ui.value.busy }
            onMain { model.confirmPairingReplacement(checkNotNull(model.ui.value.replacement).id) }
            waitFor { model.ui.value.error != null && !model.ui.value.busy }
            assertEquals(oldLink, PairingVault(app).load())
            assertEquals("Private queued note", ShelfRepository(app).entries.single().text)
        }
    }

    private fun link(port: Int, key: Int) = "hoppair://127.0.0.1:$port?key=" + Base64.encodeToString(ByteArray(32) { key.toByte() }, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(20) }
    private fun fixture(history: Boolean = false, block: suspend (Application, ShelfViewModel) -> Unit) = runBlocking {
        val base = instrumentation.targetContext
        val namespace = "pair-recovery-${UUID.randomUUID()}"
        val root = File(base.cacheDir, namespace).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = root
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$namespace-$name", mode)
        }
        val app = object : Application() { init { attachBaseContext(context) }; override fun getFilesDir() = root }
        PairingVault(app).save(oldLink)
        val repository = ShelfRepository(app)
        if (history) {
            val file = JSONObject().put("id", UUID.randomUUID().toString()).put("kind", "file").put("title", "Saved file")
                .put("filename", "saved.bin").put("mime", "application/octet-stream").put("size", 3)
                .put("createdAt", 1.0).put("source", "Old Mac").put("pinned", false)
            val note = JSONObject(file.toString()).put("id", UUID.randomUUID().toString()).put("kind", "text").put("text", "Saved history")
            repository.merge(JSONArray().put(file).put(note)) { byteArrayOf(1, 2, 3) }
        }
        repository.addText("Private queued note")
        lateinit var model: ShelfViewModel
        onMain { model = ShelfViewModel(app) }
        try { waitFor { model.ui.value.paired }; block(app, model) }
        finally {
            model.pauseConnection()
            onMain { model.viewModelScope.cancel() }
            base.deleteSharedPreferences("$namespace-device-identity")
            root.deleteRecursively()
        }
    }
    private fun peer(exchanges: Int, respond: (JSONObject) -> JSONObject) = object : AutoCloseable {
        private val server = ServerSocket(0).apply { soTimeout = 15_000 }
        val port = server.localPort
        private val failure = AtomicReference<Throwable>()
        private val worker = thread(isDaemon = true, name = "pairing-fixture") {
            try {
                repeat(exchanges) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = DataInputStream(socket.getInputStream())
                        val size = input.readInt(); require(size in 28..MAX_FRAME)
                        val frame = ByteArray(size); input.readFully(frame)
                        val request = JSONObject(String(PayloadCrypto.open(frame, ByteArray(32) { 8 }), Charsets.UTF_8))
                        val response = respond(request)
                        if (!response.has("ok")) response.put("ok", true)
                        response.put("requestId", request.getString("requestId"))
                        val encrypted = PayloadCrypto.seal(response.toString().toByteArray(), ByteArray(32) { 8 })
                        DataOutputStream(socket.getOutputStream()).apply { writeInt(encrypted.size); write(encrypted); flush() }
                    }
                }
            } catch (error: Throwable) { failure.set(error) }
        }
        override fun close() {
            worker.join(2000); server.close(); worker.join(1000)
            failure.get()?.let { throw AssertionError("Pairing fixture failed", it) }
            assertFalse(worker.isAlive)
        }
    }
}
