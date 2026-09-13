package org.castoff.control.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CastoffBlue = Color(0xFF0B3D5C)
private val CastoffAccent = Color(0xFF4FC3F7)

private val DarkColors = darkColorScheme(
    primary = CastoffAccent,
    secondary = CastoffBlue,
)

private val LightColors = lightColorScheme(
    primary = CastoffBlue,
    secondary = CastoffAccent,
)

@Composable
fun CastoffControlTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
