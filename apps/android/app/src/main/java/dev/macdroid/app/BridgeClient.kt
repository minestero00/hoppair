package dev.macdroid.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.*
import okio.ByteString.Companion.toByteString
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal const val MAX_FILE = 8 * 1024 * 1024
internal const val MAX_FRAME = 12 * 1024 * 1024

internal data class Pairing(val host: String, val port: Int, val key: ByteArray, val relay: String? = null) {
    companion object {
        fun parse(link: String): Pairing {
            val uri = URI(link.trim())
            require(uri.scheme == "macdroid" && !uri.host.isNullOrBlank() && uri.port in 1..65535) { "Paste the pairing link from your Mac." }
            val encoded = uri.rawQuery?.split("&")?.firstOrNull { it.startsWith("key=") }?.removePrefix("key=") ?: error("The pairing key is missing.")
            val key = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP)
            require(key.size == 32) { "Invalid pairing key." }
            val relay = uri.rawQuery?.split("&")?.firstOrNull { it.startsWith("relay=") }?.removePrefix("relay=")?.let { URLDecoder.decode(it, "UTF-8") }
            if (relay != null) {
                val remote = URI(relay)
                require(remote.userInfo == null && !remote.host.isNullOrBlank() && (remote.scheme == "wss" ||
                    (BuildConfig.DEBUG && remote.scheme == "ws" && remote.host in listOf("127.0.0.1", "localhost", "10.0.2.2")))) { "Check the relay address." }
            }
            return Pairing(uri.host, uri.port, key, relay)
        }
    }
}

internal object PayloadCrypto {
    fun seal(plain: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.iv + cipher.doFinal(plain)
    }
    fun open(encrypted: ByteArray, key: ByteArray): ByteArray {
        require(encrypted.size >= 28) { "Invalid frame." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
        return cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
    }
}

internal class PeerRejected(message: String) : Exception(message)

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
    private var localFailed = false
    private var relay: RelaySocket? = null
    fun call(action: String, item: JSONObject? = null, data: ByteArray? = null, id: String? = null): JSONObject {
        val request = JSONObject().put("version", 1).put("requestId", UUID.randomUUID().toString())
            .put("issuedAt", System.currentTimeMillis() / 1000.0).put("action", action)
        item?.let { request.put("item", it) }
        data?.let { request.put("data", Base64.encodeToString(it, Base64.NO_WRAP)) }
        id?.let { request.put("id", it) }
        val encrypted = PayloadCrypto.seal(request.toString().toByteArray(Charsets.UTF_8), pairing.key)
        require(encrypted.size <= MAX_FRAME) { "Transfer exceeds the size limit." }
        if (!localFailed) {
            try { return decode(local(encrypted)) }
            catch (error: Exception) {
                if (error is PeerRejected || pairing.relay == null) throw error
                localFailed = true
            }
        }
        val channel = relay ?: RelaySocket(pairing).also { relay = it }
        val response = decode(channel.exchange(encrypted))
        require(response.optString("requestId") == request.getString("requestId")) { "Could not verify the response. Please reconnect." }
        return response
    }
    private fun local(encrypted: ByteArray): ByteArray = Socket().use { socket ->
        socket.connect(InetSocketAddress(pairing.host, pairing.port), if (pairing.relay != null) 900 else 3500)
        socket.soTimeout = 15000
        val output = DataOutputStream(socket.getOutputStream())
        output.writeInt(encrypted.size); output.write(encrypted); output.flush()
        val input = DataInputStream(socket.getInputStream())
        val length = input.readInt()
        require(length in 28..MAX_FRAME) { "Invalid response size." }
        ByteArray(length).also { input.readFully(it) }
    }
    private fun decode(encrypted: ByteArray): JSONObject = JSONObject(String(PayloadCrypto.open(encrypted, pairing.key), Charsets.UTF_8)).also {
        if (!it.optBoolean("ok")) throw PeerRejected(it.optString("error", "Transfer failed."))
    }
    override fun close() { relay?.close(); relay = null }
}

private class RelaySocket(pairing: Pairing) : java.io.Closeable {
    private val opened = CountDownLatch(1)
    private val responses = LinkedBlockingQueue<Result<ByteArray>>()
    @Volatile private var openError: Exception? = null
    private val socket: okhttp3.WebSocket
    init {
        val base = URI(checkNotNull(pairing.relay))
        val endpoint = URI(base.scheme, null, base.host, base.port, "/room/" + RelayIdentity.room(pairing.key), "role=phone", null)
        val request = Request.Builder().url(endpoint.toString()).header("Authorization", "Bearer " + RelayIdentity.auth(pairing.key)).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: Response) { opened.countDown() }
            override fun onMessage(webSocket: okhttp3.WebSocket, bytes: okio.ByteString) {
                if (bytes.size in 28..MAX_FRAME) responses.offer(Result.success(bytes.toByteArray()))
                else responses.offer(Result.failure(java.io.IOException("Transfer exceeds the size limit.")))
            }
            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: Response?) {
                val error = java.io.IOException("Could not connect through the relay. Check your Mac’s internet connection and open MacDroid.")
                openError = error; opened.countDown(); responses.offer(Result.failure(error))
            }
            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                responses.offer(Result.failure(java.io.IOException("Connection lost. Retrying shortly.")))
            }
            override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        })
        if (!opened.await(6, TimeUnit.SECONDS) || openError != null) {
            socket.cancel(); throw openError ?: java.net.SocketTimeoutException()
        }
    }
    fun exchange(payload: ByteArray): ByteArray {
        if (!socket.send(payload.toByteString())) throw java.io.IOException("Connection lost.")
        return responses.poll(15, TimeUnit.SECONDS)?.getOrThrow() ?: throw java.net.SocketTimeoutException()
    }
    override fun close() { socket.close(1000, null) }
    companion object {
        private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS).build()
    }
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
        if (!file.exists()) return null
        val data = file.readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return String(cipher.doFinal(data.copyOfRange(12, data.size)))
    }
    fun clear() { file.delete() }
}
