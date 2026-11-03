package dev.macdroid.app

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Opt-in UI gate against an isolated emulator and the loopback fixture peer. Never use a real pairing key. */
@RunWith(AndroidJUnit4::class)
class ShelfUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    @Test fun shelfSearchDetailsAndAttachmentsRemainUsableAcrossDisplaySettings() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("uiFixture") == "true")
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Use an Android emulator." }
        val files = listOf("pairing.enc", "shelf.json", "shelf.json.bak", "shelf")
        val backup = File(context.cacheDir, "ui-backup-" + UUID.randomUUID()).apply { mkdirs() }
        val oldScale = Settings.System.getString(context.contentResolver, "font_scale")?.toFloatOrNull() ?: 1f
        val oldAnimation = Settings.Global.getString(context.contentResolver, "animator_duration_scale")?.toFloatOrNull() ?: 1f
        val moved = mutableListOf<String>()
        var backedUp = false
        val oldNight = context.getSystemService(UiModeManager::class.java).nightMode
        val oldIme = Settings.Secure.getString(context.contentResolver, "show_ime_with_hard_keyboard")?.toIntOrNull() ?: 0
        val oldContrast = shell("settings get secure contrast_level").takeUnless { it == "null" || it.isBlank() }
        val screenshots = File(checkNotNull(context.getExternalFilesDir(null)), "ui-review").apply { mkdirs() }
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            files.forEach { name ->
                val source = File(context.filesDir, name)
                if (source.exists()) { check(source.renameTo(File(backup, name))); moved += name }
            }
            backedUp = true
            PairingVault(context).save("hoppair://10.0.2.2:49273?key=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc")
            shell("settings put secure show_ime_with_hard_keyboard 0")
            shell("cmd uimode night no")
            shell("settings put system font_scale 1.0")
            shell("wm size reset")
            shell("wm density reset")
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            screenshot(screenshots, "startup")
            waitText("A few things for tomorrow")
            var launchIntent: Intent? = null
            scenario.onActivity { launchIntent = Intent(it.intent) }
            val originalPairing = PairingVault(context).load()
            val invitePayload = android.util.Base64.encodeToString(checkNotNull(originalPairing).toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
            val incoming = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://hoppair-connect.macdroid-relay.workers.dev/connect/#pair=$invitePayload"))
                .setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            scenario.onActivity { it.startActivity(incoming) }
            waitText("Connect to your Mac?")
            tapText("Cancel")
            assertEquals(originalPairing, PairingVault(context).load())
            scenario.onActivity { it.startActivity(incoming) }
            waitText("Connect to your Mac?")
            val invalid = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://other.example/connect/#pair=invalid"))
                .setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            scenario.onActivity { it.startActivity(invalid) }
            compose.waitUntil(timeoutMillis = 15000) {
                runCatching { compose.onAllNodesWithText("Connect to your Mac?").fetchSemanticsNodes().isEmpty() }.getOrDefault(false)
            }
            assertEquals(originalPairing, PairingVault(context).load())
            scenario.onActivity { it.startActivity(incoming) }
            waitText("Connect to your Mac?")
            compose.onNodeWithText("Connect", substring = false).performClick()
            compose.waitUntil(timeoutMillis = 15000) { PairingVault(context).load() == incoming.dataString }
            compose.onNodeWithText("Connect to your Mac?").assertDoesNotExist()
            waitText("Connected to Studio Mac")
            assertArrayEquals(Pairing.parse(checkNotNull(originalPairing)).key, Pairing.parse(checkNotNull(PairingVault(context).load())).key)
            val replacementLink = "hoppair://10.0.2.2:49273?key=" + android.util.Base64.encodeToString(ByteArray(32) { 8 }, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
            val replacementIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(replacementLink))
                .setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val savedBeforeReplacement = PairingVault(context).load()
            scenario.onActivity { it.startActivity(replacementIntent) }
            waitText("Connect to your Mac?")
            compose.onNodeWithText("Connect", substring = false).performClick()
            waitText("Replace your saved Mac?")
            screenshot(screenshots, "replace-connection")
            tapText("Keep current Mac")
            compose.onNodeWithText("Replace your saved Mac?").assertDoesNotExist()
            assertEquals(savedBeforeReplacement, PairingVault(context).load())
            // ActivityScenario recreates by matching the original launch intent.
            scenario.onActivity { it.intent = checkNotNull(launchIntent) }
            screenshot(screenshots, "shelf-light")
            tapText("Coast study.png")
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("Preview of Coast study.png").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Preview of Coast study.png").assertIsDisplayed()
            screenshot(screenshots, "photo-details")
            tapDescription("Close")
            tapDescription("Mac connection")
            waitText("Your Mac is saved")
            waitText("Update connection with QR")
            val manualRow = compose.onNodeWithText("Use a pairing link")
            manualRow.performScrollTo().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(280.dp)
            // Click the empty trailing portion, not the text label.
            manualRow.performTouchInput { click(Offset(width - 8f, height / 2f)) }
            compose.onNodeWithText("Pairing link from your Mac").assertExists()
            compose.onNodeWithText("Hide pairing link").performScrollTo().performTouchInput { click(Offset(8f, height / 2f)) }
            compose.onNodeWithText("Pairing link from your Mac").assertDoesNotExist()
            screenshot(screenshots, "saved-connection")
            tapDescription("Close")
            tapText("A few things for tomorrow")
            waitText("Keep it simple", contains = true)
            screenshot(screenshots, "note-details")
            tapText("Copy")
            waitText("Copied")
            tapDescription("Close")
            tapDescription("Search your shelf")
            screenshot(screenshots, "search-open")
            compose.onNode(hasSetTextAction() and isFocused()).assertIsDisplayed().performTextInput("no-matching-fixture")
            compose.onNode(hasSetTextAction() and isFocused()).assertTextContains("no-matching-fixture")
            screenshot(screenshots, "search-filter")
            waitText("Nothing here yet")
            assertFalse(hasText("All your items have been sent."))
            shell("settings put secure show_ime_with_hard_keyboard 1")
            compose.onNode(hasSetTextAction() and isFocused()).performClick()
            Thread.sleep(600)
            compose.onNode(hasSetTextAction() and isFocused()).assertTextContains("no-matching-fixture")
            screenshot(screenshots, "search-keyboard")
            tapDescription("Close search")
            tapDescription("Add a file or photo")
            waitText("Choose files")
            waitText("Take a photo")
            screenshot(screenshots, "attachments")
            tapDescription("Close")
            shell("cmd uimode night yes")
            Thread.sleep(800)
            waitText("Your shelf")
            screenshot(screenshots, "shelf-dark")
            if (Build.VERSION.SDK_INT >= 34) {
                shell("settings put secure contrast_level 1.0")
                compose.waitUntil(timeoutMillis = 5000) { context.getSystemService(UiModeManager::class.java).contrast > 0f }
                screenshot(screenshots, "shelf-high-contrast")
                shell(if (oldContrast == null) "settings delete secure contrast_level" else "settings put secure contrast_level $oldContrast")
            }
            shell("settings put system font_scale 2.0")
            shell("wm density 460")
            shell("settings put global animator_duration_scale 0")
            scenario.recreate()
            waitDisplay(scenario, 2f, 460)
            waitText("Your shelf")
            compose.onNodeWithContentDescription("Add a file or photo").assertIsDisplayed()
            screenshot(screenshots, "shelf-large-text")
            shell("settings put system font_scale 1.0")
            shell("wm density reset")
            shell("wm size 2400x1080")
            scenario.recreate()
            waitDisplay(scenario, 1f, orientation = Configuration.ORIENTATION_LANDSCAPE)
            waitText("Your shelf")
            screenshot(screenshots, "shelf-landscape")
            shell("wm size 1600x2560")
            shell("wm density 320")
            scenario.recreate()
            waitDisplay(scenario, 1f, 320, Configuration.ORIENTATION_PORTRAIT)
            waitText("Your shelf")
            screenshot(screenshots, "shelf-tablet")
            scenario.close()
            scenario = null
            PairingVault(context).clear()
            listOf("shelf.json", "shelf.json.bak", "shelf").forEach { File(context.filesDir, it).deleteRecursively() }
            shell("wm size reset")
            shell("wm density reset")
            shell("cmd uimode night no")
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitText("Your Mac.")
            waitText("Scan QR to connect")
            screenshot(screenshots, "welcome")
            tapText("Use a pairing link")
            waitText("Make the connection")
            screenshot(screenshots, "connection")
        } finally {
            scenario?.let { active -> runCatching { active.close() }.onFailure { println("Scenario cleanup: " + it.message) } }
            shell("wm size reset")
            shell("wm density reset")
            shell("settings put system font_scale " + oldScale)
            shell("settings put global animator_duration_scale " + oldAnimation)
            shell("settings put secure show_ime_with_hard_keyboard " + oldIme)
            shell(if (oldContrast == null) "settings delete secure contrast_level" else "settings put secure contrast_level $oldContrast")
            shell("cmd uimode night " + when (oldNight) { UiModeManager.MODE_NIGHT_YES -> "yes"; UiModeManager.MODE_NIGHT_NO -> "no"; else -> "auto" })
            Thread.sleep(300)
            if (backedUp) files.forEach { File(context.filesDir, it).deleteRecursively() }
            moved.forEach { name ->
                val original = File(backup, name)
                if (original.exists()) check(original.renameTo(File(context.filesDir, name)))
            }
            backup.delete()
        }
    }

    private fun shell(command: String): String = automation.executeShellCommand(command).use { descriptor ->
        java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8).trim() }
    }
    private fun waitDisplay(scenario: ActivityScenario<MainActivity>, fontScale: Float, density: Int? = null, orientation: Int? = null) {
        compose.waitUntil(timeoutMillis = 20000) {
            var matches = false
            scenario.onActivity { activity ->
                val config = activity.resources.configuration
                matches = kotlin.math.abs(config.fontScale - fontScale) < 0.01f &&
                    (density == null || config.densityDpi == density) && (orientation == null || config.orientation == orientation)
            }
            matches
        }
        compose.waitForIdle()
    }
    private fun recoverSystemUi() {
        val root = automation.rootInActiveWindow ?: return
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            nodes += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(root)
        if (nodes.any { it.text?.toString()?.let { text -> text.contains("System UI") && text.contains("responding") } == true }) {
            var button = nodes.firstOrNull { it.text?.toString() == "Close app" }
            while (button != null && !button.isClickable) button = button.parent
            println("Recovering the emulator's System UI.")
            button?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }
    private fun waitText(text: String, contains: Boolean = true) {
        recoverSystemUi()
        try {
            compose.waitUntil(timeoutMillis = 15000) {
                try {
                    compose.onAllNodesWithText(text, substring = contains, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                } catch (_: IllegalStateException) { false } // A dialog/configuration transition may briefly have no root.
            }
        } catch (error: Throwable) {
            File(checkNotNull(context.getExternalFilesDir(null)), "ui-review/semantics-failure.txt")
                .writeText(runCatching { compose.onRoot(useUnmergedTree = true).printToString() }.getOrElse { "No Compose root while waiting for: $text\n" + it.message })
            runCatching { automation.takeScreenshot()?.let { image ->
                File(checkNotNull(context.getExternalFilesDir(null)), "ui-review/failure.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            } }
            throw error
        }
        compose.onAllNodesWithText(text, substring = contains, useUnmergedTree = true).onFirst().assertIsDisplayed()
    }
    private fun hasText(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun tapText(text: String) = compose.onAllNodesWithText(text, substring = true).onFirst().performClick()
    private fun tapDescription(text: String) = compose.onNodeWithContentDescription(text, useUnmergedTree = true).performClick()
    private fun screenshot(directory: File, name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(250)
        var image: Bitmap? = null
        // A dismissed dialog may briefly remove the root before the activity resumes.
        compose.waitUntil(timeoutMillis = 15000) {
            try {
                val roots = compose.onAllNodes(isRoot())
                val count = roots.fetchSemanticsNodes().size
                if (count > 0) image = if (count == 1) roots.onFirst().captureToImage().asAndroidBitmap()
                    else checkNotNull(automation.takeScreenshot())
                image != null
            } catch (_: IllegalStateException) { false }
        }
        val bitmap = checkNotNull(image)
        File(directory, name + ".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
