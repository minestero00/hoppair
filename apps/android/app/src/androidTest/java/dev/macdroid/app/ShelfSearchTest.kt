package dev.macdroid.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ShelfSearchTest {
    @Test fun matchesTitleAndBodyWithoutChangingPinnedOrFilterOrder() = runBlocking {
        val note = entry("text", "Travel", "Bring the CAMERA")
        val photo = entry("file", "Camera.png", null, pinned = true)
        val link = entry("link", "Reference", "https://example.com/camera", pending = true)
        val entries = listOf(note, photo, link)
        assertEquals(listOf(photo.id, note.id, link.id), searchShelf(entries, "camera", "All").map { it.id })
        assertEquals(listOf(note.id), searchShelf(entries, "camera", "Notes").map { it.id })
        assertEquals(listOf(photo.id), searchShelf(entries, "camera", "Files").map { it.id })
        assertEquals(listOf(link.id), searchShelf(entries, "camera", "Links").map { it.id })
        assertEquals(listOf(link.id), searchShelf(entries, "camera", "Waiting").map { it.id })
        assertEquals(3, searchShelf(entries, "   ", "All").size)
        assertTrue(searchShelf(entries, "absent", "All").isEmpty())
    }

    @Test fun searchesMaximumShelfWithMatchesAtEndOfLongNotes() = runBlocking {
        val body = "a".repeat(64 * 1024 - 10)
        val entries = (0 until MAX_SHELF_ITEMS).map { index ->
            entry("text", "Note $index", body + if (index == MAX_SHELF_ITEMS - 1) "needle" else "other")
        }
        assertEquals(entries.last().id, searchShelf(entries, "NEEDLE", "All").single().id)
        assertEquals(MAX_SHELF_ITEMS, searchShelf(entries, "", "All").size)
    }

    private fun entry(kind: String, title: String, text: String?, pinned: Boolean = false, pending: Boolean = false) = ShelfEntry(
        JSONObject().put("id", UUID.randomUUID().toString()).put("kind", kind).put("title", title)
            .put("text", text ?: JSONObject.NULL).put("pinned", pinned), pending)
}
