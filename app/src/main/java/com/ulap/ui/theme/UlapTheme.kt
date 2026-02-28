package com.ulap.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val primaryLight = Color(0xFF1A6FDE)
private val onPrimaryLight = Color(0xFFFFFFFF)
private val primaryContainerLight = Color(0xFFD8E6FF)
private val onPrimaryContainerLight = Color(0xFF001945)
private val secondaryLight = Color(0xFF545F71)
private val tertiaryLight = Color(0xFF2E7D32)
private val surfaceLight = Color(0xFFF8F9FF)
private val backgroundLight = Color(0xFFF8F9FF)

private val primaryDark = Color(0xFFADC6FF)
private val onPrimaryDark = Color(0xFF002F6C)
private val primaryContainerDark = Color(0xFF004499)
private val onPrimaryContainerDark = Color(0xFFD8E6FF)
private val secondaryDark = Color(0xFFBBC7DB)
private val tertiaryDark = Color(0xFF81C784)
private val surfaceDark = Color(0xFF101418)
private val backgroundDark = Color(0xFF101418)

private val LightColors = lightColorScheme(
    primary = primaryLight,
    onPrimary = onPrimaryLight,
    primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight,
    secondary = secondaryLight,
    tertiary = tertiaryLight,
    surface = surfaceLight,
    background = backgroundLight,
)

private val DarkColors = darkColorScheme(
    primary = primaryDark,
    onPrimary = onPrimaryDark,
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark,
    secondary = secondaryDark,
    tertiary = tertiaryDark,
    surface = surfaceDark,
    background = backgroundDark,
)

@Composable
fun UlapTheme(
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themePreference) {
        ThemePreference.DARK -> true
        ThemePreference.LIGHT -> false
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        typography = UlapTypography,
        content = content,
    )
}
