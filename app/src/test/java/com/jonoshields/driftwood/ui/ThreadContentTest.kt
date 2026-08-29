package com.jonoshields.driftwood.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.jonoshields.driftwood.core.model.Ed25519Signer
import com.jonoshields.driftwood.core.model.MessageFactory
import com.jonoshields.driftwood.core.model.MessageId
import com.jonoshields.driftwood.core.store.NameResolver
import com.jonoshields.driftwood.core.store.ThreadAssembler
import com.jonoshields.driftwood.ui.thread.ThreadContent
import com.jonoshields.driftwood.ui.thread.ThreadUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Tall viewport: a short screen would let LazyColumn under-compose and silently under-count.
@Config(sdk = [36], qualifiers = "w400dp-h3000dp")
class ThreadContentTest {

    @get:Rule val compose = createComposeRule()

    private val signer = Ed25519Signer(ByteArray(32) { it.toByte() })
    private val me = signer.publicKey
    private val themSigner = Ed25519Signer(ByteArray(32) { (it + 90).toByte() })
    private val them = themSigner.publicKey
    private var clock = 1_000L

    private fun root(text: String) = MessageFactory.createRoot(me, text, clock++, signer)
    private fun reply(root: MessageId, parent: MessageId?, text: String) =
        MessageFactory.createReply(me, root, parent, text, clock++, signer)
    private fun theirRoot(text: String) = MessageFactory.createRoot(them, text, clock++, themSigner)

    private var repliedToRoot: MessageId? = null
    private var repliedToParent: MessageId? = null
    private var parentWasProvided = false
    private var nicknameSet: Pair<com.jonoshields.driftwood.core.model.AuthorId, String>? = null
    private var followToggled: com.jonoshields.driftwood.core.model.AuthorId? = null
    private var blocked: com.jonoshields.driftwood.core.model.AuthorId? = null
    private var unblocked: com.jonoshields.driftwood.core.model.AuthorId? = null
    private var pinToggled = false
    private var settingsOpened = false
    private val deletedMessages = mutableListOf<MessageId>()
    private val deletedThreads = mutableListOf<MessageId>()
    private val restoredMessages = mutableListOf<MessageId>()

    private fun show(state: ThreadUiState, myAuthor: com.jonoshields.driftwood.core.model.AuthorId? = null) {
        compose.setContent {
            ThreadContent(
                state = state,
                myAuthor = myAuthor,
                onReply = { r, p -> repliedToRoot = r; repliedToParent = p; parentWasProvided = true },
                onBack = {},
                onSettings = { settingsOpened = true },
                onTogglePin = { pinToggled = true },
                onSetNickname = { author, name -> nicknameSet = author to name },
                onToggleFollow = { author -> followToggled = author },
                onBlock = { author -> blocked = author },
                onUnblock = { author -> unblocked = author },
                onDeleteMessage = { id -> deletedMessages += id },
                onRestoreMessage = { message -> restoredMessages += message.id },
                onDeleteThread = { root -> deletedThreads += root },
            )
        }
    }

    private fun loaded(
        rootId: MessageId,
        messages: List<com.jonoshields.driftwood.core.model.Message>,
        names: Map<com.jonoshields.driftwood.core.model.AuthorId, com.jonoshields.driftwood.core.store.DisplayName> = emptyMap(),
        followList: Set<com.jonoshields.driftwood.core.model.AuthorId> = emptySet(),
        blockedAuthors: Set<com.jonoshields.driftwood.core.model.AuthorId> = emptySet(),
        pinned: Boolean = false,
        unsentIds: Set<MessageId> = emptySet(),
    ) = ThreadUiState.Loaded(
        ThreadAssembler.assemble(rootId, messages, unsentIds),
        pinned = pinned,
        names = names,
        followList = followList,
        blockedAuthors = blockedAuthors,
    )

