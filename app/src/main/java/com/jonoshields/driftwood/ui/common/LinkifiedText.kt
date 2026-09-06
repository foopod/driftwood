package com.jonoshields.driftwood.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Patterns
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.DpOffset
import kotlinx.coroutines.withTimeout

/**
 * Renders [text] with any web URLs turned into tappable links that open in the browser.
 * Long-pressing a link opens a small "Copy link / Open link" menu; a long-press that lands
 * anywhere other than a link is left unconsumed, so an enclosing gesture (e.g. a card's
 * long-press menu) still sees it.
 */
@Composable
fun LinkifiedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val linkified = remember(text, linkColor) { linkify(text, linkColor) }

    val density = LocalDensity.current
    val context = LocalContext.current

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // Non-null while the long-press menu is open: the press position (for placement) and the target URL.
    var menu by remember { mutableStateOf<Pair<Offset, String>?>(null) }

    Box {
        Text(
            linkified.annotated,
            modifier = modifier.pointerInput(linkified) {
                awaitEachGesture {
                    // The link span's own gesture node consumes the down for its pressed style.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val result = layout ?: return@awaitEachGesture
                    val charOffset = result.getOffsetForPosition(down.position)
                    val span = linkified.links.firstOrNull { charOffset >= it.start && charOffset < it.end }
                        ?: return@awaitEachGesture // not on a link — leave the gesture for an ancestor
                    try {
                        withTimeout(viewConfiguration.longPressTimeoutMillis) {
                            waitForUpOrCancellation()
                        }
                    } catch (_: PointerEventTimeoutCancellationException) {
                        // Held long enough over a link — open the menu and swallow the rest of the
                        // gesture on the Initial pass, before the link span's own tap handler runs,
                        // so releasing doesn't also follow the link.
                        menu = down.position to span.url
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }
            },
            style = style,
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = { layout = it },
        )

        menu?.let { (position, url) ->
            DropdownMenu(
                expanded = true,
                onDismissRequest = { menu = null },
                offset = with(density) { DpOffset(position.x.toDp(), position.y.toDp()) },
            ) {
                DropdownMenuItem(
                    text = { Text("Copy link") },
                    onClick = {
                        menu = null
                        copyToClipboard(context, "Link", url)
                    },
                    modifier = Modifier.testTag("link-context-copy"),
                )
                DropdownMenuItem(
                    text = { Text("Open link") },
                    onClick = {
                        menu = null
                        openLink(context, url)
                    },
                    modifier = Modifier.testTag("link-context-open"),
                )
            }
        }
    }
}

private fun openLink(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

private class Linkified(val annotated: AnnotatedString, val links: List<UrlSpan>)

private class UrlSpan(val start: Int, val end: Int, val url: String)

private fun linkify(text: String, linkColor: Color): Linkified {
    val links = mutableListOf<UrlSpan>()
    val annotated = buildAnnotatedString {
        val matcher = Patterns.WEB_URL.matcher(text)
        var lastIndex = 0
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            append(text.substring(lastIndex, start))
            val raw = text.substring(start, end)
            val url = if (raw.contains("://")) raw else "https://$raw"
            // The visible text is appended verbatim, so span offsets match the raw string 1:1.
            links += UrlSpan(start, end, url)
            withLink(
                LinkAnnotation.Url(
                    url = url,
                    styles = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                ),
            ) {
                append(raw)
            }
            lastIndex = end
        }
        append(text.substring(lastIndex))
    }
    return Linkified(annotated, links)
}
