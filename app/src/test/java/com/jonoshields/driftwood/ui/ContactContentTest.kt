package com.jonoshields.driftwood.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.jonoshields.driftwood.core.model.Ed25519Signer
import com.jonoshields.driftwood.core.store.NameResolver
import com.jonoshields.driftwood.ui.contact.ContactContent
import com.jonoshields.driftwood.ui.contact.ContactUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pins the screen shell and mutual-exclusion wiring from the contacts-list entry point (contact-actions cases themselves are covered in `ThreadContentTest`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContactContentTest {

    @get:Rule val compose = createComposeRule()

    private val them = Ed25519Signer(ByteArray(32) { it.toByte() }).publicKey

    private var backPressed = false
    private var listenToggled = false
    private var blockCalled = false
    private var unblockCalled = false
    private var nicknameSet: String? = null

    private fun show(state: ContactUiState) {
        compose.setContent {
            ContactContent(
                author = them,
                state = state,
                onBack = { backPressed = true },
                onSetNickname = { nicknameSet = it },
                onToggleListen = { listenToggled = true },
                onBlock = { blockCalled = true },
                onUnblock = { unblockCalled = true },
            )
        }
    }

    private fun loaded(isListening: Boolean = false, isBlocked: Boolean = false) = ContactUiState.Loaded(
        displayName = NameResolver.resolve(them, nickname = null, username = "sam"),
        isListening = isListening,
        isBlocked = isBlocked,
    )

    @Test
    fun `loading shows nothing to interact with`() {
        show(ContactUiState.Loading)

        compose.onNodeWithText("Save nickname").assertDoesNotExist()
    }

    @Test
    fun `the header shows the full untruncated hash, not just the short fingerprint`() {
        show(loaded())

        compose.onNodeWithText(them.toHex()).assertExists()
    }

    @Test
    fun `back calls through`() {
        show(loaded())

        compose.onNodeWithText("Back").performClick()

        assertEquals(true, backPressed)
    }

    @Test
    fun `saving a nickname calls through with the typed name`() {
        show(loaded())

        compose.onNodeWithText("Optional nickname").performTextInput("Sam")
        compose.onNodeWithText("Save nickname").performClick()

        assertEquals("Sam", nicknameSet)
    }

    @Test
    fun `a blocked contact cannot be listened to and offers unblock`() {
        show(loaded(isBlocked = true))

        compose.onNodeWithText("Not listening").performClick()
        assertEquals(false, listenToggled)

        compose.onNodeWithText("Unblock").assertExists()
        compose.onNodeWithText("Block").assertDoesNotExist()
    }

    @Test
    fun `unblocking calls through with no confirmation step`() {
        show(loaded(isBlocked = true))

        compose.onNodeWithText("Unblock").performClick()

        assertEquals(true, unblockCalled)
    }

    @Test
    fun `blocking requires a confirm step before calling through`() {
        show(loaded(isListening = true))

        compose.onNodeWithText("Block").performClick()
        assertEquals(false, blockCalled)

        compose.onNodeWithText("Block").performClick()
        assertEquals(true, blockCalled)
    }
}
