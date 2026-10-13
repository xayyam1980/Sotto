package com.example.watchscribe.mobile.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object Palette {
    val Ground = Color(0xFF0E0F11)
    val Surface = Color(0xFF17191D)
    val Surface2 = Color(0xFF1F2226)
    val Line = Color(0xFF2E3238)
    val Text = Color(0xFFEEF0F2)
    val Muted = Color(0xFFA3A9B0)
    val Soft = Color(0xFFC2C7CD)
    val Accent = Color(0xFF9EC1FF)
    val OnAccent = Color(0xFF0E1A33)
    val AccentContainer = Color(0xFF273347)
    val Amber = Color(0xFFF2C46D)
    val AmberContainer = Color(0xFF3A3020)
    val Live = Color(0xFFFF6A5C)
    val Danger = Color(0xFFFF8A7E)
    val Green = Color(0xFF9ED9B4)
    val PlaceTints = listOf(Color(0xFF9EC1FF), Color(0xFF9ED9B4), Color(0xFFF2C46D), Color(0xFFD7B8FF), Color(0xFFFFB3A6))
}

private val Scheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.OnAccent,
    primaryContainer = Palette.AccentContainer,
    onPrimaryContainer = Color(0xFFC4D9FF),
    secondary = Palette.Amber,
    onSecondary = Color(0xFF2E2104),
    background = Palette.Ground,
    onBackground = Palette.Text,
    surface = Palette.Ground,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Surface,
    onSurfaceVariant = Palette.Muted,
    surfaceContainerLowest = Color(0xFF070809),
    surfaceContainerLow = Color(0xFF141619),
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.Surface2,
    surfaceContainerHighest = Color(0xFF272A2F),
    outline = Palette.Line,
    outlineVariant = Color(0xFF23262B),
    error = Palette.Danger,
    inverseSurface = Palette.Text,
    inverseOnSurface = Palette.Ground,
    inversePrimary = Color(0xFF1F4FA8),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun WatchScribeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, shapes = AppShapes, content = content)
}
