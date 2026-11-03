package dev.macdroid.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal const val MAX_SHELF_ITEMS = 500
// At most 500 text items with 64 KiB each, including JSON's worst-case control-character escaping.
private const val MAX_INDEX_BYTES = 208 * 1024 * 1024

/** Validate before any download, mutation, or UI use; retain only protocol fields. */
internal fun validatedShelfItem(input: JSONObject): JSONObject {
    fun string(name: String, maxBytes: Int): String {
        val value = input.get(name)
        require(value is String && value.toByteArray(Charsets.UTF_8).size <= maxBytes) { "Invalid shelf item." }
        return value
    }
    val id = string("id", 36)
    require(UUID.fromString(id).toString().equals(id, ignoreCase = true)) { "Invalid item ID." }
    val kind = string("kind", 4)
    require(kind in setOf("text", "link", "file")) { "Invalid shelf item type." }
    val createdAt = input.get("createdAt")
    require(createdAt is Number && createdAt.toDouble().isFinite()) { "Invalid shelf item date." }
    val size = input.get("size")
    require(size is Number && size.toDouble().isFinite() && size.toDouble() == size.toLong().toDouble() &&
        size.toLong() in 0..MAX_FILE.toLong()) { "Invalid shelf item size." }
    val pinned = input.get("pinned")
    require(pinned is Boolean) { "Invalid shelf item." }
    val result = JSONObject().put("id", id.lowercase()).put("kind", kind).put("title", string("title", 4096))
        .put("source", string("source", 1024)).put("createdAt", createdAt.toDouble()).put("pinned", pinned).put("size", size.toInt())
    if (kind == "file") {
        val filename = if (input.isNull("filename")) "Files" else string("filename", 4096)
        val leaf = filename.replace('\\', '/').substringAfterLast('/').ifEmpty { "Files" }
        val end = leaf.offsetByCodePoints(0, minOf(160, leaf.codePointCount(0, leaf.length)))
        result.put("filename", leaf.substring(0, end))
        val mime = if (input.isNull("mime")) "application/octet-stream" else string("mime", 256)
        require(mime.none { it.isISOControl() }) { "Invalid file type information." }
        result.put("mime", mime)
    } else {
        val text = string("text", 64 * 1024)
        require(text.isNotEmpty()) { "Invalid shelf text." }
        if (kind == "link") require(URIHelper.web(text)) { "Links must use http or https." }
        result.put("text", text).put("size", text.toByteArray(Charsets.UTF_8).size)
    }
    return result
}

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
        if (!index.baseFile.exists() && !File(index.baseFile.path + ".bak").exists()) return emptyList()
        val seen = mutableSetOf<String>()
        val loaded = mutableListOf<ShelfEntry>()
        JsonReader(java.io.InputStreamReader(IndexInputStream(index.openRead()), Charsets.UTF_8)).use { reader ->
            reader.beginArray()
            while (reader.hasNext()) {
                require(loaded.size < MAX_SHELF_ITEMS) { "The shelf can hold up to 500 items." }
                reader.beginObject()
                var item: JSONObject? = null
                var pending = false
                val rowFields = mutableSetOf<String>()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(rowFields.add(name)) { "Duplicate shelf field." }
                    when (name) {
                        "item" -> item = readIndexItem(reader)
                        "pending" -> pending = reader.nextBoolean()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                val validated = validatedShelfItem(checkNotNull(item) { "Missing shelf item." })
                require(seen.add(validated.getString("id"))) { "Duplicate shelf item." }
                loaded.add(ShelfEntry(validated, pending))
            }
            reader.endArray()
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Invalid shelf index." }
        }
        return loaded
    }
    private fun readIndexItem(reader: JsonReader): JSONObject {
        val item = JSONObject()
        val known = setOf("id", "kind", "title", "text", "filename", "mime", "size", "createdAt", "source", "pinned")
        val seen = mutableSetOf<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            require(seen.add(name)) { "Duplicate shelf field." }
            if (name !in known) { reader.skipValue(); continue }
            val value: Any = when (reader.peek()) {
                JsonToken.STRING -> reader.nextString()
                JsonToken.NUMBER -> reader.nextDouble()
                JsonToken.BOOLEAN -> reader.nextBoolean()
                JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
                else -> error("Invalid shelf field.")
            }
            item.put(name, value)
        }
        reader.endObject()
        return item
    }
    private class IndexInputStream(input: java.io.InputStream) : java.io.FilterInputStream(input) {
        private var count = 0
        override fun read(): Int = super.read().also { if (it >= 0) { count++; require(count <= MAX_INDEX_BYTES) { "The shelf index exceeds the size limit." } } }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = `in`.read(buffer, offset, minOf(length, MAX_INDEX_BYTES + 1 - count))
            if (read > 0) { count += read; require(count <= MAX_INDEX_BYTES) { "The shelf index exceeds the size limit." } }
            return read
        }
    }

    private fun commit(next: List<ShelfEntry>) {
        require(next.size <= MAX_SHELF_ITEMS) { "The shelf can hold up to 500 items." }
        if (index.baseFile.exists() && next.size == entries.size && next.zip(entries).all { (a, b) -> a.pending == b.pending && a.json.toString() == b.json.toString() }) return
        val output = index.startWrite()
        try {
            val writer = output.bufferedWriter(Charsets.UTF_8)
            writer.write("[")
            next.forEachIndexed { i, entry ->
                if (i > 0) writer.write(",")
                writer.write(JSONObject().put("item", entry.json).put("pending", entry.pending).toString())
            }
            writer.write("]"); writer.flush()
            index.finishWrite(output); entries = next
        } catch (error: Exception) { index.failWrite(output); throw error }
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
        require(entries.size < MAX_SHELF_ITEMS) { "The shelf can hold up to 500 items." }
        return JSONObject().put("id", UUID.randomUUID().toString())
        .put("kind", kind).put("title", title.take(90)).put("size", 0)
        .put("createdAt", System.currentTimeMillis() / 1000.0).put("source", "Android").put("pinned", false)
    }
    fun addText(value: String) {
        require(value.length <= 64 * 1024 && value.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "Text must be 64 KiB or smaller." }
        val text = value.trim(); require(text.isNotEmpty()) { "Enter some text first." }
        require(text.toByteArray().size <= 64 * 1024) { "Text must be 64 KiB or smaller." }
        val uri = runCatching { URIHelper.web(text) }.getOrDefault(false)
        val json = item(if (uri) "link" else "text", text).put("text", text).put("size", text.toByteArray().size)
        commit(listOf(ShelfEntry(json, true)) + entries)
    }
    fun addFile(uri: Uri) {
        require(uri.scheme == "content") { "Choose a file from an Android content provider." }
        var name = "Files"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        }
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytesBounded(MAX_FILE, "Files must be 8 MiB or smaller.") }
            ?: error("Could not read the file.")
        val json = validatedShelfItem(item("file", name).put("filename", name.take(160))
            .put("mime", context.contentResolver.getType(uri) ?: "application/octet-stream").put("size", bytes.size))
        storeFile(json.getString("id"), bytes)
        try { commit(listOf(ShelfEntry(json, true)) + entries) }
        catch (error: Exception) { AtomicFile(file(json.getString("id"))).delete(); throw error }
    }
    fun acknowledge(id: String) { commit(entries.map { if (it.id == id) it.copy(pending = false) else it }) }
    fun merge(remote: JSONArray, client: BridgeClient) = merge(remote) { id ->
        val encoded = client.call("get", id = id).getString("data")
        require(encoded.length <= 4 * ((MAX_FILE + 2) / 3) && encoded.matches(Regex("[A-Za-z0-9+/]*={0,2}"))) { "Invalid file data." }
        android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
    }
    internal fun merge(remote: JSONArray, fetch: (String) -> ByteArray) {
        require(remote.length() <= MAX_SHELF_ITEMS) { "The shelf can hold up to 500 items." }
        val seen = mutableSetOf<String>()
        // Validate the complete snapshot before any file is downloaded or local state changes.
        val incoming = (0 until remote.length()).map { i ->
            ShelfEntry(validatedShelfItem(remote.getJSONObject(i))).also {
                require(seen.add(it.id)) { "Duplicate shelf item." }
            }
        }
        val next = entries.toMutableList()
        require((entries.map { it.id } + incoming.map { it.id }).toSet().size <= MAX_SHELF_ITEMS) { "The shelf can hold up to 500 items." }
        val downloaded = mutableListOf<File>()
        try {
            for (entry in incoming) {
                val existing = next.indexOfFirst { it.id == entry.id }
                // A remote UUID collision must not discard a locally queued item before its put is acknowledged.
                if (existing >= 0 && next[existing].pending) continue
                if (entry.kind == "file") {
                    val stored = file(entry.id)
                    if (!stored.exists()) {
                        val bytes = fetch(entry.id)
                        require(bytes.size == entry.size) { "The downloaded file does not match its declared size." }
                        storeFile(entry.id, bytes)
                        downloaded.add(stored)
                    } else require(stored.length() == entry.size.toLong()) { "The stored file does not match its declared size." }
                }
                if (existing >= 0) next[existing] = entry else next.add(entry)
            }
            commit(next.sortedByDescending { it.json.getDouble("createdAt") })
        } catch (error: Exception) {
            // Failed snapshots must not leave unindexed files that accumulate across retries.
            downloaded.forEach { AtomicFile(it).delete() }
            throw error
        }
    }
}
private object URIHelper {
    fun web(text: String): Boolean {
        val uri = java.net.URI(text)
        return (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            text.none { it.isWhitespace() || it.isISOControl() }
    }
}
