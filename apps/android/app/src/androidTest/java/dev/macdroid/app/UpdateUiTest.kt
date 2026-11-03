package dev.macdroid.app

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.macdroid.app.ui.HoppairTheme
import dev.macdroid.app.ui.UpdateBanner
import dev.macdroid.app.ui.UpdateSheet
import org.junit.Assert.assertEquals
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
}
