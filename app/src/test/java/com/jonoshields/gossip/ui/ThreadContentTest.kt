package com.jonoshields.gossip.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.jonoshields.gossip.core.model.Ed25519Signer
import com.jonoshields.gossip.core.model.MessageFactory
import com.jonoshields.gossip.core.model.MessageId
import com.jonoshields.gossip.core.store.NameResolver
import com.jonoshields.gossip.core.store.ThreadAssembler
import com.jonoshields.gossip.ui.thread.ThreadContent
import com.jonoshields.gossip.ui.thread.ThreadUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// A tall viewport on purpose: these count controls across the whole thread, and a
// LazyColumn only composes what fits, so a short screen would silently under-count and the
// assertions would pass for the wrong reason.
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
    private var nicknameSet: Pair<com.jonoshields.gossip.core.model.AuthorId, String>? = null
    private var listenToggled: com.jonoshields.gossip.core.model.AuthorId? = null
    private var blocked: com.jonoshields.gossip.core.model.AuthorId? = null

    private fun show(state: ThreadUiState, myAuthor: com.jonoshields.gossip.core.model.AuthorId? = null) {
        compose.setContent {
            ThreadContent(
                state = state,
                myAuthor = myAuthor,
                onReply = { r, p -> repliedToRoot = r; repliedToParent = p; parentWasProvided = true },
                onBack = {},
                onToggleStar = {},
                onSetNickname = { author, name -> nicknameSet = author to name },
                onToggleListen = { author -> listenToggled = author },
                onBlock = { author -> blocked = author },
            )
        }
    }

    private fun loaded(
        rootId: MessageId,
        messages: List<com.jonoshields.gossip.core.model.Message>,
        names: Map<com.jonoshields.gossip.core.model.AuthorId, com.jonoshields.gossip.core.store.DisplayName> = emptyMap(),
        listenScope: Set<com.jonoshields.gossip.core.model.AuthorId> = emptySet(),
    ) = ThreadUiState.Loaded(ThreadAssembler.assemble(rootId, messages), starred = false, names = names, listenScope = listenScope)

    /**
     * Counts every control offering to reply, matching on a substring so a differently
     * worded button still counts. Matching exactly would let a second "Reply to the
     * thread" button slip past unnoticed — which is exactly what happened, and was only
     * caught by deliberately re-introducing the bug to see whether this test failed.
     */
    private fun replyControls(): Int =
        compose.onAllNodesWithText("Reply", substring = true).fetchSemanticsNodes().size

    @Test
    fun `a thread with only a root offers exactly one way to reply`() {
        // The bug this pins: a separate "reply to the thread" button did the identical
        // thing as the root's own Reply, because the assembler treats a null parent and a
        // parent naming the root the same way. Two buttons, one meaning.
        val r = root("the only message")
        show(loaded(r.id, listOf(r)))

        assertEquals(1, replyControls())
    }

    @Test
    fun `there is one reply action per message and no more`() {
        // The general invariant, of which the root-only case is the tightest instance.
        val r = root("root")
        val a = reply(r.id, r.id, "first")
        val b = reply(r.id, a.id, "nested")
        show(loaded(r.id, listOf(r, a, b)))

        assertEquals(3, replyControls())
    }

    @Test
    fun `replying to the root names the root as parent`() {
        val r = root("the only message")
        show(loaded(r.id, listOf(r)))

        compose.onNodeWithText("Reply").performClick()

        assertEquals(r.id, repliedToRoot)
        assertEquals(r.id, repliedToParent)
    }

    @Test
    fun `a thread with no root still offers one way to reply, with no parent`() {
        // With no root message there is no card to reply from, so the placeholder carries
        // the reply action — and the reply names no parent, because there is none to name.
        val r = root("pruned away")
        val surviving = reply(r.id, r.id, "what is left")
        show(loaded(r.id, listOf(surviving)))

        // The placeholder standing in for the absent root, plus the surviving reply.
        assertEquals(2, replyControls())

        compose.onAllNodesWithText("Reply", substring = true)[0].performClick()
        assertEquals(r.id, repliedToRoot)
        assertNull("a thread-level reply names no parent", repliedToParent)
    }

    @Test
    fun `a missing root is explained rather than shown as an error`() {
        val r = root("pruned away")
        show(loaded(r.id, listOf(reply(r.id, r.id, "what is left"))))

        compose.onNodeWithText("The start of this conversation isn't carried here.").assertExists()
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
    fun `a starred thread says so`() {
        val r = root("kept")
        val state = ThreadUiState.Loaded(ThreadAssembler.assemble(r.id, listOf(r)), starred = true)
        show(state)

        compose.onNodeWithText("★").assertExists()
        compose.onNodeWithText(
            "Starred — this whole thread is kept, including replies that arrive later.",
        ).assertExists()
    }

    @Test
    fun `an unstarred thread shows a hollow star`() {
        val r = root("not kept")
        show(loaded(r.id, listOf(r)))

        compose.onNodeWithText("☆").assertExists()
    }

    @Test
    fun `tapping another author's name opens the contact actions dialog`() {
        val r = theirRoot("hello")
        show(loaded(r.id, listOf(r)), myAuthor = me)

        compose.onNodeWithText(NameResolver.fingerprint(them)).performClick()

        compose.onNodeWithText("Save nickname").assertExists()
    }

    @Test
    fun `tapping your own name does nothing`() {
        val r = root("hello")
        show(loaded(r.id, listOf(r)), myAuthor = me)

        compose.onNodeWithText(NameResolver.fingerprint(me)).performClick()

        compose.onNodeWithText("Save nickname").assertDoesNotExist()
    }

    @Test
    fun `saving a nickname calls through with the typed name`() {
        val r = theirRoot("hello")
        show(loaded(r.id, listOf(r)), myAuthor = me)
        compose.onNodeWithText(NameResolver.fingerprint(them)).performClick()

        compose.onNodeWithText("Nickname").performTextInput("Sam")
        compose.onNodeWithText("Save nickname").performClick()

        assertEquals(them, nicknameSet?.first)
        assertEquals("Sam", nicknameSet?.second)
    }

    @Test
    fun `toggling listen calls through for the right author`() {
        val r = theirRoot("hello")
        show(loaded(r.id, listOf(r)), myAuthor = me)
        compose.onNodeWithText(NameResolver.fingerprint(them)).performClick()

        compose.onNodeWithText("Not listening").performClick()

        assertEquals(them, listenToggled)
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
                "immediately and from this device only. They are never told.",
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
}
