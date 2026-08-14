package com.integrapose.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AppColors = darkColorScheme(
    primary = Color(0xFF33B7FF),
    onPrimary = Color(0xFF001018),
    secondary = Color(0xFFFF8A4A),
    tertiary = Color(0xFFFF4E8F),
    background = Color(0xFF03070B),
    onBackground = Color(0xFFE5EBF5),
    surface = Color(0xFF101723),
    onSurface = Color(0xFFDDE6F3)
)

@Composable
fun IntegraPoseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        typography = Typography,
        content = content
    )
}