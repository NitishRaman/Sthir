package com.nitish.still.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val AppColorScheme = lightColorScheme(
    primary = BrandTextPrimary, // For buttons and interactive elements
    onPrimary = White, // Text on top of primary color
    background = BrandBackground, // App background
    surface = BrandBackground, // Component backgrounds
    onBackground = BrandTextPrimary, // Text on top of background
    onSurface = BrandTextPrimary, // Text on top of components
)

@Composable
fun StillTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = AppTypography, // Use the custom typography
        content = content
    )
}
