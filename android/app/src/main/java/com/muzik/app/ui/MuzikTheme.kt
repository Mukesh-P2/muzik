package com.muzik.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MuzikColors = darkColorScheme(
    primary = Color(0xFFA99BFF),
    onPrimary = Color(0xFF110D2B),
    primaryContainer = Color(0xFF30275F),
    onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = Color(0xFF6EDDD0),
    onSecondary = Color(0xFF003733),
    secondaryContainer = Color(0xFF0D4E49),
    onSecondaryContainer = Color(0xFFA9F5EA),
    tertiary = Color(0xFFFFB86B),
    onTertiary = Color(0xFF482000),
    tertiaryContainer = Color(0xFF653300),
    onTertiaryContainer = Color(0xFFFFDCC0),
    background = Color(0xFF090B10),
    onBackground = Color(0xFFF4F1FF),
    surface = Color(0xFF12151D),
    onSurface = Color(0xFFF4F1FF),
    surfaceVariant = Color(0xFF202532),
    onSurfaceVariant = Color(0xFFC2C7D8),
    outline = Color(0xFF8C91A3),
    outlineVariant = Color(0xFF414654),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun MuzikTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MuzikColors, content = content)
}
