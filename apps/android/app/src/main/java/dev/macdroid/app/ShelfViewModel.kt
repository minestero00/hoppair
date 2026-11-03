package dev.macdroid.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    val host: String = ""
)

internal class ShelfViewModel(application: Application) : AndroidViewModel(application) {
    private val state = MutableStateFlow(ShelfState())
    val ui = state.asStateFlow()
    private val mutex = Mutex()
    private var pendingOperations = 0
    private val vault = PairingVault(application)
    private var pairing: Pairing? = null
    private var repository: ShelfRepository? = null
    init {
        viewModelScope.launch {
            mutex.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        repository = ShelfRepository(application)
                        pairing = vault.load()?.let(Pairing::parse)
                        state.update { it.copy(entries = repository!!.entries, paired = pairing != null, host = pairing?.host ?: "") }
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
        is java.net.ConnectException, is java.net.SocketTimeoutException, is java.net.UnknownHostException, is java.net.NoRouteToHostException -> if (pairing?.relay != null) "Check the internet connection on both devices and open Hoppair. Pending items will retry automatically." else "Could not reach your Mac. Connect both devices to the same network and open Hoppair."
        is javax.crypto.AEADBadTagException, is java.io.EOFException -> "Could not verify this connection. Scan your Mac’s pairing QR code again."
        is java.net.URISyntaxException -> "Use a Hoppair pairing QR code or pairing link."
        else -> error.localizedMessage ?: "Could not complete the action. Please try again shortly."
    }
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
                finally { pendingOperations--; state.update { it.copy(busy = pendingOperations > 0) } }
            }
        }
    }
    fun pair(link: String, onSuccess: () -> Unit = {}) = operation(onSuccess) {
        val candidate = Pairing.parse(link)
        require(pairing == null || pairing!!.key.contentEquals(candidate.key)) { "This preview supports one Mac. You can update its connection address." }
        BridgeClient(candidate).use { it.call("list") } // Do not replace a working pairing until the peer authenticates.
        vault.save(link.trim()); pairing = candidate
        state.update { it.copy(paired = true, online = true, host = candidate.host, status = "Connected · loading your shelf") }
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
                    BridgeClient(pair).use { client ->
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
                    val camera = if (response.isNull("cameraRequest")) null else response.optString("cameraRequest").takeIf { it.isNotEmpty() }
                    state.update { it.copy(entries = repo.entries, online = true, status = rejected ?: "Everything is up to date", cameraRequest = camera) }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    state.update { it.copy(entries = repo.entries, online = false, status = friendly(error)) }
                }
            }
        }
    }
    fun dismissCamera() = operation {
        val id = state.value.cameraRequest ?: return@operation
        BridgeClient(checkNotNull(pairing)).use { it.call("cameraAck", id = id) }
        state.update { it.copy(cameraRequest = null) }
    }
    fun contentFile(entry: ShelfEntry) = repository?.file(entry.id)
}
