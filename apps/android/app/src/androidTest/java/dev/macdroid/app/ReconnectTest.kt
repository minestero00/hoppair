package dev.macdroid.app

import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Invoke twice in separate instrumentation processes with the isolated UI fixture peer running. */
@RunWith(AndroidJUnit4::class)
class ReconnectTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun savedMacReconnectsAfterProcessRestart() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("reconnectFixture") == "true")
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Use a disposable Android emulator." }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = PairingVault(context)
        val receipt = File(context.cacheDir, "reconnect-fixture.txt")
        val link = "hoppair://10.0.2.2:49273?key=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc"
        val prepare = args.getString("phase") == "prepare"
        val saved = vault.load()
        check(saved == null || saved == link) { "The disposable fixture must not replace another pairing." }
        if (prepare) {
            check(!receipt.exists()) { "A previous fixture is still present." }
            vault.save(link)
        } else {
            val previous = receipt.readLines()
            assertNotEquals("Restore must run in a different app process", previous[0], Process.myPid().toString())
            assertEquals(link, saved)
            assertEquals(previous[1], fingerprint(File(context.filesDir, "pairing.enc")))
        }
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            compose.waitUntil(timeoutMillis = 15000) {
                runCatching { compose.onNodeWithText("Connected to Studio Mac").assertIsDisplayed(); true }.getOrDefault(false)
            }
            compose.onNodeWithText("Scan QR to connect").assertDoesNotExist()
            compose.onNodeWithText("Connect to your Mac?").assertDoesNotExist()
            assertEquals(link, vault.load())
            if (prepare) receipt.writeText("${Process.myPid()}\n${fingerprint(File(context.filesDir, "pairing.enc"))}\n")
            else receipt.delete()
        }
    }

    private fun fingerprint(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
