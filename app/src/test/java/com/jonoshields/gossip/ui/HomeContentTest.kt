package com.jonoshields.gossip.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.model.MessageId
import com.jonoshields.gossip.core.store.NameResolver
import com.jonoshields.gossip.ui.home.HomeContent
import com.jonoshields.gossip.ui.home.HomeUiState
import com.jonoshields.gossip.ui.home.ThreadSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// A tall viewport, per ThreadContentTest/SyncContentTest: the tab row sits above the list,
// and a short default viewport leaves later items — and their click targets — positioned
// outside the root's laid-out bounds.
@Config(sdk = [36], qualifiers = "w400dp-h3000dp")
class HomeContentTest {

    @get:Rule val compose = createComposeRule()

    private var openedThread: MessageId? = null

    private fun author(seed: Int) = AuthorId.of(ByteArray(32) { seed.toByte() })

    private fun summary(
        seed: Int,
        rootText: String?,
        rootAuthor: AuthorId? = author(seed),
        latestListenedAuthor: AuthorId? = null,
        latestListenedText: String? = null,
    ) = ThreadSummary(
        rootId = MessageId.of(ByteArray(32) { seed.toByte() }),
        rootAuthor = rootAuthor,
        rootText = rootText,
        latestListenedAuthor = latestListenedAuthor,
        latestListenedText = latestListenedText,
        messageCount = 1,
        newestTimestamp = seed.toLong(),
    )

    private fun show(state: HomeUiState) {
        compose.setContent {
            HomeContent(
                state = state,
                onOpenThread = { openedThread = it },
                onCompose = {},
                onSettings = {},
                onSync = {},
            )
        }
    }

    @Test
    fun `tab labels carry each tab's thread count`() {
        show(
            HomeUiState.Threads(
                listening = listOf(summary(1, "from someone I follow")),
                gossip = listOf(summary(2, "a"), summary(3, "b")),
            )
        )

        compose.onNodeWithText("Listening (1)").assertExists()
        compose.onNodeWithText("Gossip (2)").assertExists()
    }

    @Test
    fun `the listening tab is shown first, by default`() {
        show(
            HomeUiState.Threads(
                listening = listOf(summary(1, "listening thread")),
                gossip = listOf(summary(2, "gossip thread")),
            )
        )

        compose.onNodeWithText("listening thread").assertExists()
    }

    @Test
    fun `switching tabs shows that tab's threads and not the other`() {
        show(
            HomeUiState.Threads(
                listening = listOf(summary(1, "listening thread")),
                gossip = listOf(summary(2, "gossip thread")),
            )
        )

        compose.onNodeWithText("Gossip (1)").performClick()

        compose.onNodeWithText("gossip thread").assertExists()
        compose.onNodeWithText("listening thread").assertDoesNotExist()
    }

    @Test
    fun `an empty tab says so rather than showing a blank list`() {
        show(HomeUiState.Threads(listening = emptyList(), gossip = emptyList()))

        compose.onNodeWithText("Nobody you listen to has posted yet.").assertExists()
    }

    @Test
    fun `tapping a thread opens it by root id`() {
        val thread = summary(7, "tap me")
        show(HomeUiState.Threads(listening = listOf(thread), gossip = emptyList()))

        compose.onNodeWithText("tap me").performClick()

        assertEquals(thread.rootId, openedThread)
    }

    @Test
    fun `a missing root is explained rather than shown blank`() {
        val thread = summary(1, rootText = null, rootAuthor = null)
        show(HomeUiState.Threads(listening = listOf(thread), gossip = emptyList()))

        compose.onNodeWithText("the start of this conversation isn't carried here").assertExists()
    }

    @Test
    fun `the root's author is shown by its resolved name`() {
        val author = author(1)
        val thread = summary(1, "hello", rootAuthor = author)
        val names = mapOf(author to NameResolver.resolve(author, nickname = "Sam", username = null))
        show(HomeUiState.Threads(listening = listOf(thread), gossip = emptyList(), names = names))

        compose.onNodeWithText("Sam").assertExists()
        compose.onNodeWithText("hello").assertExists()
    }

    @Test
    fun `a listened reply is shown alongside the root, not instead of it`() {
        val root = author(1)
        val replier = author(2)
        val thread = summary(
            seed = 1,
            rootText = "the root",
            rootAuthor = root,
            latestListenedAuthor = replier,
            latestListenedText = "the reply",
        )
        val names = mapOf(replier to NameResolver.resolve(replier, nickname = "Dad", username = null))
        show(HomeUiState.Threads(listening = listOf(thread), gossip = emptyList(), names = names))

        compose.onNodeWithText("the root").assertExists()
        compose.onNodeWithText("Dad").assertExists()
        compose.onNodeWithText("replied: the reply").assertExists()
    }

    @Test
    fun `with no listened reply, only the root is shown`() {
        val thread = summary(1, "just the root", latestListenedAuthor = null)
        show(HomeUiState.Threads(listening = listOf(thread), gossip = emptyList()))

        compose.onNodeWithText("just the root").assertExists()
        compose.onAllNodesWithText("replied:", substring = true).assertCountEquals(0)
    }
}
