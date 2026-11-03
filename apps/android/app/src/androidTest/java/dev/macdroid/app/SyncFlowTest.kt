package dev.macdroid.app

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.util.Base64
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

/** Real encrypted TCP + ViewModel + isolated disk storage; no personal pairing or shelf. */
@RunWith(AndroidJUnit4::class)
class SyncFlowTest {
    @Test fun missingRemoteFileDoesNotPreventUploadOrPeerAcknowledgement() {
        val note = item("text")
        val file = item("file").put("size", 3)
        val actions = mutableListOf<String>()
        peer(4) { request ->
            val action = request.getString("action"); actions += action
            when (action) {
                "listPage" -> JSONObject().put("items", JSONArray().put(file).put(note)).put("syncAcknowledgement", true).put("deviceName", "Fixture Mac")
                "get" -> JSONObject().put("ok", false).put("error", "File not found.")
                else -> JSONObject()
            }
        }.use { server ->
            withModel(server.port) { model ->
                model.sync()
                assertTrue(model.ui.value.online)
                assertEquals("Fixture Mac", model.ui.value.peerName)
                assertTrue(model.ui.value.status.contains("waiting to download"))
                assertEquals(2, model.ui.value.entries.size)
                assertTrue(model.ui.value.entries.none { it.pending })
                assertEquals(listOf("listPage", "put", "get", "syncAck"), actions)
            }
        }
    }

    @Test fun interruptedDownloadKeepsUploadedNoteAndReportsOfflineThenRecovers() {
        val note = item("text")
        val file = item("file").put("size", 3)
        var downloads = 0
        peer(6) { request ->
            when (request.getString("action")) {
                "listPage" -> JSONObject().put("items", JSONArray().put(file).put(note)).put("syncAcknowledgement", true)
                "get" -> if (++downloads == 1) null else JSONObject().put("data", Base64.encodeToString(byteArrayOf(1, 2, 3), Base64.NO_WRAP))
                else -> JSONObject()
            }
        }.use { server ->
            withModel(server.port) { model ->
                model.sync()
                assertFalse(model.ui.value.online)
                assertEquals(2, model.ui.value.entries.size)
                assertTrue(model.ui.value.entries.none { it.pending })
                model.sync()
                assertTrue(model.ui.value.online)
                assertEquals(3, model.ui.value.entries.size)
                assertEquals("Everything is up to date", model.ui.value.status)
            }
        }
    }

    @Test fun staleSnapshotCannotReplaceJustAcknowledgedQueuedContent() {
        var queuedID = ""
        peer(3) { request ->
            when (request.getString("action")) {
                "listPage" -> JSONObject().put("items", JSONArray().put(item("text").put("id", queuedID).put("text", "Stale peer copy")))
                    .put("syncAcknowledgement", true)
                else -> JSONObject()
            }
        }.use { server ->
            withModel(server.port) { model ->
                queuedID = model.ui.value.entries.single().id
                model.sync()
                assertEquals("Outgoing fixture note", model.ui.value.entries.single().text)
                assertFalse(model.ui.value.entries.single().pending)
            }
        }
    }

    @Test fun deletionReceiptsRemoveQueuedRetriesAndStaleSnapshotBeforeUpload() {
        var queuedID = ""
        val file = item("file").put("size", 3)
        val note = item("text")
        val actions = mutableListOf<String>()
        peer(3) { request ->
            actions += request.getString("action")
            when (request.getString("action")) {
                "listPage" -> JSONObject().put("items", JSONArray().put(file).put(note)).put("supportsDeletion", true).put("syncAcknowledgement", true)
                "deleted" -> {
                    assertEquals(3, request.getJSONArray("ids").length())
                    JSONObject().put("deleted", JSONArray().put(queuedID).put(file.getString("id")))
                }
                else -> JSONObject()
            }
        }.use { server ->
            withModel(server.port) { model ->
                queuedID = model.ui.value.entries.single().id
                model.sync()
                assertEquals(listOf("listPage", "deleted", "syncAck"), actions)
                assertEquals(note.getString("id"), model.ui.value.entries.single().id)
                assertTrue(model.ui.value.canDelete)
            }
        }
    }

