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

private val LightColors = lightColorScheme(
    primary = Color(0xFF2859D9), onPrimary = Color.White,
    primaryContainer = Color(0xFFEAF0FF), onPrimaryContainer = Color(0xFF24469A),
    secondary = Color(0xFF556078), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDF0F6), onSecondaryContainer = Color(0xFF38435B),
    background = Color(0xFFF6F7FA), onBackground = Color(0xFF181C26),
    surface = Color.White, onSurface = Color(0xFF181C26),
    surfaceVariant = Color(0xFFEEF0F5), onSurfaceVariant = Color(0xFF5D6577),
    outline = Color(0xFF737D91), outlineVariant = Color(0xFFE1E5ED),
    surfaceContainer = Color.White, surfaceContainerLow = Color.White,
    surfaceContainerHigh = Color(0xFFEEF0F5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C0FF), onPrimary = Color(0xFF10275B),
    primaryContainer = Color(0xFF253759), onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFFB6C1D9), onSecondary = Color(0xFF243047),
    secondaryContainer = Color(0xFF2B3241), onSecondaryContainer = Color(0xFFDCE3F2),
    background = Color(0xFF111318), onBackground = Color(0xFFECEEF5),
    surface = Color(0xFF1B1E26), onSurface = Color(0xFFECEEF5),
    surfaceVariant = Color(0xFF272C37), onSurfaceVariant = Color(0xFFB2BACB),
    outline = Color(0xFF858FA4), outlineVariant = Color(0xFF353C4B),
    surfaceContainer = Color(0xFF1B1E26), surfaceContainerLow = Color(0xFF1B1E26),
    surfaceContainerHigh = Color(0xFF272C37),
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
    @Composable fun success() = if (isSystemInDarkTheme()) Color(0xFFA0DFBC) else Color(0xFF276749)
    @Composable fun warning() = if (isSystemInDarkTheme()) Color(0xFFFFCD8B) else Color(0xFF865115)
}
