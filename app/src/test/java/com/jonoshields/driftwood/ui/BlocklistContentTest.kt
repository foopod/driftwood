package com.jonoshields.driftwood.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.Ed25519Signer
import com.jonoshields.driftwood.core.store.NameResolver
import com.jonoshields.driftwood.ui.blocklist.BlockedEntry
import com.jonoshields.driftwood.ui.blocklist.BlocklistContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h3000dp")
class BlocklistContentTest {

    @get:Rule val compose = createComposeRule()

    private val someone = Ed25519Signer(ByteArray(32) { it.toByte() }).publicKey

    private var backPressed = false
    private var openedContact: AuthorId? = null

    private fun show(entries: List<BlockedEntry>) {
        compose.setContent {
            BlocklistContent(
                entries = entries,
                onBack = { backPressed = true },
                onOpenContact = { openedContact = it },
            )
        }
    }

    @Test
    fun `no entries says so rather than showing a blank list`() {
        show(emptyList())

        compose.onNodeWithText("Nobody's blocked.").assertExists()
    }

    @Test
    fun `tapping an entry opens its contact actions`() {
        val entry = BlockedEntry(someone, NameResolver.resolve(someone, nickname = "Sam", username = null))
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
