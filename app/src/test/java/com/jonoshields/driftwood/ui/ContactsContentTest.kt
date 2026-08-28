package com.jonoshields.driftwood.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.Ed25519Signer
import com.jonoshields.driftwood.core.store.NameResolver
import com.jonoshields.driftwood.ui.contacts.ContactEntry
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

    private fun show(entries: List<ContactEntry>) {
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

        compose.onNodeWithText("No claimed names yet.").assertExists()
    }

    @Test
    fun `tapping an entry opens its contact actions`() {
        val a = author(1)
        val entry = ContactEntry(a, NameResolver.resolve(a, nickname = "Sam", username = null), isFollowing = false)
        show(listOf(entry))

        compose.onNodeWithText("Sam").assertExists()
        compose.onNodeWithText("Sam").performClick()

        assertEquals(a, openedContact)
    }

    @Test
    fun `every row shows the full hash, even for a nicknamed contact`() {
        val a = author(1)
        val entry = ContactEntry(a, NameResolver.resolve(a, nickname = "Sam", username = null), isFollowing = false)
        show(listOf(entry))

        compose.onNodeWithText(a.toHex()).assertExists()
    }

    @Test
    fun `the add button calls through`() {
        show(emptyList())

        compose.onNodeWithContentDescription("Add contact").performClick()

        assertEquals(true, addContactCalled)
    }

    @Test
    fun `back calls through`() {
        show(emptyList())

        compose.onNodeWithText("Back").performClick()

        assertEquals(true, backPressed)
    }

    @Test
    fun `followed and non-followed contacts appear under separate section headers`() {
        val followed = author(1)
        val confirmedOnly = author(2)
        show(
            listOf(
                ContactEntry(followed, NameResolver.resolve(followed, nickname = "Ears", username = null), isFollowing = true),
                ContactEntry(confirmedOnly, NameResolver.resolve(confirmedOnly, nickname = "Quiet", username = null), isFollowing = false),
            ),
        )

        compose.onNodeWithText("Following").assertExists()
        compose.onNodeWithText("Everyone else").assertExists()
    }

    @Test
    fun `only-followed contacts show just the Following section`() {
        val a = author(1)
        show(listOf(ContactEntry(a, NameResolver.resolve(a, nickname = "Ears", username = null), isFollowing = true)))

        compose.onNodeWithText("Following").assertExists()
        compose.onNodeWithText("Everyone else").assertDoesNotExist()
    }

    @Test
    fun `a followed-but-unverified person still shows up`() {
        // Following someone doesn't require ever checking their fingerprint.
        val a = author(1)
        val unverifiedButFollowed = ContactEntry(
            a,
            NameResolver.resolve(a, nickname = null, username = "claimed", confirmed = false),
            isFollowing = true,
        )
        show(listOf(unverifiedButFollowed))

        compose.onNodeWithText("Following").assertExists()
    }

}
