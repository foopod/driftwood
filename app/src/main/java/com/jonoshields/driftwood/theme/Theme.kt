package com.jonoshields.driftwood.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = DriftBlue,
    onPrimary = DriftOnAccent,
    secondary = DriftMint,
    onSecondary = DriftOnAccent,
    tertiary = DriftMint,
    onTertiary = DriftOnAccent,
    background = DriftBackground,
    onBackground = DriftOnSurface,
    surface = DriftBackground,
    onSurface = DriftOnSurface,
    surfaceVariant = DriftSurfaceVariant,
    onSurfaceVariant = DriftOnSurfaceVariant,
    surfaceDim = DriftSurfaceDim,
    surfaceBright = DriftSurfaceBright,
    surfaceContainerLowest = DriftSurfaceContainerLowest,
    surfaceContainerLow = DriftSurfaceContainerLow,
    surfaceContainer = DriftSurfaceContainer,
    surfaceContainerHigh = DriftSurfaceContainerHigh,
    surfaceContainerHighest = DriftSurfaceContainerHighest,
)

/** driftwood is dark-only — the brand palette assumes a near-black canvas. */
@Composable
fun DriftwoodTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColorScheme, typography = Typography, content = content)
}
