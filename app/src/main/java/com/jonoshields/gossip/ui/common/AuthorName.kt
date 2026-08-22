package com.jonoshields.gossip.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jonoshields.gossip.core.store.DisplayName

/**
 * How an identity appears anywhere in the app (plan.md §3.1).
 *
 * A **nickname** — a name you assigned after confirming someone's key in person — is plain
 * text with no chip. A **claimed username** sits on a colour derived from the key, with the
 * fingerprint beside it.
 *
 * The two must never look alike: "I vouched for this person" and "this person says so" are
 * completely different claims, and the difference is structural here rather than a matter
 * of shading.
 *
 * Colour does the glancing and the fingerprint does the checking. Colour alone would be
 * unreadable for a colour-blind user, and there are too few distinguishable hues for it to
 * be worth attacking anyway — a few dozen throwaway keypairs would match one.
 */
@Composable
fun AuthorName(name: DisplayName, modifier: Modifier = Modifier) {
    if (name.verified) {
        Text(
            text = name.label.orEmpty(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
        return
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(chipColour(name.hue))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        name.label?.let {
            Text(it, style = MaterialTheme.typography.labelLarge, color = chipTextColour())
        }
        Text(
            text = name.fingerprint,
            style = MaterialTheme.typography.labelSmall,
            color = chipTextColour().copy(alpha = 0.7f),
        )
    }
}

/**
 * Saturation and lightness are fixed and the hue is the only free variable, so every chip
 * keeps the same contrast against its text no matter which key produced it.
 */
@Composable
private fun chipColour(hue: Float): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) Color.hsl(hue, 0.45f, 0.26f) else Color.hsl(hue, 0.55f, 0.85f)
}

@Composable
private fun chipTextColour(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFFF2F2F2) else Color(0xFF14110F)

private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
