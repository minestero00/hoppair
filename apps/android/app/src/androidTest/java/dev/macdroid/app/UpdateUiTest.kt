package dev.macdroid.app

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.macdroid.app.ui.HoppairTheme
import dev.macdroid.app.ui.UpdateBanner
import dev.macdroid.app.ui.UpdateSheet
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateUiTest {
    @get:Rule val compose = createComposeRule()
    private val release = AndroidRelease("0.1.6", 7, "https://github.com/minestero00/hoppair/releases/download/v0.1.6/Hoppair-0.1.6-android.apk", "a".repeat(64), 100, 28)
    @Test fun availableUpdateWaitsForTapAndProgressCanBeCancelledAndRetried() {
        var downloads = 0
        var cancels = 0
        compose.setContent {
            var state by remember { mutableStateOf(UpdateState(UpdateStage.AVAILABLE, release)) }
            HoppairTheme {
                if (!state.sheet) UpdateBanner(state) { state = state.copy(sheet = true) }
                else UpdateSheet(state, onCheck = {}, onUpdate = {
                    downloads++; state = state.copy(stage = UpdateStage.DOWNLOADING, progress = 42)
                }, onCancel = {
                    cancels++; state = state.copy(stage = UpdateStage.AVAILABLE)
                }, onDismiss = { state = state.copy(sheet = false) })
            }
        }
        compose.onNodeWithText("Download update").assertDoesNotExist()
        assertEquals(0, downloads)
        compose.onNodeWithText("Hoppair 0.1.6 is available").performClick()
        compose.onNodeWithText("Download update").performClick()
        compose.onNodeWithText("Downloading · 42%").assertIsDisplayed()
        compose.onNodeWithText("Cancel update").performClick()
        compose.onNodeWithText("Download update").assertIsDisplayed().performClick()
        assertEquals(2, downloads); assertEquals(1, cancels)
    }
    @Test fun verificationAndRetryStagesStayReadableAndInstallerRequiresExplicitAction() {
        var installs = 0
        var state by mutableStateOf(UpdateState(UpdateStage.READY, release, error = "Allow installation in Android settings.", sheet = true))
        compose.setContent {
            HoppairTheme { UpdateSheet(state, onCheck = {}, onUpdate = { installs++ }, onCancel = {}, onDismiss = {}) }
        }
        compose.onNodeWithText("Verified and ready to install").assertIsDisplayed()
        assertEquals(0, installs)
        compose.onNodeWithText("Install update").performClick()
        assertEquals(1, installs)
        compose.runOnIdle { state = state.copy(stage = UpdateStage.ERROR, error = "The update could not be verified.") }
        compose.onNodeWithText("The update could not be verified.").assertIsDisplayed()
        compose.onNodeWithText("Retry download").assertIsDisplayed()
    }
    @Test fun largeTextUpdateActionsRemainReachable() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("largeTextFixture") == "true")
        var state by mutableStateOf(UpdateState(UpdateStage.AVAILABLE, release, sheet = true))
        var dark by mutableStateOf(false)
        var actions = 0
        compose.setContent {
            val config = LocalConfiguration.current
            check(config.fontScale >= 1.9f && config.screenWidthDp <= 340) { "Run the narrow/large-text emulator fixture." }
            HoppairTheme(dark = dark) {
                UpdateSheet(state, onCheck = {}, onUpdate = { actions++ }, onCancel = { actions++ }, onDismiss = {})
            }
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night; state = state.copy(stage = UpdateStage.AVAILABLE, error = null) }
            compose.onNodeWithText("Download update").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
            capture("available-" + if (night) "dark" else "light")
            compose.runOnIdle { state = state.copy(stage = UpdateStage.DOWNLOADING, progress = 42) }
            compose.onNodeWithText("Cancel update").performScrollTo().assertIsDisplayed().performClick()
            compose.runOnIdle { state = state.copy(stage = UpdateStage.ERROR, error = "The update could not be verified. Please try again later.") }
            compose.onNodeWithText("Retry download").performScrollTo().assertIsDisplayed().performClick()
            capture("retry-" + if (night) "dark" else "light")
            compose.runOnIdle { state = state.copy(stage = UpdateStage.READY, error = null) }
            compose.onNodeWithText("Install update").performScrollTo().assertIsDisplayed().performClick()
            capture("install-" + if (night) "dark" else "light")
        }
        assertEquals(8, actions)
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "update-review")
            check(directory.mkdirs() || directory.isDirectory)
            File(directory, "$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
    }

}