    @Test
    fun `there is exactly one reply control, regardless of how many messages are in the thread`() {
        // "Reply" appears exactly once regardless of message count, while no context menu is open.
        val r = root("root")
        val a = reply(r.id, r.id, "first")
        val b = reply(r.id, a.id, "nested")
        show(loaded(r.id, listOf(r, a, b)))

        assertEquals(1, compose.onAllNodesWithText("Reply", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `the floating reply button replies to the root when it is held`() {
        val r = root("the only message")
        show(loaded(r.id, listOf(r)))

        compose.onNodeWithTag("thread-reply-fab").performClick()

        assertEquals(r.id, repliedToRoot)
        assertEquals(r.id, repliedToParent)
    }

    @Test
    fun `the floating reply button replies with no parent when the root is missing`() {
        // With no root message there is no card to reply from — the floating button covers
        // it regardless, and the reply names no parent, because there is none to name.
        val r = root("pruned away")
        val surviving = reply(r.id, r.id, "what is left")
        show(loaded(r.id, listOf(surviving)))

        compose.onNodeWithTag("thread-reply-fab").performClick()

        assertEquals(r.id, repliedToRoot)
        assertNull("a thread-level reply names no parent", repliedToParent)
    }

    @Test
    fun `long-pressing a message reveals a context menu offering reply and profile`() {
        val r = root("hello")
        show(loaded(r.id, listOf(r)))

        compose.onNodeWithText("hello").performTouchInput { longClick() }

        compose.onNodeWithTag("message-context-reply").assertExists()
        compose.onNodeWithTag("message-context-profile").assertExists()
    }

    @Test
    fun `the message context menu also offers pinning the whole thread`() {
        // "Pin from any surface" (plan.md M4): any message's menu, not just the
        // thread's own top bar, can pin it.
        val r = root("hello")
        show(loaded(r.id, listOf(r)))

        compose.onNodeWithText("hello").performTouchInput { longClick() }
        compose.onNodeWithTag("message-context-pin").assertExists()
        compose.onNodeWithTag("message-context-pin").performClick()

        assertEquals(true, pinToggled)
    }

    @Test
    fun `the pin context item offers to unpin an already-pinned thread`() {
        val r = root("hello")
        show(loaded(r.id, listOf(r), pinned = true))

        compose.onNodeWithText("hello").performTouchInput { longClick() }

        compose.onNodeWithText("Unpin thread").assertExists()
    }

    @Test
    fun `choosing reply from a message's context menu replies to that specific message`() {
        val r = root("root")
        val a = reply(r.id, r.id, "first")
        show(loaded(r.id, listOf(r, a)))

        compose.onNodeWithText("first").performTouchInput { longClick() }
        compose.onNodeWithTag("message-context-reply").performClick()

        assertEquals(r.id, repliedToRoot)
        assertEquals(a.id, repliedToParent)
    }

    @Test
    fun `choosing user profile from another author's message opens their contact actions`() {
        val r = theirRoot("hello")
        show(loaded(r.id, listOf(r)), myAuthor = me)

        compose.onNodeWithText("hello").performTouchInput { longClick() }
        compose.onNodeWithTag("message-context-profile").performClick()

        compose.onNodeWithText("Save nickname").assertExists()
    }

    @Test
    fun `choosing user profile on your own message opens settings instead`() {
        val r = root("hello")
        show(loaded(r.id, listOf(r)), myAuthor = me)

        compose.onNodeWithText("hello").performTouchInput { longClick() }
        compose.onNodeWithTag("message-context-profile").performClick()

        compose.onNodeWithText("Save nickname").assertDoesNotExist()
        assertEquals(true, settingsOpened)
    }

    @Test
    fun `a missing root is explained rather than shown as an error`() {
        val r = root("pruned away")
        show(loaded(r.id, listOf(reply(r.id, r.id, "what is left"))))

        compose.onNodeWithText("The start of this thread isn't carried here.").assertExists()
    }

    @Test
    fun `a reply whose parent is missing is marked`() {
        val r = root("root")
        val missing = reply(r.id, r.id, "pruned")
        val orphan = reply(r.id, missing.id, "answering something absent")
        show(loaded(r.id, listOf(r, orphan)))

        compose.onNodeWithText("replying to a message not carried here").assertExists()
    }

    @Test
    fun `a claimed name is shown with its fingerprint attached`() {
        // Colour does the glancing, but it is unreadable for a colour-blind user and is
        // only a few bits anyway, so the fingerprint must be on screen as well.
        val r = root("hello")
        val username = NameResolver.resolve(me, nickname = null, username = "jono")
        show(loaded(r.id, listOf(r), names = mapOf(me to username)))

        compose.onNodeWithText("jono").assertExists()
        compose.onNodeWithText(username.fingerprint).assertExists()
    }

    @Test
    fun `a nickname is shown without a fingerprint`() {
        // The visible difference between "I vouched for this person" and "they say so".
        val r = root("hello")
        val nickname = NameResolver.resolve(me, nickname = "Dad", username = null)
        show(loaded(r.id, listOf(r), names = mapOf(me to nickname)))

        compose.onNodeWithText("Dad").assertExists()
        compose.onNodeWithText(nickname.fingerprint).assertDoesNotExist()
    }

    @Test
    fun `a nickname you assigned is shown alone`() {
        val r = root("hello")
        val nickname = NameResolver.resolve(me, nickname = "Dad", username = "someone else")
        show(loaded(r.id, listOf(r), names = mapOf(me to nickname)))

        compose.onNodeWithText("Dad").assertExists()
    }

    @Test
    fun `an author with no name at all falls back to the fingerprint`() {
        val r = root("hello")
        show(loaded(r.id, listOf(r)))

        compose.onNodeWithText(NameResolver.fingerprint(me)).assertExists()
    }

    @Test
    fun `a pinned thread says so`() {
        val r = root("kept")
        val state = ThreadUiState.Loaded(ThreadAssembler.assemble(r.id, listOf(r)), pinned = true)
        show(state)

        compose.onNodeWithContentDescription("Unpin this thread").assertExists()
        compose.onNodeWithText(
            "Pinned — this whole thread is kept, including replies that arrive later.",
        ).assertExists()
    }

    @Test
    fun `an unpinned thread shows the outline pin toggle`() {
        val r = root("not kept")
        show(loaded(r.id, listOf(r)))

        compose.onNodeWithContentDescription("Pin this thread").assertExists()
    }

    @Test
    fun `tapping another author's name opens the contact actions dialog`() {
        val r = theirRoot("hello")
        show(loaded(r.id, listOf(r)), myAuthor = me)

        compose.onNodeWithText(NameResolver.fingerprint(them)).performClick()

        compose.onNodeWithText("Save nickname").assertExists()
    }

    @Test
    fun `tapping your own name opens settings`() {
        val r = root("hello")
        val myName = NameResolver.resolve(me, nickname = null, username = "myself")
        show(loaded(r.id, listOf(r), names = mapOf(me to myName)), myAuthor = me)

        compose.onNodeWithText("myself").performClick()

        compose.onNodeWithText("Save nickname").assertDoesNotExist()
        assertEquals(true, settingsOpened)
    }

    @Test
    fun `your own message is styled distinctly, with no fingerprint alongside it`() {
        // The one name in the app that never needs proving — it gets the loudest, most
        // different-looking treatment (an inverted chip) instead of a fingerprint.
        val r = root("hello")
        val myName = NameResolver.resolve(me, nickname = null, username = "myself")
        show(loaded(r.id, listOf(r), names = mapOf(me to myName)), myAuthor = me)

        compose.onNodeWithText("myself").assertExists()
        compose.onNodeWithText(myName.fingerprint).assertDoesNotExist()
    }

    @Test
    fun `saving a nickname calls through with the typed name`() {
        val r = theirRoot("hello")
        show(loaded(r.id, listOf(r)), myAuthor = me)
        compose.onNodeWithText(NameResolver.fingerprint(them)).performClick()

        compose.onNodeWithText("Optional nickname").performTextInput("Sam")
        compose.onNodeWithText("Save nickname").performClick()

        assertEquals(them, nicknameSet?.first)
        assertEquals("Sam", nicknameSet?.second)
    }

    @Test
    fun `toggling follow calls through for the right author`() {
        val r = theirRoot("hello")
        show(loaded(r.id, listOf(r)), myAuthor = me)
        compose.onNodeWithText(NameResolver.fingerprint(them)).performClick()

        compose.onNodeWithText("Not following").performClick()

        assertEquals(them, followToggled)
    }

    @Test
    fun `blocking requires a confirm step before calling through`() {
        val r = theirRoot("hello")
        show(loaded(r.id, listOf(r)), myAuthor = me)
        compose.onNodeWithText(NameResolver.fingerprint(them)).performClick()

        // First tap only asks for confirmation — nothing is blocked yet.
        compose.onNodeWithText("Block").performClick()
        assertNull(blocked)
        compose.onNodeWithText(
            "Blocks them: removes their messages and the threads they started, " +
                "immediately and from this device only, and stops listening to them " +
                "too. They are never told.",
        ).assertExists()

        // Second tap actually does it.
        compose.onNodeWithText("Block").performClick()
        assertEquals(them, blocked)
    }

    @Test
    fun `cancelling a block confirmation backs out without blocking`() {
        val r = theirRoot("hello")
        show(loaded(r.id, listOf(r)), myAuthor = me)
        compose.onNodeWithText(NameResolver.fingerprint(them)).performClick()
        compose.onNodeWithText("Block").performClick()

        compose.onNodeWithText("Cancel").performClick()

        assertNull(blocked)
        compose.onNodeWithText("Save nickname").assertExists()
    }

    @Test
    fun `a blocked author cannot be followed`() {
        val r = theirRoot("hello")
        show(loaded(r.id, listOf(r), blockedAuthors = setOf(them)), myAuthor = me)
        compose.onNodeWithText(NameResolver.fingerprint(them)).performClick()

        compose.onNodeWithText("Not following").performClick()

        assertNull("follow is disabled while blocked", followToggled)
    }

    @Test
    fun `a blocked author shows unblock instead of block`() {
        val r = theirRoot("hello")
        show(loaded(r.id, listOf(r), blockedAuthors = setOf(them)), myAuthor = me)
        compose.onNodeWithText(NameResolver.fingerprint(them)).performClick()

        compose.onNodeWithText("Unblock").assertExists()
        compose.onNodeWithText("Block").assertDoesNotExist()
    }

    @Test
    fun `tapping unblock calls through for the right author with no confirmation`() {
        val r = theirRoot("hello")
        show(loaded(r.id, listOf(r), blockedAuthors = setOf(them)), myAuthor = me)
        compose.onNodeWithText(NameResolver.fingerprint(them)).performClick()

        compose.onNodeWithText("Unblock").performClick()

        assertEquals(them, unblocked)
    }

    @Test
    fun `tapping a name swaps the top bar to its own distinct User view`() {
        val r = theirRoot("hello")
        show(loaded(r.id, listOf(r)), myAuthor = me)

        compose.onNodeWithText("Thread").assertExists()
        compose.onNodeWithText(NameResolver.fingerprint(them)).performClick()

        compose.onNodeWithText("User").assertExists()
        compose.onNodeWithText("Thread").assertDoesNotExist()
    }

    @Test
    fun `closing the user view returns to the thread's own title and Back, with no leftover bottom close button`() {
        val r = theirRoot("hello")
        show(loaded(r.id, listOf(r)), myAuthor = me)
        compose.onNodeWithText(NameResolver.fingerprint(them)).performClick()

        // Exactly one "Close" on screen — the top bar's nav icon, not a second one at the
        // bottom of the panel.
        compose.onAllNodesWithText("Close").assertCountEquals(1)
        compose.onNodeWithText("Close").performClick()

        compose.onNodeWithText("Thread").assertExists()
        compose.onNodeWithText("Back").assertExists()
        compose.onNodeWithText("Save nickname").assertDoesNotExist()
    }

    @Test
    fun `the thread's pin toggle is hidden while viewing the user panel`() {
        val r = theirRoot("hello")
        show(loaded(r.id, listOf(r)), myAuthor = me)

        compose.onNodeWithContentDescription("Pin this thread").assertExists()
        compose.onNodeWithText(NameResolver.fingerprint(them)).performClick()

        compose.onNodeWithContentDescription("Pin this thread").assertDoesNotExist()
        compose.onNodeWithContentDescription("Unpin this thread").assertDoesNotExist()
    }

    @Test
    fun `delete is offered for your own unsent message`() {
        val r = root("hello")
        show(loaded(r.id, listOf(r), unsentIds = setOf(r.id)), myAuthor = me)

        compose.onNodeWithText("hello").performTouchInput { longClick() }

        compose.onNodeWithTag("message-context-delete").assertExists()
    }

    @Test
    fun `delete is not offered once a message has synced`() {
        val r = root("hello")
        // No unsentIds — this message has already been served to a peer.
        show(loaded(r.id, listOf(r)), myAuthor = me)

        compose.onNodeWithText("hello").performTouchInput { longClick() }

        compose.onNodeWithTag("message-context-delete").assertDoesNotExist()
    }

    @Test
    fun `delete is never offered for someone else's message, even if it were somehow unsent`() {
        val r = theirRoot("hello")
        show(loaded(r.id, listOf(r), unsentIds = setOf(r.id)), myAuthor = me)

        compose.onNodeWithText("hello").performTouchInput { longClick() }

        compose.onNodeWithTag("message-context-delete").assertDoesNotExist()
    }

    @Test
    fun `deleting a leaf message commits immediately, not delayed on the undo snackbar`() {
        // A commit delayed until the snackbar resolves would silently vanish if the screen is
        // left before then — this is the bug that shape caused, so it must fire right away.
        val r = root("hello")
        show(loaded(r.id, listOf(r), unsentIds = setOf(r.id)), myAuthor = me)
        compose.onNodeWithText("hello").performTouchInput { longClick() }

        compose.onNodeWithTag("message-context-delete").performClick()

        assertEquals(listOf(r.id), deletedMessages)
    }

    @Test
    fun `undo on the delete snackbar restores the message`() {
        val r = root("hello")
        show(loaded(r.id, listOf(r), unsentIds = setOf(r.id)), myAuthor = me)
        compose.onNodeWithText("hello").performTouchInput { longClick() }
        compose.onNodeWithTag("message-context-delete").performClick()
        assertEquals(listOf(r.id), deletedMessages)

        compose.onNodeWithText("Undo").performClick()

        assertEquals(listOf(r.id), restoredMessages)
    }

    @Test
    fun `deleting an unsent root with no replies is a plain leaf delete, no dialog`() {
        val r = root("hello")
        show(loaded(r.id, listOf(r), unsentIds = setOf(r.id)), myAuthor = me)
        compose.onNodeWithText("hello").performTouchInput { longClick() }

        compose.onNodeWithTag("message-context-delete").performClick()

        compose.onNodeWithText("Delete this thread?").assertDoesNotExist()
    }

    @Test
    fun `deleting an unsent root with local replies offers a choice`() {
        val r = root("root")
        val a = reply(r.id, r.id, "first")
        show(loaded(r.id, listOf(r, a), unsentIds = setOf(r.id, a.id)), myAuthor = me)

        compose.onNodeWithText("root").performTouchInput { longClick() }
        compose.onNodeWithTag("message-context-delete").performClick()

        compose.onNodeWithText("Delete this thread?").assertExists()
        compose.onNodeWithText("Delete whole thread (2 messages)").assertExists()
        compose.onNodeWithText("Delete just this message").assertExists()
    }

    @Test
    fun `choosing to delete the whole thread commits immediately, no undo cushion`() {
        val r = root("root")
        val a = reply(r.id, r.id, "first")
        show(loaded(r.id, listOf(r, a), unsentIds = setOf(r.id, a.id)), myAuthor = me)
        compose.onNodeWithText("root").performTouchInput { longClick() }
        compose.onNodeWithTag("message-context-delete").performClick()

        compose.onNodeWithText("Delete whole thread (2 messages)").performClick()

        assertEquals(listOf(r.id), deletedThreads)
    }

    @Test
    fun `choosing just this message from the dialog commits immediately, no undo cushion`() {
        val r = root("root")
        val a = reply(r.id, r.id, "first")
        show(loaded(r.id, listOf(r, a), unsentIds = setOf(r.id, a.id)), myAuthor = me)
        compose.onNodeWithText("root").performTouchInput { longClick() }
        compose.onNodeWithTag("message-context-delete").performClick()

        compose.onNodeWithText("Delete just this message").performClick()

        assertEquals(listOf(r.id), deletedMessages)
        assertEquals(emptyList<MessageId>(), deletedThreads)
    }

    @Test
    fun `a non-root unsent reply with local children still just offers a plain delete, no dialog`() {
        // Only the literal thread root ever gets the whole-thread choice.
        val r = root("root")
        val a = reply(r.id, r.id, "first")
        val b = reply(r.id, a.id, "nested")
        show(loaded(r.id, listOf(r, a, b), unsentIds = setOf(r.id, a.id, b.id)), myAuthor = me)

        compose.onNodeWithText("first").performTouchInput { longClick() }
        compose.onNodeWithTag("message-context-delete").performClick()

        // Same leaf-delete path as the root case: no dialog, immediate commit.
        compose.onNodeWithText("Delete this thread?").assertDoesNotExist()
        assertEquals(listOf(a.id), deletedMessages)
    }
}
