package com.example.ui.theme

import androidx.compose.ui.graphics.Color

enum class DashboardSkin(
    val displayName: String,
    val subtitle: String,
) {
    CLINICAL_OBSIDIAN("Clinical Obsidian", "Void slate & diagnostic ice"),
    BIO_CYAN("Bio-Cyan Telemetry", "Deep navy HUD & electric cyan"),
    ICU_AMBER("ICU Amber", "Carbon phosphor & clinical amber"),
}

data class BioSkinPalette(
    val skin: DashboardSkin,
    val background: Color,
    val backgroundGlow: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val borderSubtle: Color,
    val primaryAccent: Color,
    val secondaryAccent: Color,
    val arterialRed: Color,
    val venousBlue: Color,
    val normalColor: Color,
    val cautionColor: Color,
    val criticalColor: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val onAccent: Color,
)

fun paletteForSkin(skin: DashboardSkin): BioSkinPalette = when (skin) {
    DashboardSkin.CLINICAL_OBSIDIAN -> BioSkinPalette(
        skin = skin,
        background = Color(0xFF090C10),
        backgroundGlow = Color(0xFF102030),
        surface = Color(0xFF11161E),
        surfaceElevated = Color(0xFF18202C),
        borderSubtle = Color(0xFF283447),
        primaryAccent = Color(0xFF38BDF8),
        secondaryAccent = Color(0xFF34D399),
        arterialRed = Color(0xFFF43F5E),
        venousBlue = Color(0xFF38BDF8),
        normalColor = Color(0xFF10B981),
        cautionColor = Color(0xFFFBBF24),
        criticalColor = Color(0xFFF43F5E),
        textPrimary = Color(0xFFF1F5F9),
        textSecondary = Color(0xFF94A3B8),
        onAccent = Color(0xFF04131D),
    )

    DashboardSkin.BIO_CYAN -> BioSkinPalette(
        skin = skin,
        background = Color(0xFF050C12),
        backgroundGlow = Color(0xFF082738),
        surface = Color(0xFF0B1824),
        surfaceElevated = Color(0xFF102436),
        borderSubtle = Color(0xFF1A3A54),
        primaryAccent = Color(0xFF00E5FF),
        secondaryAccent = Color(0xFF2DD4BF),
        arterialRed = Color(0xFFFF4D6D),
        venousBlue = Color(0xFF00E5FF),
        normalColor = Color(0xFF2DD4BF),
        cautionColor = Color(0xFFF59E0B),
        criticalColor = Color(0xFFFF3366),
        textPrimary = Color(0xFFE6FAFF),
        textSecondary = Color(0xFF7FA6BD),
        onAccent = Color(0xFF02141A),
    )

    DashboardSkin.ICU_AMBER -> BioSkinPalette(
        skin = skin,
        background = Color(0xFF0B0A08),
        backgroundGlow = Color(0xFF241B0A),
        surface = Color(0xFF15130E),
        surfaceElevated = Color(0xFF1F1B14),
        borderSubtle = Color(0xFF383022),
        primaryAccent = Color(0xFFFFB300),
        secondaryAccent = Color(0xFF4ADE80),
        arterialRed = Color(0xFFEF4444),
        venousBlue = Color(0xFF60A5FA),
        normalColor = Color(0xFF4ADE80),
        cautionColor = Color(0xFFFB923C),
        criticalColor = Color(0xFFEF4444),
        textPrimary = Color(0xFFFEF3C7),
        textSecondary = Color(0xFFA8997A),
        onAccent = Color(0xFF1A1202),
    )
}
