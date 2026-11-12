package app.altrove.ui

import android.graphics.Typeface
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily

// Indigo fallback for Android 8-11; from Android 12 the colours follow the wallpaper.
private val LightColors = lightColorScheme(
    primary = Color(0xFF4A55B9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDFE0FF),
    onPrimaryContainer = Color(0xFF0A1163),
    secondary = Color(0xFF5C5D72),
    secondaryContainer = Color(0xFFE1E0F9),
    onSecondaryContainer = Color(0xFF191A2C),
    tertiary = Color(0xFF78536B),
    tertiaryContainer = Color(0xFFFFD8EE),
    onTertiaryContainer = Color(0xFF2E1126),
    background = Color(0xFFFCF8FF),
    surface = Color(0xFFFCF8FF),
    onSurface = Color(0xFF1B1B21),
    onSurfaceVariant = Color(0xFF46464F),
    surfaceContainer = Color(0xFFF0ECF6),
    surfaceContainerHigh = Color(0xFFEAE7F0),
    outline = Color(0xFF777680),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBCC3FF),
    onPrimary = Color(0xFF172387),
    primaryContainer = Color(0xFF313C9F),
    onPrimaryContainer = Color(0xFFDFE0FF),
    secondary = Color(0xFFC5C4DD),
    secondaryContainer = Color(0xFF444559),
    onSecondaryContainer = Color(0xFFE1E0F9),
    tertiary = Color(0xFFE8B9D5),
    tertiaryContainer = Color(0xFF5E3C53),
    onTertiaryContainer = Color(0xFFFFD8EE),
    background = Color(0xFF131318),
    surface = Color(0xFF131318),
    onSurface = Color(0xFFE5E1E9),
    onSurfaceVariant = Color(0xFFC7C5D0),
    surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF2A2A30),
    outline = Color(0xFF918F9A),
)

/** Google Sans where the device has it (Pixel); elsewhere this resolves to the system font. */
private val Brand = FontFamily(Typeface.create("google-sans", Typeface.NORMAL))

private val AltroveTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontFamily = Brand),
        headlineMedium = headlineMedium.copy(fontFamily = Brand),
        titleLarge = titleLarge.copy(fontFamily = Brand),
        titleMedium = titleMedium.copy(fontFamily = Brand),
        labelLarge = labelLarge.copy(fontFamily = Brand),
    )
}

@Composable
fun AltroveTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = AltroveTypography, content = content)
}
