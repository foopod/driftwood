package com.jonoshields.driftwood.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.jonoshields.driftwood.ui.common.LinkifiedText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
// NATIVE graphics so text actually measures — the link hit-test needs real glyph metrics.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h800dp")
class LinkifiedTextTest {

    @get:Rule val compose = createComposeRule()

    private val opened = mutableListOf<String>()
    private val uriHandler = object : UriHandler {
        override fun openUri(uri: String) { opened += uri }
    }

    private fun setText(text: String) = compose.setContent {
        CompositionLocalProvider(LocalUriHandler provides uriHandler) {
            LinkifiedText(text, modifier = Modifier.padding(4.dp))
        }
    }

    @Test
    fun `long-press on a link opens the copy or open menu without following the link`() {
        setText("https://example.com")

        compose.onNodeWithText("https://example.com").performTouchInput { longClick() }
        compose.waitForIdle()

        compose.onNodeWithTag("link-context-copy").assertExists()
        compose.onNodeWithTag("link-context-open").assertExists()
        assertEquals(emptyList<String>(), opened)
    }

    @Test
    fun `tapping a link still follows it`() {
        setText("see https://example.com ok")

        compose.onNodeWithText("see https://example.com ok").performClick()
        compose.waitForIdle()

        assertEquals(listOf("https://example.com"), opened)
    }

    @Test
    fun `long-press with no link under it does not open the menu`() {
        setText("just some plain words here")

        compose.onNodeWithText("just some plain words here").performTouchInput { longClick() }
        compose.waitForIdle()

        compose.onNodeWithTag("link-context-copy").assertDoesNotExist()
    }
}
