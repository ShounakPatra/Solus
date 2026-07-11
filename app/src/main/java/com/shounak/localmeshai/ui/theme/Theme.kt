package com.shounak.localmeshai.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color(0xFF002B3B),
    primaryContainer = Color(0xFF123E52),
    onPrimaryContainer = Color(0xFFCDEFFF),
    secondary = StatusAmber,
    onSecondary = Color(0xFF3F2600),
    secondaryContainer = Color(0xFF49351F),
    onSecondaryContainer = Color(0xFFFFDDB5),
    tertiary = StatusGreen,
    onTertiary = Color(0xFF00391E),
    tertiaryContainer = Color(0xFF163D2A),
    onTertiaryContainer = Color(0xFFB7F3CE),
    error = StatusRed,
    errorContainer = Color(0xFF4A2027),
    onErrorContainer = Color(0xFFFFDADD),
    background = Slate925,
    onBackground = Slate100,
    surface = Slate850,
    onSurface = Slate100,
    surfaceVariant = Slate750,
    onSurfaceVariant = Slate300,
    outline = Slate500,
    outlineVariant = Color(0xFF2D3B47),
    surfaceBright = Slate650,
    surfaceContainerLowest = Slate950,
    surfaceContainerLow = Slate900,
    surfaceContainer = Slate800,
    surfaceContainerHigh = Slate750,
    surfaceContainerHighest = Slate700,
    surfaceDim = Slate925,
    surfaceTint = AccentBlue
)

private val LightColorScheme = lightColorScheme(
    primary = AccentBlueDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC6EDFF),
    onPrimaryContainer = Color(0xFF003548),
    secondary = Color(0xFFA45B00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDDB5),
    onSecondaryContainer = Color(0xFF3A2200),
    tertiary = Color(0xFF167A46),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB7F3CE),
    onTertiaryContainer = Color(0xFF00391E),
    error = Color(0xFFBA4050),
    errorContainer = Color(0xFFFFDADD),
    onErrorContainer = Color(0xFF40000A),
    background = Color(0xFFF4F7F9),
    onBackground = Color(0xFF111820),
    surface = Color.White,
    onSurface = Color(0xFF111820),
    surfaceVariant = Color(0xFFDDE5EB),
    onSurfaceVariant = Color(0xFF465664),
    outline = Color(0xFF71818E),
    outlineVariant = Color(0xFFC5D0D8),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F9FA),
    surfaceContainer = Color(0xFFEEF2F5),
    surfaceContainerHigh = Color(0xFFE6ECEF),
    surfaceContainerHighest = Color(0xFFDDE5EA),
    surfaceDim = Color(0xFFD8E0E6),
    surfaceTint = AccentBlueDark
)

// A compact, consistent radius scale shared by cards, controls, and dialogs.
private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun LocalMeshAITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        // Only update status/nav bar icon appearance when dark-theme state
        // actually changes.  enableEdgeToEdge() in MainActivity already sets
        // the bar colours to transparent once; repeating that in a SideEffect
        // on every recomposition can cause some GPUs to briefly invalidate the
        // window surface, flashing the underlying windowBackground colour.
        LaunchedEffect(darkTheme) {
            val window = (view.context as Activity).window
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = ExpressiveShapes,
        content = content
    )
}
