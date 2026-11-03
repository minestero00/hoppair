package dev.macdroid.app

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QrScannerUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun privacyAndManualFallbackRemainAccessible() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launchActivityForResult<QrScannerActivity>(Intent(context, QrScannerActivity::class.java)).use { scenario ->
            compose.onNodeWithText("Scan to connect").assertIsDisplayed()
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                compose.waitUntil(20_000) {
                    compose.onAllNodesWithText("Point at your Mac’s QR. Tap to focus or pinch to zoom.").fetchSemanticsNodes().isNotEmpty()
                }
            }
            capture("scanner-portrait.png")
            compose.onNodeWithText("Scanner privacy").performScrollTo().performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Done").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Done").performClick()
            compose.onNodeWithText("Use a pairing link instead").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Use a pairing link instead").performClick()
            assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
            assertTrue(scenario.result.resultData.getBooleanExtra(QrScannerActivity.USE_LINK, false))
            assertNull(scenario.result.resultData.getStringExtra(QrScannerActivity.PAIRING_LINK))
        }
    }
    @Test fun landscapeControlsStayReachable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<QrScannerActivity>(Intent(context, QrScannerActivity::class.java)).use { scenario ->
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            compose.onNodeWithText("Scanner privacy").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Use a pairing link instead").performScrollTo().assertIsDisplayed()
            capture("scanner-landscape.png")
        }
    }
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }
}
