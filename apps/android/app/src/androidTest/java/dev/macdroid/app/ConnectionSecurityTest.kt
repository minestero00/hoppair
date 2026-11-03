package dev.macdroid.app

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

/** All keys, peers, files, and identities here are disposable fixtures. */
@RunWith(AndroidJUnit4::class)
class ConnectionSecurityTest {
    private val key = ByteArray(32) { 7 }
    private val encodedKey = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc"

    @Test fun stalledLocalReaderCannotHoldLargeUploadBeyondTotalDeadline() {
        val server = ServerSocket(0)
        val release = java.util.concurrent.CountDownLatch(1)
        val accepted = java.util.concurrent.CountDownLatch(1)
        val worker = thread(isDaemon = true, name = "stalled-local-peer") {
            server.accept().use { socket ->
                socket.receiveBufferSize = 1024
                accepted.countDown()
                release.await(25, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        val started = System.nanoTime()
        try {
            BridgeClient(Pairing("127.0.0.1", server.localPort, key)).use { client ->
                assertThrows(java.io.IOException::class.java) { client.call("put", data = ByteArray(MAX_FILE)) }
            }
            assertEquals(0L, accepted.count)
            val elapsed = (System.nanoTime() - started) / 1_000_000_000.0
            assertTrue("Write exceeded bounded deadline: $elapsed", elapsed < 22)
        } finally {
            release.countDown(); server.close(); worker.join(2000)
            assertFalse(worker.isAlive)
        }
    }

    @Test fun cancellationStopsNextExchangeAndDoesNotFallBackAfterLocalFailure() {
        ServerSocket(0).use { relay ->
            relay.soTimeout = 250
            BridgeClient(Pairing("127.0.0.1", 1, key, "ws://127.0.0.1:${relay.localPort}")).use { client ->
                var checks = 0
                client.setCancellationCheck {
                    if (++checks == 2) throw java.util.concurrent.CancellationException()
                }
                assertThrows(java.util.concurrent.CancellationException::class.java) { client.call("list") }
                assertEquals(2, checks)
                assertThrows(java.net.SocketTimeoutException::class.java) { relay.accept().close() }
                client.setCancellationCheck { throw java.util.concurrent.CancellationException() }
                assertThrows(java.util.concurrent.CancellationException::class.java) { client.call("list") }
            }
        }
    }

    @Test fun directRepliesMustAuthenticateAndMatchBeforeSuccessOrErrorIsUsed() {
        withPeer { request -> JSONObject().put("ok", true).put("requestId", request.getString("requestId")).put("items", JSONArray()) }.use { peer ->
            BridgeClient(peer.pairing).use { assertTrue(it.call("list").getBoolean("ok")) }
        }
        for (reply in listOf(
            JSONObject().put("ok", true),
            JSONObject().put("ok", true).put("requestId", UUID.randomUUID().toString()),
            JSONObject().put("ok", false).put("requestId", UUID.randomUUID().toString()).put("error", "Unverified error")
        )) {
            withPeer { reply }.use { peer ->
                // An unreachable relay makes an accidental fallback observable as the wrong exception type.
                BridgeClient(peer.pairing.copy(relay = "ws://127.0.0.1:1")).use { client ->
                    assertThrows(PeerVerificationFailed::class.java) { client.call("list") }
                }
            }
        }
        withPeer { request -> JSONObject().put("ok", "true").put("requestId", request.getString("requestId")) }.use { peer ->
            BridgeClient(peer.pairing).use { client -> assertThrows(PeerVerificationFailed::class.java) { client.call("list") } }
        }
        withPeer(responseKey = ByteArray(32) { 8 }) { request ->
            JSONObject().put("ok", true).put("requestId", request.getString("requestId"))
        }.use { peer ->
            BridgeClient(peer.pairing.copy(relay = "ws://127.0.0.1:1")).use { client ->
                assertThrows(PeerVerificationFailed::class.java) { client.call("list") }
            }
        }
    }

    @Test fun capturedAuthenticatedReplyCannotAcknowledgeAnotherRequest() {
        val captured = AtomicReference<JSONObject>()
        withPeer(exchanges = 2) { request ->
            captured.get() ?: JSONObject().put("ok", true).put("requestId", request.getString("requestId")).put("items", JSONArray()).also { captured.set(it) }
        }.use { peer ->
            BridgeClient(peer.pairing).use { client ->
                client.call("list")
                assertThrows(PeerVerificationFailed::class.java) { client.call("list") }
            }
        }
    }

    @Test fun pagingCorrelatesFreshRequestsAndReturnsOnlyACompleteBoundedSnapshot() {
        val rows = (0 until 500).map { text() }
        val requests = mutableSetOf<String>()
        var page = 0
        withPeer(exchanges = 5) { request ->
            assertEquals("listPage", request.getString("action"))
            assertTrue(requests.add(request.getString("requestId")))
            if (page > 0) assertEquals(rows[page * 100 - 1].getString("id"), request.getString("id"))
            val response = JSONObject().put("ok", true).put("requestId", request.getString("requestId"))
                .put("items", JSONArray(rows.subList(page * 100, (page + 1) * 100)))
            page++
            if (page < 5) response.put("nextCursor", rows[page * 100 - 1].getString("id"))
            response
        }.use { peer ->
            BridgeClient(peer.pairing).use { client -> assertEquals(500, client.call("list").getJSONArray("items").length()) }
        }
        assertEquals(5, requests.size)
        val first = text()
        val second = text()
        var attempt = 0
        withPeer(exchanges = 2) { request ->
            JSONObject().put("ok", true).put("requestId", request.getString("requestId"))
                .put("items", JSONArray().put(if (attempt++ == 0) first else second)).put("nextCursor", first.getString("id"))
        }.use { peer ->
            BridgeClient(peer.pairing).use { client -> assertThrows(IllegalArgumentException::class.java) { client.call("list") } }
        }
    }

    @Test fun escapedShelfLargerThanOneFrameRoundTripsWithoutBuildingOneHugeIndexString() = withRepository { context, repo ->
        val content = "\u0000".repeat(64 * 1024)
        val snapshot = JSONArray().also { array -> repeat(33) { array.put(text().put("text", content).put("size", 64 * 1024)) } }
        repo.merge(snapshot) { error("Text never downloads a file") }
        assertTrue(File(context.filesDir, "shelf.json").length() > MAX_FRAME)
        val reopened = ShelfRepository(context)
        assertEquals(33, reopened.entries.size)
        assertEquals(content, reopened.entries.first().text)
    }

    @Test fun pairingRejectsAmbiguityOversizeAndRelayUrlPartsThatWouldBeDiscarded() {
        val link = "hoppair://127.0.0.1:49271?key=$encodedKey"
        for (invalid in listOf(
            "$link&key=$encodedKey", "$link&relay=wss%3A%2F%2Frelay.example&relay=wss%3A%2F%2Fother.example",
            "$link&relay=wss%3A%2F%2Frelay.example%2Fprivate", "$link&relay=wss%3A%2F%2Frelay.example%3Ftoken%3Dx",
            "$link&relay=wss%3A%2F%2Frelay.example%3A0", link + "&x=" + "a".repeat(8192)
        )) assertThrows(IllegalArgumentException::class.java) { Pairing.parse(invalid) }
        assertArrayEquals(key, Pairing.parse(link).key)
    }

    @Test fun vaultRejectsTamperingAndTruncationAndRecoversAtomicBackup() = withRepository { context, _ ->
        val vault = PairingVault(context)
        val link = "hoppair://127.0.0.1:49271?key=$encodedKey"
        val file = File(context.filesDir, "pairing.enc")
        vault.save(link)
        val original = file.readBytes()
        assertEquals(link, vault.load())
        file.writeBytes(original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        assertThrows(Exception::class.java) { vault.load() }
        file.writeBytes(ByteArray(5))
        assertThrows(IllegalArgumentException::class.java) { vault.load() }
        File(context.filesDir, "pairing.enc.bak").writeBytes(original)
        assertEquals(link, vault.load())
        vault.clear()
        assertNull(vault.load())
    }

    @Test fun invalidSnapshotsCannotMutateOutboxOrStartDownloads() = withRepository { _, repo ->
        repo.addText("Keep my queued note")
        val original = repo.entries.single()
        val collision = text(original.id).put("text", "Remote replacement")
        repo.merge(JSONArray().put(collision)) { error("Unexpected download") }
        assertEquals("Keep my queued note", repo.entries.single().text)
        assertTrue(repo.entries.single().pending)
        val invalidRows = listOf(
            text().put("id", "../../outside"), text().put("kind", "html"), text().put("text", "€".repeat(23000)),
            text().put("kind", "link").put("text", "file:///private/data"), text().put("pinned", "true"),
            text().put("title", "x".repeat(4097)), text().put("createdAt", "now"), text().put("size", 0.5)
        )
        for (invalid in invalidRows) {
            val remote = JSONArray().put(fileItem()).put(invalid)
            var downloads = 0
            assertThrows(Exception::class.java) { repo.merge(remote) { downloads++; byteArrayOf() } }
            assertEquals("Validation must precede downloads", 0, downloads)
            assertEquals(1, repo.entries.size)
        }
        val duplicate = text()
        assertThrows(IllegalArgumentException::class.java) {
            repo.merge(JSONArray().put(duplicate).put(JSONObject(duplicate.toString()).put("id", duplicate.getString("id").uppercase()))) { error("Unexpected download") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            repo.merge(JSONArray().also { array -> repeat(501) { array.put(text()) } }) { error("Unexpected download") }
        }
        assertEquals(original.json.toString(), repo.entries.single().json.toString())
    }

    @Test fun fileSizeMismatchRollsBackNewFilesAndValidNamesCannotEscapeShelf() = withRepository { context, repo ->
        val first = fileItem().put("size", 3)
        val second = fileItem().put("size", 4)
        assertThrows(IllegalArgumentException::class.java) {
            repo.merge(JSONArray().put(first).put(second)) { byteArrayOf(1, 2, 3) }
        }
        assertTrue(repo.entries.isEmpty())
        assertFalse(repo.file(first.getString("id")).exists())
        assertFalse(repo.file(second.getString("id")).exists())
        val incoming = fileItem().put("filename", "../../outside.bin").put("size", 3)
        repo.merge(JSONArray().put(incoming)) { byteArrayOf(1, 2, 3) }
        val stored = repo.entries.single()
        assertEquals("outside.bin", stored.filename)
        assertEquals(File(context.filesDir, "shelf").canonicalFile, repo.file(stored.id).canonicalFile.parentFile)
        assertArrayEquals(byteArrayOf(1, 2, 3), repo.file(stored.id).readBytes())
        assertEquals("outside.bin", ShelfRepository(context).entries.single().filename)
    }

    private fun text(id: String = UUID.randomUUID().toString()) = JSONObject().put("id", id).put("kind", "text")
        .put("title", "Fixture").put("text", "Fixture text").put("size", 12).put("createdAt", 1.0).put("source", "Mac").put("pinned", false)
    private fun fileItem() = text().put("kind", "file").put("filename", "fixture.bin").put("mime", "application/octet-stream").put("size", 0)
    private fun withRepository(block: (ContextWrapper, ShelfRepository) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "security-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = directory }
        try { block(context, ShelfRepository(context)) } finally { directory.deleteRecursively() }
    }
    private fun withPeer(exchanges: Int = 1, responseKey: ByteArray = key, reply: (JSONObject) -> JSONObject): FixturePeer = FixturePeer(exchanges, responseKey, reply)
    private inner class FixturePeer(exchanges: Int, responseKey: ByteArray, reply: (JSONObject) -> JSONObject) : java.io.Closeable {
        private val server = ServerSocket(0)
        val pairing = Pairing("127.0.0.1", server.localPort, key)
        private val error = AtomicReference<Throwable>()
        private val worker = thread(isDaemon = true, name = "isolated-security-peer") {
            try {
                repeat(exchanges) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = DataInputStream(socket.getInputStream())
                        val length = input.readInt()
                        require(length in 28..MAX_FRAME)
                        val encrypted = ByteArray(length).also { input.readFully(it) }
                        val request = JSONObject(String(PayloadCrypto.open(encrypted, key), Charsets.UTF_8))
                        val response = PayloadCrypto.seal(reply(request).toString().toByteArray(Charsets.UTF_8), responseKey)
                        DataOutputStream(socket.getOutputStream()).apply { writeInt(response.size); write(response); flush() }
                    }
                }
            } catch (failure: Throwable) { if (!server.isClosed) error.set(failure) }
        }
        override fun close() {
            server.close(); worker.join(6000)
            check(!worker.isAlive) { "Fixture peer did not stop." }
            error.get()?.let { throw AssertionError("Fixture peer failed", it) }
        }
    }
}
