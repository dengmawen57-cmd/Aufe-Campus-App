package cn.aufe.campus.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = AufeBlue,
    onPrimary = Color.White,
    primaryContainer = AufeBlueLight,
    onPrimaryContainer = AufeBlueDark,
    secondary = AufeAccent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8E4),
    onSecondaryContainer = Color(0xFF00382F),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = Color(0xFFE1E9F5),
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = Color(0xFFC3C9D4),
    outlineVariant = Color(0xFFDFE3EA),
    error = ColorError,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C8F0),
    onPrimary = Color(0xFF06305F),
    primaryContainer = AufeBlueDark,
    onPrimaryContainer = AufeBlueLight,
    secondary = Color(0xFF8FD8CF),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF005048),
    onSecondaryContainer = Color(0xFFCCE8E4),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = Color(0xFF42474E),
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = Color(0xFF8C9199),
    outlineVariant = Color(0xFF42474E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun CampusAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = CampusTypography,
        content = content
    )
}
