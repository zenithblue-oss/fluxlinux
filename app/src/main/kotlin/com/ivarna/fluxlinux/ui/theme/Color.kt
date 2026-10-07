package com.ivarna.fluxlinux.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// Brand Colors
val FluxCream = Color(0xFFFDFCF0) // Soft Cream Background/Surface
val FluxCreamPrimary = Color(0xFFE6DABf) // Darker Cream for Primary elements? Or maybe a rich Cream #F0E68C (Khaki) -> let's go with a warm beige/cream
// User said "Primary colors should be cream".
val BrandCream = Color(0xFFF5E6CA) // Warm Cream

val FluxDarkGrey = Color(0xFF1A1C1E) // Material Dark Grey
val FluxDarkSurface = Color(0xFF121212)

// Legacy / Accents (Keeping for now if needed for specific highlights, but likely replaced by Monet)
val FluxAccentCyan = Color(0xFF00E5FF)
val FluxAccentMagenta = Color(0xFFFF00E6)

// Glass Defaults (Deprecated but kept for compilation until refactor complete)
val FluxBackgroundStart = Color(0xFF0F0C29)
val FluxBackgroundMid = Color(0xFF302B63)
val FluxBackgroundEnd = Color(0xFF24243E)
val GlassWhiteHigh = Color(0x26FFFFFF)
val GlassWhiteMedium = Color(0x1AFFFFFF)
val GlassWhiteLow = Color(0x0DFFFFFF)
val GlassBorder = Color(0x4DFFFFFF)
val TextWhite = Color(0xFFFFFFFF)
val TextGrey = Color(0xFFDDDDDD)
val Seed = Color(0xFF00E5FF)

// Contrast-safe on #121212 / #1A1C1E
val FluxBodyMuted = Color(0xFFC8C8C8)          // ~10:1 on #121212
val FluxHairline = Color(0x33FFFFFF)
val FluxCardFill = Color(0xE61A1C1E)           // 90% surface
val FluxDanger = Color(0xFFC62828)             // white on it ~5.6:1
val FluxSwitchCheckedTrack = BrandCream        // #F5E6CA
val FluxSwitchCheckedThumb = FluxDarkGrey
val FluxSwitchUncheckedTrack = Color(0xFF3A3A3A)
val FluxSwitchUncheckedThumb = Color(0xFFE8E8E8)
val FluxSwitchUncheckedBorder = Color(0xFF6B6B6B)

@Composable
fun fluxMutedText(): Color {
    return if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        FluxBodyMuted
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
}

