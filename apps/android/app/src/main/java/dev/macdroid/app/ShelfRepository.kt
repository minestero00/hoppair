package dev.macdroid.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class ShelfEntry(val json: JSONObject, val pending: Boolean = false) {
    val id: String get() = json.getString("id")
    val title: String get() = json.getString("title")
    val kind: String get() = json.getString("kind")
    val text: String? get() = if (json.isNull("text")) null else json.optString("text").takeIf { it.isNotEmpty() }
    val filename: String get() = json.optString("filename", title)
    val size: Int get() = json.optInt("size")
    val pinned: Boolean get() = json.optBoolean("pinned")
}

/** Called on one IO coroutine under the ViewModel's mutex. */
internal class ShelfRepository(private val context: Context) {
    private val root = File(context.filesDir, "shelf").apply { mkdirs() }
    private val index = AtomicFile(File(context.filesDir, "shelf.json"))
    var entries: List<ShelfEntry> = load()
        private set
    private fun load(): List<ShelfEntry> {
        if (!index.baseFile.exists()) return emptyList()
        val array = JSONArray(index.openRead().use { String(it.readBytes()) })
        return (0 until array.length()).map { i ->
            val row = array.getJSONObject(i)
            ShelfEntry(row.getJSONObject("item"), row.optBoolean("pending"))
        }
    }
    private fun commit(next: List<ShelfEntry>) {
        val array = JSONArray()
        next.forEach { array.put(JSONObject().put("item", it.json).put("pending", it.pending)) }
        if (index.baseFile.exists() && next.size == entries.size && next.zip(entries).all { (a, b) -> a.pending == b.pending && a.json.toString() == b.json.toString() }) return
        val output = index.startWrite()
        try { output.write(array.toString().toByteArray()); index.finishWrite(output); entries = next }
        catch (error: Exception) { index.failWrite(output); throw error }
    }
    fun file(id: String): File {
        require(UUID.fromString(id).toString().equals(id, ignoreCase = true)) { "Invalid item ID." }
        return File(root, id.lowercase())
    }
    private fun storeFile(id: String, bytes: ByteArray) {
        require(bytes.size <= MAX_FILE) { "Files must be 8 MiB or smaller." }
        val atomic = AtomicFile(file(id)); val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }
    private fun item(kind: String, title: String): JSONObject {
        require(entries.size < 500) { "The shelf can hold up to 500 items." }
        return JSONObject().put("id", UUID.randomUUID().toString())
        .put("kind", kind).put("title", title.take(90)).put("size", 0)
        .put("createdAt", System.currentTimeMillis() / 1000.0).put("source", "Android").put("pinned", false)
    }
    fun addText(value: String) {
        val text = value.trim(); require(text.isNotEmpty()) { "Enter some text first." }
        require(text.toByteArray().size <= 64 * 1024) { "Text must be 64 KiB or smaller." }
        val uri = runCatching { URIHelper.web(text) }.getOrDefault(false)
        val json = item(if (uri) "link" else "text", text).put("text", text).put("size", text.toByteArray().size)
        commit(listOf(ShelfEntry(json, true)) + entries)
    }
    fun addFile(uri: Uri) {
        var name = "Files"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        }
        val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= MAX_FILE) {
                val count = stream.read(buffer, 0, minOf(buffer.size, MAX_FILE + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.toByteArray() } ?: error("Could not read the file.")
        require(bytes.size <= MAX_FILE) { "Files must be 8 MiB or smaller." }
        val json = item("file", name).put("filename", name.take(160)).put("mime", context.contentResolver.getType(uri) ?: "application/octet-stream").put("size", bytes.size)
        storeFile(json.getString("id"), bytes)
        commit(listOf(ShelfEntry(json, true)) + entries)
    }
    fun acknowledge(id: String) { commit(entries.map { if (it.id == id) it.copy(pending = false) else it }) }
    fun merge(remote: JSONArray, client: BridgeClient) {
        val next = entries.toMutableList()
        for (i in 0 until remote.length()) {
            val json = remote.getJSONObject(i)
            val entry = ShelfEntry(json)
            val existing = next.indexOfFirst { it.id == entry.id }
            if (entry.kind == "file" && !file(entry.id).exists()) {
                val response = client.call("get", id = entry.id)
                val bytes = android.util.Base64.decode(response.getString("data"), android.util.Base64.DEFAULT)
                storeFile(entry.id, bytes)
            }
            if (existing >= 0) next[existing] = entry else next.add(entry)
        }
        commit(next.sortedByDescending { it.json.optDouble("createdAt") })
    }
}
private object URIHelper {
    fun web(text: String): Boolean {
        val uri = java.net.URI(text)
        return (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
    }
}
