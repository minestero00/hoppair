package dev.macdroid.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Real loopback WebSocket: hold a large upload in the writer while PINGs arrive. */
@RunWith(AndroidJUnit4::class)
class RelayBackpressureTest {
    @Test fun slowUploadSurvivesPingBurstAndReturnsAuthenticatedReply() {
        val key = ByteArray(32) { 7 }
        val server = ServerSocket(0)
        val failure = AtomicReference<Throwable>()
        val worker = thread(isDaemon = true, name = "relay-backpressure-fixture") {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 20000
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) {
                        check(header.length < 16384)
                        val next = input.read()
                        check(next >= 0)
                        header.append(next.toChar())
                    }
                    val nonce = header.lines().single { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
                    val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((nonce + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                    output.writeBytes("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n")
                    output.flush()
                    assertEquals(0x82, input.readUnsignedByte())
                    val sizeByte = input.readUnsignedByte()
                    assertEquals(0x80, sizeByte and 0x80)
                    val size = when (sizeByte and 0x7f) {
                        127 -> input.readLong().also { require(it in 28..MAX_FRAME.toLong()) }.toInt()
                        126 -> input.readUnsignedShort()
                        else -> sizeByte and 0x7f
                    }
                    val mask = ByteArray(4).also { input.readFully(it) }
                    // The writer cannot flush the 11 MiB request while this peer
                    // deliberately stops reading. PONGs must stay bounded, not abort it.
                    repeat(40) {
                        output.write(byteArrayOf(0x89.toByte(), 1, it.toByte()))
                        output.flush()
                        Thread.sleep(25)
                    }
                    val encrypted = ByteArray(size).also { input.readFully(it) }
                    for (i in encrypted.indices) encrypted[i] = (encrypted[i].toInt() xor mask[i % 4].toInt()).toByte()
                    val request = JSONObject(String(PayloadCrypto.open(encrypted, key)))
                    assertEquals("put", request.getString("action"))
                    val response = PayloadCrypto.seal(JSONObject().put("ok", true).put("requestId", request.getString("requestId")).toString().toByteArray(), key)
                    output.writeByte(0x82)
                    if (response.size < 126) output.writeByte(response.size) else { output.writeByte(126); output.writeShort(response.size) }
                    output.write(response); output.flush()
                    Thread.sleep(200)
                }
            } catch (error: Throwable) { failure.set(error) }
        }
        try {
            val item = JSONObject().put("id", UUID.randomUUID().toString()).put("kind", "file").put("title", "Fixture")
            BridgeClient(Pairing("127.0.0.1", 1, key, "ws://127.0.0.1:${server.localPort}")).use {
                assertTrue(it.call("put", item, ByteArray(MAX_FILE)).getBoolean("ok"))
            }
        } finally {
            server.close()
            worker.join(25000)
            assertFalse("Fixture peer did not stop", worker.isAlive)
            failure.get()?.let { throw AssertionError("Fixture peer failed", it) }
        }
    }
}
