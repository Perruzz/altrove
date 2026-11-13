package app.altrove.ui

import android.graphics.Typeface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * The palette of the app: deep space, with the violet of the icon as the resting accent and
 * a warmer magenta for the moments you are "elsewhere". The screen is dark whatever the
 * system theme says, because space is.
 */
internal object Space {
    val Void = Color(0xFF04040F)
    val Night = Color(0xFF070718)
    val Dusk = Color(0xFF0D0D2E)
    val Panel = Color(0xFF17174A)

    val Violet = Color(0xFF8C7CFF)
    val Lavender = Color(0xFFC6BDFF)
    val Cyan = Color(0xFF72E6F7)
    val Magenta = Color(0xFFFF6FB5)
    val Gold = Color(0xFFFFD58A)
    val Amber = Color(0xFFFFC46B)
    val Starlight = Color(0xFFF3F0FF)
    val Danger = Color(0xFFFF9B9B)

    /** Body text that should step back a little. */
    val Muted = Starlight.copy(alpha = 0.66f)
}

private val SpaceColors = darkColorScheme(
    primary = Space.Violet,
    onPrimary = Color.White,
    primaryContainer = Space.Panel,
    onPrimaryContainer = Space.Starlight,
    secondary = Space.Cyan,
    onSecondary = Space.Night,
    tertiary = Space.Gold,
    onTertiary = Space.Night,
    background = Space.Night,
    onBackground = Space.Starlight,
    surface = Space.Night,
    onSurface = Space.Starlight,
    onSurfaceVariant = Space.Muted,
    surfaceContainer = Space.Dusk,
    surfaceContainerHigh = Space.Panel,
    outline = Color.White.copy(alpha = 0.22f),
    error = Space.Danger,
    onError = Space.Night,
)

/** Google Sans where the device has it (Pixel); elsewhere this resolves to the system font. */
private val Brand = FontFamily(Typeface.create("google-sans", Typeface.NORMAL))

private val AltroveTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontFamily = Brand),
        headlineMedium = headlineMedium.copy(fontFamily = Brand),
        headlineSmall = headlineSmall.copy(fontFamily = Brand),
        titleLarge = titleLarge.copy(fontFamily = Brand),
        titleMedium = titleMedium.copy(fontFamily = Brand),
        labelLarge = labelLarge.copy(fontFamily = Brand),
    )
}

@Composable
fun AltroveTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SpaceColors, typography = AltroveTypography, content = content)
}
