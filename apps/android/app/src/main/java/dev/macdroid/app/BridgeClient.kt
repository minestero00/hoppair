package dev.macdroid.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import org.json.JSONArray
import okio.buffer
import okio.sink
import okio.source
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.java_websocket.WebSocket
import org.java_websocket.framing.Framedata
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.nio.ByteBuffer
import javax.net.ssl.SSLParameters
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal const val MAX_FILE = 8 * 1024 * 1024
internal const val MAX_FRAME = 12 * 1024 * 1024
private const val MAX_PAIRING_LINK = 8192

internal data class Pairing(val host: String, val port: Int, val key: ByteArray, val relay: String? = null) {
    companion object {
        fun parse(link: String): Pairing {
            require(link.length <= MAX_PAIRING_LINK && link.toByteArray(Charsets.UTF_8).size <= MAX_PAIRING_LINK) { "Invalid pairing code." }
            val outer = URI(link.trim())
            val uri = if (outer.scheme == "https") {
                require(outer.userInfo == null && outer.rawQuery == null && outer.port in listOf(-1, 443) &&
                    ((outer.host == "hoppair-connect.macdroid-relay.workers.dev" && outer.path in listOf("/connect", "/connect/")) ||
                    (outer.host == "minestero00.github.io" && outer.path == "/hoppair/connect/"))) { "Use the pairing QR code shown on your Mac." }
                val payload = outer.rawFragment?.takeIf { it.startsWith("pair=") && !it.contains('&') }?.removePrefix("pair=")
                    ?: error("The pairing code is missing.")
                require(payload.length <= 4096 && payload.matches(Regex("[A-Za-z0-9_-]+={0,2}"))) { "Invalid pairing code." }
                URI(String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
            } else outer
            require(uri.scheme in setOf("hoppair", "macdroid") && uri.userInfo == null && uri.fragment == null && uri.path in listOf("", "/") && !uri.host.isNullOrBlank() && uri.port in 1..65535) { "Paste the pairing link from your Mac." }
            val parameters = uri.rawQuery?.split("&").orEmpty()
            require(parameters.all { it.startsWith("key=") || it.startsWith("relay=") } &&
                parameters.count { it.startsWith("key=") } == 1 &&
                parameters.count { it.startsWith("relay=") } <= 1) { "Invalid pairing code." }
            val encoded = parameters.single { it.startsWith("key=") }.removePrefix("key=")
            val decodedKey = URLDecoder.decode(encoded, "UTF-8")
            require(decodedKey.matches(Regex("[A-Za-z0-9_-]{43}=?"))) { "Invalid pairing key." }
            val key = Base64.decode(decodedKey, Base64.URL_SAFE or Base64.NO_WRAP)
            require(key.size == 32) { "Invalid pairing key." }
            val relay = parameters.singleOrNull { it.startsWith("relay=") }?.removePrefix("relay=")?.let { URLDecoder.decode(it, "UTF-8") }
            if (relay != null) {
                val remote = URI(relay)
                require(remote.userInfo == null && remote.fragment == null && remote.rawQuery == null &&
                    remote.path in listOf("", "/") && remote.port in -1..65535 && remote.port != 0 &&
                    !remote.host.isNullOrBlank() && (remote.scheme == "wss" ||
                    (BuildConfig.DEBUG && remote.scheme == "ws" && remote.host in listOf("127.0.0.1", "localhost", "10.0.2.2")))) { "Check the relay address." }
            }
            return Pairing(uri.host, uri.port, key, relay)
        }
    }
}

internal object PayloadCrypto {
    fun seal(plain: ByteArray, key: ByteArray): ByteArray {
        require(key.size == 32 && plain.size <= MAX_FRAME - 28) { "Invalid frame." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.iv + cipher.doFinal(plain)
    }
    fun open(encrypted: ByteArray, key: ByteArray): ByteArray {
        require(key.size == 32 && encrypted.size in 28..MAX_FRAME) { "Invalid frame." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
        return cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
    }
}

internal class PeerRejected(message: String) : Exception(message)
internal class PeerVerificationFailed(message: String, cause: Exception? = null) : Exception(message, cause)

// Stable v1 derivation labels preserve existing relay enrollment across the rename.
internal object RelayIdentity {
    private fun derive(label: String, key: ByteArray): ByteArray = javax.crypto.Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(label.toByteArray())
    }
    fun room(key: ByteArray): String {
        val hex = derive("macdroid-room-v1", key).take(16).joinToString("") { "%02x".format(it.toInt() and 255) }
        return "${hex.take(8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }
    fun auth(key: ByteArray): String = Base64.encodeToString(derive("macdroid-relay-auth-v1", key), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

internal class BridgeClient(private val pairing: Pairing) : java.io.Closeable {
    private var cancellationCheck: () -> Unit = {}
    fun setCancellationCheck(check: () -> Unit = {}) { cancellationCheck = check }
    private var localFailed = false
    private var relay: RelaySocket? = null
    @Synchronized fun call(action: String, item: JSONObject? = null, data: ByteArray? = null, id: String? = null, deviceName: String? = null): JSONObject =
        if (action == "list") listPages() else callSingle(action, item, data, id, deviceName)
    private fun listPages(): JSONObject {
        val items = JSONArray()
        val seenItems = mutableSetOf<String>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        var first: JSONObject? = null
        do {
            val page = try { callSingle("listPage", id = cursor) }
            catch (error: PeerRejected) {
                if (error.message == "Unsupported action.") throw PeerRejected("Update Hoppair on your Mac, then reconnect.")
                throw error
            }
            if (first == null) first = page
            val batch = page.getJSONArray("items")
            require(batch.length() <= 100 && items.length() + batch.length() <= MAX_SHELF_ITEMS) { "Invalid shelf page size." }
            for (i in 0 until batch.length()) {
                val item = batch.getJSONObject(i)
                val itemId = item.getString("id")
                require(UUID.fromString(itemId).toString().equals(itemId, ignoreCase = true) && seenItems.add(itemId.lowercase())) { "Invalid or duplicate shelf item." }
                items.put(item)
            }
            cursor = if (page.isNull("nextCursor")) null else page.get("nextCursor") as? String ?: error("Invalid shelf cursor.")
            if (cursor != null) {
                require(batch.length() > 0 && items.length() < MAX_SHELF_ITEMS &&
                    UUID.fromString(cursor).toString().equals(cursor, ignoreCase = true) &&
                    cursor.equals(batch.getJSONObject(batch.length() - 1).getString("id"), ignoreCase = true) && seenCursors.add(cursor.lowercase())) { "Invalid shelf cursor." }
            }
        } while (cursor != null)
        return checkNotNull(first).put("items", items).also { it.remove("nextCursor") }
    }
    private fun callSingle(action: String, item: JSONObject? = null, data: ByteArray? = null, id: String? = null, deviceName: String? = null): JSONObject {
        cancellationCheck()
        require(data == null || data.size <= MAX_FILE) { "Files must be 8 MiB or smaller." }
        val request = JSONObject().put("version", 1).put("requestId", UUID.randomUUID().toString())
            .put("issuedAt", System.currentTimeMillis() / 1000.0).put("action", action)
        item?.let { request.put("item", it) }
        data?.let { request.put("data", Base64.encodeToString(it, Base64.NO_WRAP)) }
        id?.let { request.put("id", it) }
        deviceName?.let { request.put("deviceName", it) }
        val encrypted = PayloadCrypto.seal(request.toString().toByteArray(Charsets.UTF_8), pairing.key)
        require(encrypted.size <= MAX_FRAME) { "Transfer exceeds the size limit." }
        if (!localFailed) {
            try { return decode(local(encrypted), request.getString("requestId")) }
            catch (error: Exception) {
                if (error is java.util.concurrent.CancellationException || error is PeerRejected || error is PeerVerificationFailed || pairing.relay == null) throw error
                localFailed = true
            }
        }
        // Cancellation must not turn a failed local attempt into another network
        // exchange after the app has moved to the background.
        cancellationCheck()
        val channel = relay ?: RelaySocket(pairing).also { relay = it }
        return try { decode(channel.exchange(encrypted), request.getString("requestId")) }
        catch (error: Exception) { channel.abort(); relay = null; throw error }
    }
    private fun local(encrypted: ByteArray): ByteArray = Socket().use { socket ->
        socket.connect(InetSocketAddress(pairing.host, pairing.port), if (pairing.relay != null) 900 else 3500)
        // SO_TIMEOUT alone does not bound writes. Socket-backed Okio timeouts
        // close this owned socket if either direction exceeds one total deadline.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        val output = socket.sink().also { it.timeout().deadlineNanoTime(deadline) }.buffer()
        val input = socket.source().also { it.timeout().deadlineNanoTime(deadline) }.buffer()
        output.writeInt(encrypted.size); output.write(encrypted); output.flush()
        val length = input.readInt()
        require(length in 28..MAX_FRAME) { "Invalid response size." }
        input.readByteArray(length.toLong())
    }
    private fun decode(encrypted: ByteArray, requestId: String): JSONObject {
        val response = try { JSONObject(String(PayloadCrypto.open(encrypted, pairing.key), Charsets.UTF_8)) }
        catch (error: Exception) { throw PeerVerificationFailed("Could not verify this connection. Scan your Mac’s pairing QR code again.", error) }
        if (!response.has("requestId")) throw PeerVerificationFailed("Update Hoppair on your Mac, then reconnect. This Mac cannot verify replies yet.")
        if (response.opt("requestId") != requestId || response.opt("ok") !is Boolean) {
            throw PeerVerificationFailed("Could not verify the response. Please reconnect.")
        }
        if (!response.getBoolean("ok")) throw PeerRejected(response.optString("error", "Transfer failed.").take(240).filter { !it.isISOControl() })
        return response
    }
    override fun close() { relay?.close(); relay = null }
}

/** Only allowlisted diagnostics reach UI; remote reason strings and exception text never do. */
internal object RelayFailure {
    fun message(status: Int? = null, closeCode: Int? = null, reason: String? = null, error: Exception? = null): String = when {
        status == 401 || status == 403 -> "Relay registration was rejected. Check relay registration on your Mac."
        status == 429 -> "Relay reconnect limit reached. Retrying shortly."
        status == 503 || closeCode == 1013 -> "Your Mac is not available through the relay. Open Hoppair on your Mac."
        status != null && status >= 500 -> "Relay is temporarily unavailable. Retrying shortly."
        closeCode == 1008 && reason == "Transfer limit reached" -> "Relay transfer limit reached. Try again shortly."
        closeCode == 1008 && reason == "Device registration revoked" -> "Relay registration was revoked. Check relay registration on your Mac."
        closeCode == 1008 -> "Relay session ended. Retrying shortly."
        error is javax.net.ssl.SSLException -> "Could not verify the relay’s secure connection. Check your network."
        error is java.net.UnknownHostException -> "Could not find the relay. Check your internet connection."
        error is java.net.SocketTimeoutException -> "Relay did not respond in time. Retrying shortly."
        else -> "Relay connection interrupted. Retrying shortly."
    }
}

private class RelaySocket(pairing: Pairing) : java.io.Closeable {
    private val opened = CountDownLatch(1)
    private val responses = LinkedBlockingQueue<Result<ByteArray>>(1)
    private val stateLock = Any()
    private val sendLock = Any()
    private var awaitingResponse = false
    private var pongQueued = false
    private var terminalError: Exception? = null
    @Volatile private var openError: Exception? = null
    @Volatile private var handshakeStatus: Int? = null
    private val socket: WebSocketClient
    init {
        val base = URI(checkNotNull(pairing.relay))
        val endpoint = URI(base.scheme, null, base.host, base.port, "/room/" + RelayIdentity.room(pairing.key), "role=phone", null)
        socket = object : WebSocketClient(endpoint, BoundedRelayDraft { handshakeStatus = it }, mapOf("Authorization" to "Bearer " + RelayIdentity.auth(pairing.key)), 5000) {
            override fun onOpen(handshake: ServerHandshake) { opened.countDown() }
            override fun onMessage(message: String) {
                fail(java.io.IOException("Invalid relay message.")); abort()
            }
            override fun onMessage(bytes: ByteBuffer) {
                if (bytes.remaining() !in 28..MAX_FRAME) {
                    fail(java.io.IOException("Transfer exceeds the size limit.")); abort(); return
                }
                synchronized(stateLock) {
                    // One exchange is in flight; unsolicited/replayed frames never build a queue.
                    if (awaitingResponse) {
                        awaitingResponse = false
                        responses.offer(Result.success(ByteArray(bytes.remaining()).also { bytes.get(it) }))
                    }
                }
            }
            override fun onError(error: Exception) {
                val friendly = java.io.IOException(RelayFailure.message(handshakeStatus, error = error))
                openError = friendly; opened.countDown(); fail(friendly)
                // The library may otherwise wait on a close handshake after malformed input.
                runCatching { getSocket()?.close() }
            }
            override fun onClose(code: Int, reason: String, remote: Boolean) {
                val error = java.io.IOException(RelayFailure.message(handshakeStatus, code, reason))
                if (opened.count > 0) openError = error
                opened.countDown(); fail(error)
            }
            override fun onWebsocketPing(connection: WebSocket, frame: Framedata) {
                synchronized(sendLock) {
                    // A queued file/request is normal on a slow mobile uplink.
                    // Allow one bounded PONG alongside it; coalesce further pings
                    // until the writer drains, instead of aborting a valid transfer.
                    if (!connection.hasBufferedData()) pongQueued = false
                    if (!pongQueued) {
                        pongQueued = true
                        super.onWebsocketPing(connection, frame)
                    }
                }
            }
            override fun onSetSSLParameters(parameters: SSLParameters) {
                // Preserve system trust, SNI from the URI, and TLS hostname verification.
                super.onSetSSLParameters(parameters)
                parameters.endpointIdentificationAlgorithm = "HTTPS"
            }
        }
        socket.setReceiveBufferSize(8192)
        socket.setConnectionLostTimeout(0) // No automatic PING queue; every request has a 15-second response deadline.
        socket.setDaemon(true)
        socket.connect()
        if (!opened.await(6, TimeUnit.SECONDS) || openError != null || !socket.isOpen) {
            abort(); throw openError ?: java.net.SocketTimeoutException()
        }
    }
    private fun fail(error: Exception) = synchronized(stateLock) {
        terminalError = error
        if (awaitingResponse) { awaitingResponse = false; responses.offer(Result.failure(error)) }
    }
    fun exchange(payload: ByteArray): ByteArray {
        synchronized(stateLock) {
            terminalError?.let { throw it }
            responses.clear(); awaitingResponse = true
        }
        try {
            synchronized(sendLock) {
                // Calls are serialized and each authenticated response acknowledges
                // the previous request. A queued PONG must not reject the next request.
                if (!socket.isOpen) throw java.io.IOException("Connection lost.")
                socket.send(payload)
            }
            return responses.poll(15, TimeUnit.SECONDS)?.getOrThrow() ?: throw java.net.SocketTimeoutException()
        } finally { synchronized(stateLock) { awaitingResponse = false } }
    }
    fun abort() {
        runCatching { socket.socket?.close() }
        socket.closeConnection(1000, "Closed")
    }
    override fun close() { abort() }
}

/** Only the pairing secret is stored here. The shelf is local app-private data. */
internal class PairingVault(context: Context) {
    private val file = java.io.File(context.filesDir, "pairing.enc")
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("macdroid.pairing", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("macdroid.pairing", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(link: String) {
        Pairing.parse(link)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        val atomic = android.util.AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(cipher.iv + cipher.doFinal(link.toByteArray())); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }
    fun load(): String? {
        val atomic = android.util.AtomicFile(file)
        if (!file.exists() && !java.io.File(file.path + ".bak").exists()) return null
        val data = atomic.openRead().use { it.readBytesBounded(MAX_PAIRING_LINK + 28) }
        require(data.size >= 28) { "Invalid saved pairing. Scan your Mac’s pairing QR code again." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8).also { Pairing.parse(it) }
    }
    fun clear() { android.util.AtomicFile(file).delete() }
}

/** Reads at most limit + 1 bytes before rejecting; never allocates from an untrusted length. */
internal fun java.io.InputStream.readBytesBounded(limit: Int, message: String = "Stored data exceeds the size limit."): ByteArray {
    require(limit in 0 until Int.MAX_VALUE)
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() <= limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
        if (count < 0) return output.toByteArray()
        if (count == 0) {
            val byte = read()
            if (byte < 0) return output.toByteArray()
            output.write(byte)
        } else output.write(buffer, 0, count)
    }
    throw IllegalArgumentException(message)
}
