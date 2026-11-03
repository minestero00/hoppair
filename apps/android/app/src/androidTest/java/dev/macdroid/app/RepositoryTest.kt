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
    @Test fun renamedAndLegacyPairingLinksKeepTheSameDeviceIdentity() {
        val key = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc"
        val suffix = ":49271?key=$key&relay=wss%3A%2F%2Frelay.example.com"
        val legacy = Pairing.parse("macdroid://192.168.1.12$suffix")
        val current = Pairing.parse("hoppair://192.168.1.12$suffix")
        assertEquals(legacy.host, current.host)
        assertEquals(legacy.port, current.port)
        assertArrayEquals(legacy.key, current.key)
        assertEquals(legacy.relay, current.relay)
        val payload = android.util.Base64.encodeToString("hoppair://192.168.1.12$suffix".toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        for (base in listOf("https://hoppair-connect.macdroid-relay.workers.dev/connect/", "https://minestero00.github.io/hoppair/connect/")) {
            val web = Pairing.parse("$base#pair=$payload")
            assertArrayEquals(current.key, web.key)
            assertEquals(current.relay, web.relay)
            assertEquals(current.host, web.host)
        }
        assertThrows(IllegalArgumentException::class.java) { Pairing.parse("https://other.example/connect/#pair=$payload") }
        assertThrows(Exception::class.java) { Pairing.parse("https://hoppair-connect.macdroid-relay.workers.dev/connect/#pair=invalid") }
        assertArrayEquals(current.key, Pairing.parse("hoppair://192.168.1.12:49271?key=" + android.util.Base64.encodeToString(current.key, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP).replace("=", "%3D")).key)
        assertThrows(IllegalArgumentException::class.java) { Pairing.parse("hoppair://user@192.168.1.12$suffix") }
        assertEquals("cd0ab698-24ee-465f-e4d6-ef293b3cd2cd", RelayIdentity.room(current.key))
        assertThrows(IllegalArgumentException::class.java) { Pairing.parse("https://192.168.1.12$suffix") }
    }
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
