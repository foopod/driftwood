package com.jonoshields.gossip.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.model.Ed25519Signer
import com.jonoshields.gossip.core.store.NameResolver
import com.jonoshields.gossip.ui.listen.ListenEntry
import com.jonoshields.gossip.ui.listen.ListenListContent
import org.junit.Assert.assertEquals
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
    private var openedContact: AuthorId? = null

    private fun show(entries: List<ListenEntry>) {
        compose.setContent {
            ListenListContent(
                entries = entries,
                onBack = { backPressed = true },
                onOpenContact = { openedContact = it },
            )
        }
    }

    @Test
    fun `no entries says so rather than showing a blank list`() {
        show(emptyList())

        compose.onNodeWithText("Not listening to anyone yet.").assertExists()
    }

    @Test
    fun `tapping an entry opens its contact actions`() {
        val entry = ListenEntry(someone, NameResolver.resolve(someone, nickname = "Sam", username = null))
        show(listOf(entry))

        compose.onNodeWithText("Sam").assertExists()
        compose.onNodeWithText("Sam").performClick()

        assertEquals(someone, openedContact)
    }

    @Test
    fun `back calls through`() {
        show(emptyList())

        compose.onNodeWithText("Back").performClick()

        assertEquals(true, backPressed)
    }
}
