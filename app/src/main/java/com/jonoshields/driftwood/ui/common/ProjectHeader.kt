package com.jonoshields.driftwood.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jonoshields.driftwood.R

/** Playwrite US Traditional, bundled locally rather than via Android's Downloadable Fonts API. */
private val PlaywriteFontFamily = FontFamily(Font(R.font.playwrite_us_trad))

/** The mark and the name, centered — shared branding for Settings and first-run. [subtitle], if given, sits directly under the name (Settings' version line; first-run leaves it unset). */
@Composable
fun ProjectHeader(modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(
        modifier.fillMaxWidth().padding(top = 40.dp, bottom = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Cropped tight to the mark, unlike the launcher icon's drawable (which keeps margin for masking).
        Image(
            painter = painterResource(R.drawable.logo_mark),
            contentDescription = null,
            modifier = Modifier.width(220.dp).height(129.dp),
        )
        Text(
            stringResource(R.string.app_name),
            fontFamily = PlaywriteFontFamily,
            style = MaterialTheme.typography.headlineLarge,
        )
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