    @Test fun lostDeleteAcknowledgementReconcilesOnNextSync() {
        var queuedID = ""
        var deleted = false
        peer(6) { request ->
            when (request.getString("action")) {
                "listPage" -> JSONObject().put("items", JSONArray()).put("supportsDeletion", true)
                "deleted" -> JSONObject().put("deleted", if (deleted) JSONArray().put(queuedID) else JSONArray())
                "delete" -> { assertEquals(queuedID, request.getString("id")); deleted = true; null }
                else -> JSONObject()
            }
        }.use { server ->
            withModel(server.port) { model ->
                queuedID = model.ui.value.entries.single().id
                model.sync()
                InstrumentationRegistry.getInstrumentation().runOnMainSync { model.deleteEntry(queuedID) }
                withTimeout(10_000) { while (model.ui.value.busy || model.ui.value.error == null) delay(20) }
                assertEquals(queuedID, model.ui.value.entries.single().id)
                model.sync()
                assertTrue(model.ui.value.entries.isEmpty())
                assertTrue(model.ui.value.online)
            }
        }
    }

    private fun withModel(port: Int, block: suspend (ShelfViewModel) -> Unit) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val namespace = "flow-${UUID.randomUUID()}"
        val root = File(base.cacheDir, namespace).apply { mkdirs() }
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = root
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$namespace-$name", mode)
        }
        val app = object : Application() {
            init { attachBaseContext(isolated) }
            override fun getFilesDir() = root
        }
        PairingVault(app).save("hoppair://127.0.0.1:$port?key=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc")
        ShelfRepository(app).addText("Outgoing fixture note")
        lateinit var model: ShelfViewModel
        instrumentation.runOnMainSync { model = ShelfViewModel(app) }
        try {
            withTimeout(10_000) { while (!model.ui.value.paired) delay(20) }
            block(model)
        } finally {
            model.pauseConnection()
            instrumentation.runOnMainSync { model.viewModelScope.cancel() }
            base.deleteSharedPreferences("$namespace-device-identity")
            root.deleteRecursively()
        }
    }

    private fun item(kind: String) = JSONObject().put("id", UUID.randomUUID().toString()).put("kind", kind)
        .put("title", "Remote fixture").put("text", "Remote note").put("filename", "fixture.bin")
        .put("mime", "application/octet-stream").put("size", 0).put("createdAt", 1.0).put("source", "Mac").put("pinned", false)

    private fun peer(exchanges: Int, respond: (JSONObject) -> JSONObject?) = object : AutoCloseable {
        private val server = ServerSocket(0).apply { soTimeout = 20_000 }
        val port = server.localPort
        private val failure = AtomicReference<Throwable>()
        private val worker = thread(isDaemon = true, name = "sync-flow-peer") {
            try {
                repeat(exchanges) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = DataInputStream(socket.getInputStream())
                        val size = input.readInt(); require(size in 28..MAX_FRAME)
                        val encrypted = ByteArray(size); input.readFully(encrypted)
                        val request = JSONObject(String(PayloadCrypto.open(encrypted, ByteArray(32) { 7 }), Charsets.UTF_8))
                        val reply = respond(request) ?: return@use
                        if (!reply.has("ok")) reply.put("ok", true)
                        reply.put("requestId", request.getString("requestId"))
                        val data = PayloadCrypto.seal(reply.toString().toByteArray(), ByteArray(32) { 7 })
                        DataOutputStream(socket.getOutputStream()).apply { writeInt(data.size); write(data); flush() }
                    }
                }
            } catch (error: Throwable) { failure.set(error) }
        }
        override fun close() {
            worker.join(2000); server.close(); worker.join(1000)
            failure.get()?.let { throw AssertionError("Fixture peer failed", it) }
            assertFalse("Fixture peer must stop", worker.isAlive)
        }
    }
}
