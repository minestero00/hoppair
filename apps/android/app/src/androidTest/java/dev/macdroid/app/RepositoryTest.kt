package dev.macdroid.app

import android.content.Context
import android.content.ContextWrapper
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    @Test fun offlineOutboxPersistsTextAndFileAndRejectsOversizedInputWithoutLosingItems() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "repository-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir(): File = root }
        val camera = File(base.cacheDir, "camera").apply { mkdirs() }
        val source = File(camera, "test-${UUID.randomUUID()}.bin")
        try {
            val original = ByteArray(65537) { (it % 251).toByte() }
            source.writeBytes(original)
            val repo = ShelfRepository(context)
            repo.addText("A note queued while offline")
            repo.addFile(FileProvider.getUriForFile(base, "${base.packageName}.files", source))
            val reopened = ShelfRepository(context)
            assertEquals(2, reopened.entries.size)
            assertTrue(reopened.entries.all { it.pending })
            val file = reopened.entries.first { it.kind == "file" }
            assertArrayEquals(original, reopened.file(file.id).readBytes())
            reopened.acknowledge(file.id)
            assertFalse(ShelfRepository(context).entries.first { it.id == file.id }.pending)
            assertThrows(IllegalArgumentException::class.java) { reopened.addText("€".repeat(23000)) }
            assertEquals(2, ShelfRepository(context).entries.size)
        } finally { source.delete(); root.deleteRecursively() }
    }
}
