package dev.macdroid.app

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.SocketTimeoutException
import java.util.UUID

/** Public fixtures prove failed file transfers cannot starve unrelated notes. */
@RunWith(AndroidJUnit4::class)
class SyncRecoveryTest {
    @Test fun missingRemoteFileDoesNotHideNewNoteAndCanRecoverLater() = repository { context, repo ->
        repo.addText("Local queued note")
        val missing = item("file").put("size", 3)
        val note = item("text")
        val remote = JSONArray().put(missing).put(note)
        val partial = repo.merge(remote) { throw PeerRejected("File not found.") }
        assertEquals(1, partial.unavailableFiles)
        assertNull(partial.interruption)
        assertEquals(2, repo.entries.size)
        assertTrue(repo.entries.any { it.id == note.getString("id") })
        assertTrue(repo.entries.single { it.pending }.text == "Local queued note")
        assertFalse(repo.file(missing.getString("id")).exists())
        assertEquals(2, ShelfRepository(context).entries.size)
        repo.merge(remote) { byteArrayOf(1, 2, 3) }
        assertEquals(3, repo.entries.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), repo.file(missing.getString("id")).readBytes())
    }

    @Test fun interruptedDownloadPreservesNotesWithoutRetryingEveryFileInSamePass() = repository { _, repo ->
        val remote = JSONArray().put(item("file")).put(item("text")).put(item("file"))
        var attempts = 0
        val result = repo.merge(remote) { attempts++; throw SocketTimeoutException("fixture timeout") }
        assertEquals(2, result.unavailableFiles)
        assertTrue(result.interruption is SocketTimeoutException)
        assertEquals(1, attempts)
        assertEquals("text", repo.entries.single().kind)
    }

    @Test fun invalidPayloadStillRollsBackRatherThanBeingTreatedAsOffline() = repository { _, repo ->
        val bad = item("file").put("size", 3)
        val remote = JSONArray().put(item("text")).put(bad)
        assertThrows(IllegalArgumentException::class.java) { repo.merge(remote) { byteArrayOf(1) } }
        assertTrue(repo.entries.isEmpty())
        assertFalse(repo.file(bad.getString("id")).exists())
    }

    @Test fun rejectedQueuedItemDoesNotStarveOtherNotes() = repository { context, repo ->
        repo.addText("Send this note")
        repo.addText("Rejected item")
        val delivered = mutableListOf<String>()
        val result = repo.uploadPending { entry, _ ->
            if (entry.text == "Rejected item") throw PeerRejected("Fixture rejection")
            delivered += checkNotNull(entry.text)
        }
        assertEquals(listOf("Send this note"), delivered)
        assertEquals(1, result.rejectedItems)
        val reopened = ShelfRepository(context)
        assertTrue(reopened.entries.single { it.text == "Rejected item" }.pending)
        assertFalse(reopened.entries.single { it.text == "Send this note" }.pending)
    }

    @Test fun missingQueuedFileDoesNotBlockOtherOutgoingItems() = repository { context, repo ->
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(base.cacheDir, "camera/audit-${UUID.randomUUID()}.bin")
        source.parentFile!!.mkdirs(); source.writeBytes(byteArrayOf(1, 2, 3))
        try {
            repo.addText("Send this note")
            repo.addFile(androidx.core.content.FileProvider.getUriForFile(base, "${base.packageName}.files", source))
            val missing = repo.entries.first()
            assertTrue(repo.file(missing.id).delete())
            val delivered = mutableListOf<String>()
            val result = repo.uploadPending { entry, _ -> delivered += checkNotNull(entry.text) }
            assertEquals(1, result.missingFiles)
            assertEquals(listOf("Send this note"), delivered)
            assertTrue(ShelfRepository(context).entries.single { it.id == missing.id }.pending)
        } finally { source.delete() }
    }

    @Test fun uploadTransportFailureNeverAcknowledgesUnsentItems() = repository { _, repo ->
        repo.addText("Keep queued")
        assertThrows(SocketTimeoutException::class.java) {
            repo.uploadPending { _, _ -> throw SocketTimeoutException("fixture timeout") }
        }
        assertTrue(repo.entries.single().pending)
    }

    @Test fun unavailableRemoteFileDoesNotConsumeLastSlotForANote() = repository { _, repo ->
        repo.merge(JSONArray().also { rows -> repeat(499) { rows.put(item("text")) } }) { error("No file expected") }
        val note = item("text")
        val result = repo.merge(JSONArray().put(item("file")).put(note)) { throw PeerRejected("File not found.") }
        assertEquals(500, repo.entries.size)
        assertTrue(repo.entries.any { it.id == note.getString("id") })
        assertEquals(1, result.unavailableFiles)
        assertEquals(0, result.capacityLimited)
        val full = repo.merge(JSONArray().put(item("text"))) { error("No file expected") }
        assertEquals(1, full.capacityLimited)
        assertEquals(500, repo.entries.size)
    }

    @Test fun damagedReceivedFileIsRepairedWithoutLosingItsSavedEntry() = repository { context, repo ->
        val file = item("file").put("size", 3)
        repo.merge(JSONArray().put(file)) { byteArrayOf(1, 2, 3) }
        repo.file(file.getString("id")).writeBytes(byteArrayOf(1))
        val note = item("text")
        var fetched = 0
        val result = repo.merge(JSONArray().put(file).put(note)) { fetched++; byteArrayOf(1, 2, 3) }
        assertEquals(1, fetched)
        val revision = repo.entries.single { it.id == file.getString("id") }.fileRevision
        assertTrue(revision != 0L)
        repo.merge(JSONArray().put(file)) { error("Valid repair must not be fetched again") }
        assertEquals(revision, repo.entries.single { it.id == file.getString("id") }.fileRevision)
        assertEquals(0, result.unavailableFiles)
        assertEquals(2, ShelfRepository(context).entries.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), repo.file(file.getString("id")).readBytes())
    }

    @Test fun unavailableRepairDoesNotHideIncomingNotesOrDeleteExistingBytes() = repository { context, repo ->
        val file = item("file").put("size", 3)
        repo.merge(JSONArray().put(file)) { byteArrayOf(1, 2, 3) }
        repo.file(file.getString("id")).writeBytes(byteArrayOf(1))
        val result = repo.merge(JSONArray().put(file).put(item("text"))) { throw PeerRejected("File not found.") }
        assertEquals(1, result.unavailableFiles)
        assertEquals(2, ShelfRepository(context).entries.size)
        assertArrayEquals(byteArrayOf(1), repo.file(file.getString("id")).readBytes())
    }

    @Test fun repairedIndexedFileSurvivesLaterBadPayloadWithoutCommittingNewMetadata() = repository { context, repo ->
        val file = item("file").put("size", 3)
        repo.merge(JSONArray().put(file)) { byteArrayOf(1, 2, 3) }
        repo.file(file.getString("id")).writeBytes(byteArrayOf(1))
        val newFile = item("file").put("size", 3)
        val badFile = item("file").put("size", 4)
        val snapshot = JSONArray().put(JSONObject(file.toString()).put("pinned", true)).put(newFile).put(badFile)
        assertThrows(IllegalArgumentException::class.java) { repo.merge(snapshot) { byteArrayOf(1, 2, 3) } }
        assertFalse(repo.entries.single().pinned)
        assertTrue(repo.entries.single().fileRevision != 0L)
        assertFalse(ShelfRepository(context).entries.single().pinned)
        assertArrayEquals(byteArrayOf(1, 2, 3), repo.file(file.getString("id")).readBytes())
        assertFalse(repo.file(newFile.getString("id")).exists())
        assertFalse(repo.file(badFile.getString("id")).exists())
    }

    @Test fun conflictingRemoteSizeCannotOverwriteAnExistingFile() = repository { _, repo ->
        val file = item("file").put("size", 3)
        repo.merge(JSONArray().put(file)) { byteArrayOf(1, 2, 3) }
        var fetched = false
        assertThrows(IllegalArgumentException::class.java) {
            repo.merge(JSONArray().put(JSONObject(file.toString()).put("size", 1))) { fetched = true; byteArrayOf(9) }
        }
        assertFalse(fetched)
        assertEquals(3, repo.entries.single().size)
        assertArrayEquals(byteArrayOf(1, 2, 3), repo.file(file.getString("id")).readBytes())
    }

    @Test fun nonRegularLocalPathDoesNotBlockUnrelatedNotes() = repository { _, repo ->
        val file = item("file").put("size", 3)
        repo.merge(JSONArray().put(file)) { byteArrayOf(1, 2, 3) }
        val path = repo.file(file.getString("id"))
        assertTrue(path.delete()); assertTrue(path.mkdir())
        val result = repo.merge(JSONArray().put(file).put(item("text"))) { error("Do not replace a directory") }
        assertEquals(1, result.unavailableFiles)
        assertEquals(2, repo.entries.size)
        assertTrue(path.isDirectory)
    }

    @Test fun failedRepairWritePreservesOldBytesAndStillCommitsNotes() = repository { context, repo ->
        val file = item("file").put("size", 3)
        repo.merge(JSONArray().put(file)) { byteArrayOf(1, 2, 3) }
        val stored = repo.file(file.getString("id"))
        stored.writeBytes(byteArrayOf(1))
        val blockedWrite = File(stored.path + ".new").apply { mkdir() }
        File(blockedWrite, "occupied").writeText("fixture")
        val result = repo.merge(JSONArray().put(file).put(item("text"))) { byteArrayOf(1, 2, 3) }
        assertEquals(1, result.unavailableFiles)
        assertEquals(2, ShelfRepository(context).entries.size)
        assertArrayEquals(byteArrayOf(1), stored.readBytes())
    }

    @Test fun failedIndexCommitKeepsRepairButRollsBackNewFileAndMetadata() = repository { context, repo ->
        val file = item("file").put("size", 3)
        repo.merge(JSONArray().put(file)) { byteArrayOf(1, 2, 3) }
        val stored = repo.file(file.getString("id"))
        stored.writeBytes(byteArrayOf(1))
        val newFile = item("file").put("size", 3)
        val blockedIndex = File(context.filesDir, "shelf.json.new").apply { mkdir() }
        File(blockedIndex, "occupied").writeText("fixture")
        assertThrows(java.io.IOException::class.java) {
            repo.merge(JSONArray().put(JSONObject(file.toString()).put("pinned", true)).put(newFile)) { byteArrayOf(1, 2, 3) }
        }
        assertFalse(repo.entries.single().pinned)
        assertArrayEquals(byteArrayOf(1, 2, 3), stored.readBytes())
        assertFalse(repo.file(newFile.getString("id")).exists())
        blockedIndex.deleteRecursively()
        assertFalse(ShelfRepository(context).entries.single().pinned)
    }

    private fun item(kind: String) = JSONObject().put("id", UUID.randomUUID().toString()).put("kind", kind)
        .put("title", "Public fixture").put("text", "A new note from Mac").put("filename", "fixture.bin")
        .put("mime", "application/octet-stream").put("size", 0).put("createdAt", 1.0).put("source", "Mac").put("pinned", false)

    private fun repository(block: (ContextWrapper, ShelfRepository) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "sync-recovery-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = root }
        try { block(context, ShelfRepository(context)) } finally { root.deleteRecursively() }
    }
}
