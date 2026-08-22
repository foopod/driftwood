package com.jonoshields.gossip.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.model.Ed25519Signer
import com.jonoshields.gossip.core.store.NameResolver
import com.jonoshields.gossip.ui.listen.ListenEntry
import com.jonoshields.gossip.ui.listen.ListenListContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h3000dp")
class ListenListContentTest {

    @get:Rule val compose = createComposeRule()

    private val someone = Ed25519Signer(ByteArray(32) { it.toByte() }).publicKey

    private var backPressed = false
    private var listenedTo: AuthorId? = null
    private var stoppedListening: AuthorId? = null

    private fun show(entries: List<ListenEntry>) {
        compose.setContent {
            ListenListContent(
                entries = entries,
                onBack = { backPressed = true },
                onListenTo = { listenedTo = it },
                onStopListening = { stoppedListening = it },
            )
        }
    }

    @Test
    fun `no entries says so rather than showing a blank list`() {
        show(emptyList())

        compose.onNodeWithText("Not listening to anyone yet.").assertExists()
    }

    @Test
    fun `an entry shows its display name and can be stopped`() {
        val entry = ListenEntry(someone, NameResolver.resolve(someone, nickname = "Sam", username = null))
        show(listOf(entry))

        compose.onNodeWithText("Sam").assertExists()

        compose.onNodeWithText("Stop").performClick()

        assertEquals(someone, stoppedListening)
    }

    @Test
    fun `listen is disabled until a key is typed`() {
        show(emptyList())

        compose.onNodeWithText("Listen").assertIsNotEnabled()

        compose.onNodeWithTag("listen-key-field").performTextInput("a")

        compose.onNodeWithText("Listen").assertIsEnabled()
    }

    @Test
    fun `a valid key calls onListenTo and clears the field`() {
        show(emptyList())

        compose.onNodeWithTag("listen-key-field").performTextInput(someone.toHex())
        compose.onNodeWithText("Listen").performClick()

        assertEquals(someone, listenedTo)
        // The field reset means Listen is disabled again rather than re-armed for a repeat tap.
        compose.onNodeWithText("Listen").assertIsNotEnabled()
    }

    @Test
    fun `an invalid key shows an error and calls nothing`() {
        show(emptyList())

        compose.onNodeWithTag("listen-key-field").performTextInput("not a real key")
        compose.onNodeWithText("Listen").performClick()

        compose.onNodeWithText("That doesn't look like a valid key").assertExists()
        assertNull(listenedTo)
    }

    @Test
    fun `back calls through`() {
        show(emptyList())

        compose.onNodeWithText("Back").performClick()

        assertEquals(true, backPressed)
    }
}
