package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

val LocalBioSkin = staticCompositionLocalOf { paletteForSkin(DashboardSkin.CLINICAL_OBSIDIAN) }

@Composable
fun BioCascadeTheme(
    skin: DashboardSkin = DashboardSkin.CLINICAL_OBSIDIAN,
    content: @Composable () -> Unit,
) {
    val palette = paletteForSkin(skin)
    val colorScheme = darkColorScheme(
        primary = palette.primaryAccent,
        onPrimary = palette.onAccent,
        secondary = palette.secondaryAccent,
        onSecondary = palette.onAccent,
        tertiary = palette.cautionColor,
        error = palette.criticalColor,
        background = palette.background,
        onBackground = palette.textPrimary,
        surface = palette.surface,
        onSurface = palette.textPrimary,
        surfaceVariant = palette.surfaceElevated,
        onSurfaceVariant = palette.textSecondary,
        outline = palette.borderSubtle,
    )

    CompositionLocalProvider(LocalBioSkin provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
