package com.example.music_app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = NeonGreen,
    secondary = BrandGreenDark,
    background = AppBlack,
    surface = Panel,
    surfaceVariant = PanelSoft,
    onPrimary = Color(0xFF001A10),
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
    outline = SoftLine,
    error = ErrorRed
)

private val LightColorScheme = lightColorScheme(
    primary = BrandGreen,
    secondary = BrandGreenDark,
    background = Smoke,
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Ink,
    onSurface = Ink,
    outline = SoftLine,
    error = ErrorRed
)

@Composable
fun Music_APPTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
