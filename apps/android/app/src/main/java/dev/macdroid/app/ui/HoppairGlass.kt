package dev.macdroid.app.ui

import android.os.Build
import android.app.UiModeManager
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

// Blur only navigation surfaces. Content and text remain crisp; older Android
// versions use an opaque surface rather than an expensive software blur.
@Composable
internal fun Modifier.hoppairGlass(state: HazeState, radius: Dp = 28.dp): Modifier {
    val surface = MaterialTheme.colorScheme.surface
    val increasedContrast = rememberIncreasedContrast()
    val style = HazeStyle(
        backgroundColor = MaterialTheme.colorScheme.background,
        tint = HazeTint(surface.copy(alpha = if (isSystemInDarkTheme()) 0.82f else 0.84f)),
        blurRadius = 18.dp,
        noiseFactor = 0f,
        fallbackTint = HazeTint(surface),
    )
    return clip(RoundedCornerShape(radius)).hazeEffect(state = state, style = style) {
        blurEnabled = Build.VERSION.SDK_INT >= 31 && !increasedContrast
    }
}

@Composable
internal fun Modifier.hoppairCanvas(): Modifier = background(Brush.linearGradient(listOf(
    MaterialTheme.colorScheme.surfaceContainerLow,
    MaterialTheme.colorScheme.background,
    MaterialTheme.colorScheme.surfaceVariant,
)))

@Composable
private fun rememberIncreasedContrast(): Boolean {
    if (Build.VERSION.SDK_INT < 34) return false
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(UiModeManager::class.java) }
    var contrast by remember(manager) { mutableFloatStateOf(manager.contrast) }
    DisposableEffect(manager) {
        val listener = UiModeManager.ContrastChangeListener { contrast = it }
        manager.addContrastChangeListener(context.mainExecutor, listener)
        onDispose { manager.removeContrastChangeListener(listener) }
    }
    return contrast > 0f
}
