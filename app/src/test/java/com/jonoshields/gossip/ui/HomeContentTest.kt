package com.jonoshields.gossip.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.jonoshields.gossip.core.model.MessageId
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

    private fun summary(seed: Int, opening: String) =
        ThreadSummary(MessageId.of(ByteArray(32) { seed.toByte() }), opening, 1, seed.toLong(), rootHeld = true)

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
}
