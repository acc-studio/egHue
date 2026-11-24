package app.eghue.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.eghue.data.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF8A5100),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCBE),
    onPrimaryContainer = Color(0xFF2C1600),
    secondary = Color(0xFF725A42),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDCBE),
    onSecondaryContainer = Color(0xFF291806),
    tertiary = Color(0xFF58633A),
    background = Color(0xFFFFF8F4),
    onBackground = Color(0xFF211A14),
    surface = Color(0xFFFFF8F4),
    onSurface = Color(0xFF211A14),
    surfaceVariant = Color(0xFFF2DFD1),
    onSurfaceVariant = Color(0xFF51443A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFCF2EB),
    surfaceContainer = Color(0xFFF6ECE5),
    surfaceContainerHigh = Color(0xFFF0E6DF),
    surfaceContainerHighest = Color(0xFFEBE0D9),
    outline = Color(0xFF837468),
    outlineVariant = Color(0xFFD5C3B5),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB870),
    onPrimary = Color(0xFF4A2800),
    primaryContainer = Color(0xFF693C00),
    onPrimaryContainer = Color(0xFFFFDCBE),
    secondary = Color(0xFFE1C1A4),
    onSecondary = Color(0xFF402C18),
    secondaryContainer = Color(0xFF59422C),
    onSecondaryContainer = Color(0xFFFFDCBE),
    tertiary = Color(0xFFC0CC9A),
    background = Color(0xFF19120C),
    onBackground = Color(0xFFEFE0D6),
    surface = Color(0xFF19120C),
    onSurface = Color(0xFFEFE0D6),
    surfaceVariant = Color(0xFF51443A),
    onSurfaceVariant = Color(0xFFD5C3B5),
    surfaceContainerLowest = Color(0xFF130D07),
    surfaceContainerLow = Color(0xFF211A14),
    surfaceContainer = Color(0xFF261E18),
    surfaceContainerHigh = Color(0xFF302822),
    surfaceContainerHighest = Color(0xFF3C332C),
    outline = Color(0xFF9D8E81),
    outlineVariant = Color(0xFF51443A),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun isAppInDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun EgHueTheme(mode: ThemeMode, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = isAppInDarkTheme(mode)
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
