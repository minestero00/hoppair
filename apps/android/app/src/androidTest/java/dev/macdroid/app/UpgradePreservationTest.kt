package dev.macdroid.app

import android.content.Intent
import androidx.core.content.FileProvider
import java.util.UUID
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Run prepare on the old debug APK, replace it without uninstalling, then run restore. */
@RunWith(AndroidJUnit4::class)
class UpgradePreservationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun installedUpgradePreservesPairingShelfAndPayload() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("upgradeFixture") == "true")
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = PairingVault(context)
        val link = "hoppair://10.0.2.2:49273?key=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc"
        check(vault.load() == null || vault.load() == link) { "Do not replace another pairing." }
        val receipt = File(context.filesDir, "upgrade-fixture.json")
        val code = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        val phase = args.getString("phase")
        require(phase == "prepare" || phase == "restore")
        val title = if (phase == "prepare") "Upgrade fixture ${UUID.randomUUID().toString().take(8)}" else JSONObject(receipt.readText()).getString("title")
        val preferences = context.getSharedPreferences("device-identity", 0)
        if (args.getString("phase") == "prepare") {
            check(!receipt.exists()) { "A previous upgrade fixture is still present." }
            val previousPairing = vault.load()
            val previousName = preferences.getString("name", null)
            if (previousPairing == null) vault.save(link)
            check(preferences.edit().putString("name", "Upgrade test phone").commit())
            val repo = ShelfRepository(context)
            repo.addText(title)
            val beforeFiles = repo.entries.map { it.id }.toSet()
            val bytes = ByteArray(8192) { (it % 251).toByte() }
            val directory = File(context.cacheDir, "camera").apply { check(mkdirs() || isDirectory) }
            val source = File.createTempFile("upgrade-fixture-", ".bin", directory)
            try {
                source.writeBytes(bytes)
                repo.addFile(FileProvider.getUriForFile(context, "${context.packageName}.files", source))
            } finally { source.delete() }
            val addedFile = repo.entries.single { it.id !in beforeFiles && it.kind == "file" }
            assertArrayEquals(bytes, repo.file(addedFile.id).readBytes())
            val addedNote = repo.entries.single { it.text == title }
            val payloads = JSONObject()
            for (entry in repo.entries.filter { it.kind == "file" }) payloads.put(entry.id, digest(repo.file(entry.id)))
            receipt.writeText(JSONObject().put("code", code).put("title", title).put("fileId", addedFile.id).put("noteId", addedNote.id)
                .put("hadPairing", previousPairing != null).put("previousName", previousName ?: JSONObject.NULL).put("pairing", digest(File(context.filesDir, "pairing.enc")))
                .put("index", digest(File(context.filesDir, "shelf.json"))).put("payloads", payloads).toString())
        } else {
            val expected = JSONObject(receipt.readText())
            assertTrue("APK must have a newer build number", code > expected.getLong("code"))
        }
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            compose.waitUntil(15_000) {
                runCatching { compose.onNodeWithText(title).assertIsDisplayed(); true }.getOrDefault(false)
            }
        }
        val expected = JSONObject(receipt.readText())
        assertEquals(link, vault.load())
        assertEquals("Upgrade test phone", preferences.getString("name", null))
        assertEquals(expected.getString("pairing"), digest(File(context.filesDir, "pairing.enc")))
        assertEquals(expected.getString("index"), digest(File(context.filesDir, "shelf.json")))
        val repo = ShelfRepository(context)
        val payloads = expected.getJSONObject("payloads")
        for (id in payloads.keys()) assertEquals(payloads.getString(id), digest(repo.file(id)))
        assertTrue(repo.entries.any { it.text == title && it.pending })
        if (phase == "restore") {
            repo.remove(setOf(expected.getString("fileId"), expected.getString("noteId")))
            val edit = preferences.edit()
            if (expected.isNull("previousName")) edit.remove("name") else edit.putString("name", expected.getString("previousName"))
            check(edit.commit())
            if (!expected.getBoolean("hadPairing")) vault.clear()
            assertEquals(if (expected.getBoolean("hadPairing")) link else null, vault.load())
            assertEquals(if (expected.isNull("previousName")) null else expected.getString("previousName"), preferences.getString("name", null))
            assertFalse(repo.entries.any { it.id == expected.getString("fileId") || it.id == expected.getString("noteId") })
            assertFalse(repo.file(expected.getString("fileId")).exists())
            check(receipt.delete())
        }
    }
    private fun digest(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
