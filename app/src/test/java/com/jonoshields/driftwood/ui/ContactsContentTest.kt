package com.jonoshields.driftwood.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.Ed25519Signer
import com.jonoshields.driftwood.core.store.NameResolver
import com.jonoshields.driftwood.ui.contacts.ConfirmedEntry
import com.jonoshields.driftwood.ui.contacts.ContactsContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h3000dp")
class ContactsContentTest {

    @get:Rule val compose = createComposeRule()

    private fun author(seed: Int) = Ed25519Signer(ByteArray(32) { (it + seed).toByte() }).publicKey

    private var backPressed = false
    private var openedContact: AuthorId? = null
    private var addContactCalled = false

    private fun show(entries: List<ConfirmedEntry>) {
        compose.setContent {
            ContactsContent(
                entries = entries,
                onBack = { backPressed = true },
                onOpenContact = { openedContact = it },
                onAddContact = { addContactCalled = true },
            )
        }
    }

    @Test
    fun `no entries says so rather than showing a blank list`() {
        show(emptyList())

        compose.onNodeWithText("Nobody confirmed yet.").assertExists()
    }

    @Test
    fun `tapping an entry opens its contact actions`() {
        val a = author(1)
        val entry = ConfirmedEntry(a, NameResolver.resolve(a, nickname = "Sam", username = null), isListening = false)
        show(listOf(entry))

        compose.onNodeWithText("Sam").assertExists()
        compose.onNodeWithText("Sam").performClick()

        assertEquals(a, openedContact)
    }

    @Test
    fun `every row shows the full hash, even for a nicknamed contact`() {
        val a = author(1)
        val entry = ConfirmedEntry(a, NameResolver.resolve(a, nickname = "Sam", username = null), isListening = false)
        show(listOf(entry))

        compose.onNodeWithText(a.toHex()).assertExists()
    }

    @Test
    fun `the add button calls through`() {
        show(emptyList())

        compose.onNodeWithText("+").performClick()

        assertEquals(true, addContactCalled)
    }

    @Test
    fun `back calls through`() {
        show(emptyList())

        compose.onNodeWithText("Back").performClick()

        assertEquals(true, backPressed)
    }

    @Test
    fun `a listened person is marked, a confirmed-only person is not`() {
        val listened = author(1)
        val confirmedOnly = author(2)
        show(
            listOf(
                ConfirmedEntry(listened, NameResolver.resolve(listened, nickname = "Ears", username = null), isListening = true),
                ConfirmedEntry(confirmedOnly, NameResolver.resolve(confirmedOnly, nickname = "Quiet", username = null), isListening = false),
            ),
        )

        compose.onAllNodesWithText("Listening").assertCountEquals(1)
    }

    @Test
    fun `a listened-but-unconfirmed person still shows up`() {
        // Their posts arrived via gossip relay, never a direct sync or QR — the whole point
        // of merging the two lists rather than only showing confirmed people.
        val a = author(1)
        val unconfirmedButListened = ConfirmedEntry(
            a,
            NameResolver.resolve(a, nickname = null, username = "claimed", confirmed = false),
            isListening = true,
        )
        show(listOf(unconfirmedButListened))

        compose.onNodeWithText("Listening").assertExists()
    }

}
