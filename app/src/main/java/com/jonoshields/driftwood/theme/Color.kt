package com.jonoshields.driftwood.theme

import androidx.compose.ui.graphics.Color

val DriftBackground = Color(0xFF090B16)
val DriftBlue = Color(0xFF7488FF)
val DriftMint = Color(0xFF70E5B5)
val DriftOnAccent = Color(0xFF0A0E1A)
val DriftOnSurface = Color(0xFFEDEFF7)
val DriftOnSurfaceVariant = Color(0xFFA8AFC7)

// A navy-tinted ramp at the background's own hue/saturation, so surfaces read as one brand.
val DriftSurfaceDim = Color(0xFF05060D)
val DriftSurfaceContainerLowest = Color(0xFF070810)
val DriftSurfaceContainerLow = Color(0xFF0D0F1F)
val DriftSurfaceContainer = Color(0xFF101326)
val DriftSurfaceContainerHigh = Color(0xFF141831)
val DriftSurfaceContainerHighest = Color(0xFF181E3C)
val DriftSurfaceBright = Color(0xFF1E2448)

// Explicit alias for the few cards that ask for `colorScheme.surfaceVariant` directly.
val DriftSurfaceVariant = DriftSurfaceContainerLow
