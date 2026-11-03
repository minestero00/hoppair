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

internal data class PairingReplacement(val id: String, val host: String)

internal data class ShelfState(
    val entries: List<ShelfEntry> = emptyList(), val paired: Boolean = false,
    val online: Boolean = false, val status: String = "Connect your Mac to get started",
    val error: String? = null, val busy: Boolean = false, val cameraRequest: String? = null,
    val host: String = "", val peerName: String = "Mac", val deviceName: String = "", val defaultDeviceName: String = "Android phone",
    val replacement: PairingReplacement? = null, val canDelete: Boolean = false
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
    private data class PendingPairing(val id: String, val revision: Long, val link: String, val previousKey: ByteArray, val onSuccess: () -> Unit)
    // Prompt publication/consumption is confined to Main; IO probes check the revision before committing.
    private val pairingGate = Mutex()
    private var pendingPairing: PendingPairing? = null
    private val pairingRevision = java.util.concurrent.atomic.AtomicLong()
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
    private fun friendly(error: Exception, connection: Pairing? = pairing): String = when (error) {
        is java.net.ConnectException, is java.net.SocketTimeoutException, is java.net.UnknownHostException, is java.net.NoRouteToHostException -> if (connection?.relay != null) "Check the internet connection on both devices and open Hoppair. Reconnecting automatically." else "Open Hoppair on your Mac and use the same network. Reconnecting automatically."
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
    private fun operation(onSuccess: () -> Unit = {}, block: suspend () -> Unit) {
        pendingOperations++
        state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                mutex.withLock {
                    state.update { it.copy(busy = true) }
                    withContext(Dispatchers.IO) {
                        checkNotNull(repository) { "Could not open local storage. Please restart the app." }
                        try { block() }
                        finally { state.update { it.copy(entries = repository!!.entries) } }
                    }
                    onSuccess()
                }
            } catch (error: Exception) { fail(error) }
            finally { pendingOperations--; state.update { it.copy(busy = pendingOperations > 0) } }
        }
    }
    fun pair(link: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            pairingGate.withLock {
                clearPairingPrompt()
                val revision = pairingRevision.get()
                var connected = false
                operation(onSuccess = { if (connected && revision == pairingRevision.get()) onSuccess() }) {
                    val candidate = Pairing.parse(link)
                    val current = pairing
                    if (current != null && !current.key.contentEquals(candidate.key)) {
                        withContext(Dispatchers.Main.immediate) {
                            pairingGate.withLock {
                                if (revision == pairingRevision.get()) {
                                    val id = java.util.UUID.randomUUID().toString()
                                    pendingPairing = PendingPairing(id, revision, link, current.key.copyOf(), onSuccess)
                                    state.update { it.copy(replacement = PairingReplacement(id, candidate.host), error = null) }
                                }
                            }
                        }
                    } else {
                        connected = connectPairing(link, candidate, revision)
                    }
                }
            }
        }
    }
    private fun clearPairingPrompt() {
        pairingRevision.incrementAndGet()
        pendingPairing = null
        state.update { it.copy(replacement = null) }
    }
    fun cancelPairingReplacement() {
        viewModelScope.launch { pairingGate.withLock { clearPairingPrompt() } }
    }
    fun confirmPairingReplacement(id: String) {
        viewModelScope.launch {
            pairingGate.withLock {
                val request = pendingPairing ?: return@withLock
                if (request.id != id) return@withLock
                pendingPairing = null
                state.update { it.copy(replacement = null) }
                var connected = false
                operation(onSuccess = { if (connected && request.revision == pairingRevision.get()) request.onSuccess() }) {
                    check(pairing?.key?.contentEquals(request.previousKey) == true) { "Your connection changed. Scan the QR code again." }
                    connected = connectPairing(request.link, Pairing.parse(request.link), request.revision)
                }
            }
        }
    }
    private suspend fun connectPairing(link: String, candidate: Pairing, revision: Long): Boolean {
        if (revision != pairingRevision.get()) return false
        val context = currentCoroutineContext()
        // Test the candidate route before replacing saved pairing or the current connection.
        val response = try { BridgeClient(candidate).use {
            it.setCancellationCheck { context.ensureActive() }
            it.call("list")
        }.also { repository!!.validateSnapshot(it.getJSONArray("items")) } }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            if (revision != pairingRevision.get()) return false
            throw PeerRejected(friendly(error, candidate).replace(" Reconnecting automatically.", " Try connecting again."))
        }
        // Serialize persistence with prompt cancellation/new requests without blocking Main on disk IO.
        return pairingGate.withLock {
            context.ensureActive()
            if (revision != pairingRevision.get()) return@withLock false
            vault.save(link.trim()); closeConnection(); pairing = candidate
            val name = cleanDeviceName(response.optString("deviceName", "Mac")).ifBlank { "Mac" }
            preferences.edit().putString("peer", name).apply()
            state.update { it.copy(paired = true, online = false, host = candidate.host, peerName = name, cameraRequest = null,
                error = null, canDelete = response.optBoolean("supportsDeletion", false), status = "Loading your shelf…") }
            true
        }
    }

    fun addText(text: String, onSuccess: () -> Unit = {}) = operation(onSuccess) {
        repository!!.addText(text)
        state.update { it.copy(status = "Queued for transfer") }
    }
    fun addFiles(uris: List<Uri>) = operation {
        for (uri in uris) repository!!.addFile(uri)
        state.update { it.copy(status = "Queued for transfer") }
    }
    fun deleteEntry(id: String, onSuccess: () -> Unit = {}) = operation(onSuccess) {
        check(state.value.canDelete) { "Update Hoppair on your Mac and reconnect to delete shared items." }
        val client = connection(checkNotNull(pairing) { "Connect your Mac to delete shared items." })
        val context = currentCoroutineContext()
        client.setCancellationCheck { context.ensureActive() }
        try {
            client.call("delete", id = id)
            repository!!.remove(setOf(id))
            state.update { it.copy(status = "Deleted from the shared shelf") }
        } catch (error: Exception) {
            closeConnection()
            state.update { it.copy(online = false, status = friendly(error)) }
            throw error
        } finally { syncClient?.setCancellationCheck {} }
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
                    val incoming = repo.validateSnapshot(response.getJSONArray("items"))
                    val supportsDeletion = response.optBoolean("supportsDeletion", false)
                    val requested = (repo.entries.map { it.id } + incoming.map { it.id }).toSet()
                    val deleted = if (supportsDeletion && requested.isNotEmpty())
                        repo.applyDeletions(client.call("deleted", ids = requested.toList()).getJSONArray("deleted"), requested)
                        else emptySet()
                    val queuedAtSnapshot = repo.entries.filter { it.pending }.map { it.id }.toSet()
                    // Send local changes before downloads so one unavailable remote file cannot starve them.
                    val uploads = repo.uploadPending { entry, bytes -> client.call("put", entry.json, bytes) }
                    // The snapshot predates these acknowledgements; do not replace just-sent
                    // content with its older peer copy during this pass.
                    val received = repo.merge(incoming.filterNot { it.id in queuedAtSnapshot || it.id in deleted }, client)
                    state.update { it.copy(entries = repo.entries) }
                    received.interruption?.let { throw it }
                    val unavailableFiles = received.unavailableFiles
                    val rejected = when {
                        uploads.missingFiles > 0 -> "${uploads.missingFiles} queued ${if (uploads.missingFiles == 1) "file is" else "files are"} unavailable on this phone. Other items can still sync."
                        uploads.rejectedItems > 0 -> "${uploads.rejectedItems} ${if (uploads.rejectedItems == 1) "item is" else "items are"} waiting to send. ${friendly(checkNotNull(uploads.rejection))}"
                        else -> null
                    }
                    val peerName = cleanDeviceName(response.optString("deviceName", "Mac")).trim().ifBlank { "Mac" }
                    if (response.optBoolean("syncAcknowledgement", false)) client.call("syncAck", deviceName = ownName())
                    if (peerName != state.value.peerName) preferences.edit().putString("peer", peerName).apply()
                    val camera = if (response.isNull("cameraRequest")) null else response.optString("cameraRequest").takeIf { it.isNotEmpty() }
                    val transferStatus = if (received.capacityLimited > 0)
                        "This phone’s shelf is full. ${received.capacityLimited} more items are waiting on your Mac."
                        else if (unavailableFiles > 0)
                        "$unavailableFiles ${if (unavailableFiles == 1) "file is" else "files are"} waiting to download. Other items can still sync. Retrying automatically."
                        else "Everything is up to date"
                    state.update { it.copy(entries = repo.entries, online = true, peerName = peerName, canDelete = supportsDeletion, status = rejected ?: transferStatus, cameraRequest = camera) }
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
