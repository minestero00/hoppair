package dev.macdroid.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.macdroid.app.ui.ShelfImages
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ShelfImageTest {
    @Test fun previewBoundsPreserveAspectAndSmallImagesAreNotUpscaled() = runBlocking {
        fixture { root ->
            val entry = photo(root, 1600, 800)
            val thumbnail = checkNotNull(ShelfImages.load(root, entry, 160))
            assertEquals(160, thumbnail.width)
            assertEquals(80, thumbnail.height)
            val detail = checkNotNull(ShelfImages.load(root, entry, 960))
            assertEquals(960, detail.width)
            assertEquals(480, detail.height)
            assertTrue(detail.allocationByteCount <= 960 * 960 * 4)
            val small = checkNotNull(ShelfImages.load(root, photo(root, 32, 16), 960))
            assertEquals(32, small.width)
            assertEquals(16, small.height)
        }
    }

    @Test fun missingCorruptAndChangedFilesNeverReuseStalePreview() = runBlocking {
        fixture { root ->
            val entry = photo(root, 64, 64)
            assertNotNull(ShelfImages.load(root, entry, 160))
            val file = File(root, entry.id)
            file.delete()
            assertNull(ShelfImages.load(root, entry, 160))
            file.writeText("not an image")
            assertNull(ShelfImages.load(root, entry, 160))
            val corrupt = ShelfEntry(JSONObject(entry.json.toString()).put("size", file.length()))
            assertNull(ShelfImages.load(root, corrupt, 160))
            val traversal = ShelfEntry(JSONObject(entry.json.toString()).put("id", "../outside"))
            assertNull(ShelfImages.load(root, traversal, 160))
        }
    }

    private suspend fun fixture(block: suspend (File) -> Unit) {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "preview-test-${UUID.randomUUID()}").apply { mkdirs() }
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun photo(root: File, width: Int, height: Int): ShelfEntry {
        val id = UUID.randomUUID().toString()
        val file = File(root, id)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(75, 100, 125))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return ShelfEntry(JSONObject().put("id", id).put("kind", "file").put("mime", "image/png").put("size", file.length()))
    }
}
