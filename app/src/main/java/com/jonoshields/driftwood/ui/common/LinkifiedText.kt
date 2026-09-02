package com.jonoshields.driftwood.ui.common

import android.util.Patterns
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

/**
 * Renders [text] with any web URLs turned into tappable links that open in the browser.
 */
@Composable
fun LinkifiedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: androidx.compose.ui.text.style.TextOverflow = androidx.compose.ui.text.style.TextOverflow.Clip,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, linkColor) { linkify(text, linkColor) }
    Text(annotated, modifier = modifier, style = style, maxLines = maxLines, overflow = overflow)
}

private fun linkify(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    val matcher = Patterns.WEB_URL.matcher(text)
    var lastIndex = 0
    while (matcher.find()) {
        val start = matcher.start()
        val end = matcher.end()
        append(text.substring(lastIndex, start))
        val url = text.substring(start, end)
        val normalizedUrl = if (url.contains("://")) url else "https://$url"
        withLink(
            LinkAnnotation.Url(
                url = normalizedUrl,
                styles = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
            ),
        ) {
            append(url)
        }
        lastIndex = end
    }
    append(text.substring(lastIndex))
}
