package com.jonoshields.driftwood.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.jonoshields.driftwood.core.store.DisplayName

/** How an identity appears: nickname = primary chip, your own name = secondary chip, unverified = hue chip + fingerprint. */
@Composable
fun AuthorName(name: DisplayName, isMine: Boolean = false, modifier: Modifier = Modifier) {
    if (isMine) {
        // The one name that never needs proving, so it gets the palette's other solid colour.
        Text(
            text = name.label.orEmpty(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondary,
            modifier = modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.secondary)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
        return
    }

    if (name.verified) {
        Text(
            text = name.label.orEmpty(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 8.dp, vertical = 3.dp),
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

/** A louder form for surfaces where you're looking someone up: label above the full, untruncated key hash. */
@Composable
fun AuthorNameExpanded(name: DisplayName, fullHash: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(chipColour(name.hue))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        name.label?.let {
            Text(it, style = MaterialTheme.typography.labelLarge, color = chipTextColour())
        }
        Text(
            text = fullHash,
            style = MaterialTheme.typography.labelSmall,
            color = chipTextColour().copy(alpha = 0.7f),
        )
    }
}

/** Saturation and lightness are fixed, hue is the only free variable, so contrast stays constant. */
private fun chipColour(hue: Float): Color = Color.hsl(hue, 0.45f, 0.26f)

private fun chipTextColour(): Color = Color(0xFFF2F2F2)
