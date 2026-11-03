package dev.macdroid.app.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import dev.macdroid.app.MAX_FILE
import dev.macdroid.app.ShelfEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/** Local previews with bounded output dimensions and cache; visible images also hold references. */
internal object ShelfImages {
    private val decoding = Mutex()
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    suspend fun load(root: File, entry: ShelfEntry, edge: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (entry.kind != "file" || !entry.json.optString("mime").startsWith("image/")) return@withContext null
        val id = runCatching { UUID.fromString(entry.id).toString() }.getOrNull() ?: return@withContext null
        val file = File(root, id)
        if (!file.isFile || file.length() != entry.size.toLong() || file.length() !in 1..MAX_FILE.toLong()) return@withContext null
        val limit = edge.coerceIn(1, 960)
        val key = "${file.absolutePath}:${file.lastModified()}:${file.length()}:${entry.fileRevision}:$limit"
        decoding.withLock {
            cache.get(key)?.let { return@withLock it }
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val width = info.size.width
                    val height = info.size.height
                    require(width > 0 && height > 0 && width.toLong() * height <= 100_000_000) { "Image too large to preview." }
                    val scale = minOf(1.0, limit.toDouble() / maxOf(width, height))
                    decoder.setTargetSize(maxOf(1, (width * scale).roundToInt()), maxOf(1, (height * scale).roundToInt()))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }.also { cache.put(key, it) }
            } catch (_: java.io.IOException) { null }
              catch (_: IllegalArgumentException) { null }
        }
    }
}

@Composable
internal fun ShelfImage(entry: ShelfEntry, detail: Boolean = false, fallback: @Composable () -> Unit) {
    val root = File(LocalContext.current.filesDir, "shelf")
    // Payloads are immutable per UUID; local repair advances a transient revision.
    // A reused composition must never flash the previous item's photo.
    key(root.path, entry.id, entry.size, entry.kind, entry.json.optString("mime"), entry.fileRevision, detail) {
        val bitmap by produceState<Bitmap?>(null) {
            value = ShelfImages.load(root, entry, if (detail) 960 else 160)
        }
        val image = bitmap
        if (image == null) fallback()
        else Image(image.asImageBitmap(), if (detail) "Preview of ${entry.filename}" else null,
            modifier = Modifier.fillMaxSize(), contentScale = if (detail) ContentScale.Fit else ContentScale.Crop)
    }
}
