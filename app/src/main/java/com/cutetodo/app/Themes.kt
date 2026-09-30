package com.cutetodo.app

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

data class CuteTheme(
    val name: String, val emoji: String,
    val primary: Color, val bg: Color, val card: Color, val accent: Color, val text: Color,
    val dark: Boolean = false,
    val onPrimary: Color = Color.White   // warna teks/ikon di atas warna primary
)

val Themes = listOf(
    CuteTheme("Sakura Pink", "🌸", Color(0xFFFF6FA5), Color(0xFFFFF0F6), Color(0xFFFFFFFF), Color(0xFFFFD6E7), Color(0xFF5A2A3C)),
    CuteTheme("Lavender Dream", "🦄", Color(0xFF9B7EDE), Color(0xFFF5F0FF), Color(0xFFFFFFFF), Color(0xFFE3D8FA), Color(0xFF3F2F63)),
    CuteTheme("Mint Cookie", "🍃", Color(0xFF3DBE9B), Color(0xFFEFFBF6), Color(0xFFFFFFFF), Color(0xFFCDF2E6), Color(0xFF1F4A3D)),
    CuteTheme("Peach Tea", "🍑", Color(0xFFFF9A6C), Color(0xFFFFF4EC), Color(0xFFFFFFFF), Color(0xFFFFDCC7), Color(0xFF5C3520)),
    CuteTheme("Blueberry Milk", "🫐", Color(0xFF5B8DEF), Color(0xFFEFF5FF), Color(0xFFFFFFFF), Color(0xFFD3E2FF), Color(0xFF24365E)),
    CuteTheme("Sunny Lemon", "🍋", Color(0xFFF2B705), Color(0xFFFFFBE8), Color(0xFFFFFFFF), Color(0xFFFFF0A8), Color(0xFF574800)),
    CuteTheme(
        "Night Panda", "🐼", Color(0xFFB69CFF), Color(0xFF1E1B2E), Color(0xFF2A2640), Color(0xFF3B3559), Color(0xFFF0EBFF),
        dark = true, onPrimary = Color(0xFF2A1B5C)
    )
)

/** Merah untuk tugas terlambat; dibuat lebih terang di tema gelap supaya kontrasnya cukup. */
val CuteTheme.overdue: Color get() = if (dark) Color(0xFFFF8A8F) else Color(0xFFE5484D)

/**
 * Tema gelap HARUS memakai darkColorScheme. Sebelumnya Night Panda memakai lightColorScheme,
 * sehingga komponen yang tidak diberi warna manual (dialog, menu, label input, chip, checkbox,
 * teks sekunder) jatuh ke warna terang bawaan -> teks terang di atas latar terang.
 */
fun CuteTheme.toColorScheme(): ColorScheme =
    if (dark) darkColorScheme(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = accent, onPrimaryContainer = text,
        secondary = primary, onSecondary = onPrimary,
        secondaryContainer = accent, onSecondaryContainer = text,
        background = bg, onBackground = text,
        surface = card, onSurface = text,
        surfaceVariant = Color(0xFF363052), onSurfaceVariant = Color(0xFFCFC8EA),
        surfaceTint = primary,
        inverseSurface = text, inverseOnSurface = bg, inversePrimary = Color(0xFF6B4FD1),
        outline = Color(0xFF9D96BC), outlineVariant = Color(0xFF5B5480),
        surfaceContainerLowest = Color(0xFF17142A),
        surfaceContainerLow = card,
        surfaceContainer = Color(0xFF302B4A),
        surfaceContainerHigh = Color(0xFF363052),     // warna dasar AlertDialog & DropdownMenu
        surfaceContainerHighest = Color(0xFF3D3759)
    ) else lightColorScheme(
        primary = primary, onPrimary = onPrimary, background = bg, surface = card,
        onSurface = text, onBackground = text, secondaryContainer = accent, onSecondaryContainer = text
    )
