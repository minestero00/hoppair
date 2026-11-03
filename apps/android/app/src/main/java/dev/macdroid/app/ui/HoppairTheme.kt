package dev.macdroid.app.ui

import android.app.Activity
import android.content.ContextWrapper

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// Match the Mac's neutral surfaces and charcoal actions; blue identifies links.
private val LightColors = lightColorScheme(
    primary = Color(0xFF212121), onPrimary = Color.White,
    primaryContainer = Color(0xFFF1F1F3), onPrimaryContainer = Color(0xFF27292D),
    secondary = Color(0xFF2164CE), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAF1FF), onSecondaryContainer = Color(0xFF244A8B),
    tertiary = Color(0xFF60636B), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEEEEF0), onTertiaryContainer = Color(0xFF27292D),
    background = Color(0xFFF7F7F8), onBackground = Color(0xFF1B1C1F),
    surface = Color.White, onSurface = Color(0xFF1B1C1F),
    surfaceVariant = Color(0xFFF0F0F2), onSurfaceVariant = Color(0xFF60636B),
    outline = Color(0xFF797C84), outlineVariant = Color(0xFFE3E3E6),
    surfaceTint = Color(0xFF212121),
    inverseSurface = Color(0xFF303136), inverseOnSurface = Color(0xFFF3F3F5), inversePrimary = Color(0xFF8AB4F8),
    surfaceBright = Color.White, surfaceDim = Color(0xFFE0E0E3),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White,
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFF0F0F2), surfaceContainerHighest = Color(0xFFE7E8EB),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE0E0E0), onPrimary = Color(0xFF1F1F1F),
    primaryContainer = Color(0xFF2C2D30), onPrimaryContainer = Color(0xFFE7E8EA),
    secondary = Color(0xFF8AB4F8), onSecondary = Color(0xFF102B55),
    secondaryContainer = Color(0xFF24334B), onSecondaryContainer = Color(0xFFDBE8FF),
    tertiary = Color(0xFFB7BBC3), onTertiary = Color(0xFF28292D),
    tertiaryContainer = Color(0xFF303136), onTertiaryContainer = Color(0xFFE7E8EA),
    background = Color(0xFF121315), onBackground = Color(0xFFECEDEE),
    surface = Color(0xFF1B1C1F), onSurface = Color(0xFFECEDEE),
    surfaceVariant = Color(0xFF28292D), onSurfaceVariant = Color(0xFFB7BBC3),
    outline = Color(0xFF8A8D96), outlineVariant = Color(0xFF37383E),
    surfaceTint = Color(0xFFE0E0E0),
    inverseSurface = Color(0xFFE6E7EA), inverseOnSurface = Color(0xFF303136), inversePrimary = Color(0xFF2164CE),
    surfaceBright = Color(0xFF35363A), surfaceDim = Color(0xFF121315),
    surfaceContainerLowest = Color(0xFF101113), surfaceContainerLow = Color(0xFF1B1C1F),
    surfaceContainer = Color(0xFF1B1C1F), surfaceContainerHigh = Color(0xFF28292D), surfaceContainerHighest = Color(0xFF33353A),
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 39.sp, letterSpacing = (-0.8).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 25.sp, lineHeight = 32.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 23.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
)

@Composable
internal fun HoppairTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        var context = view.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        (context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = AppTypography, content = content)
}

internal object ShelfColors {
    @Composable fun success() = MaterialTheme.colorScheme.onSurface
    @Composable fun link() = MaterialTheme.colorScheme.secondary
    @Composable fun warning() = if (isSystemInDarkTheme()) Color(0xFFFFCD8B) else Color(0xFF865115)
}
