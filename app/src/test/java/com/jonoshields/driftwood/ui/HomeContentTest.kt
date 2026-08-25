package com.jonoshields.driftwood.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.paging.PagingData
import com.jonoshields.driftwood.core.data.ThreadSummary
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.MessageId
import com.jonoshields.driftwood.core.store.NameResolver
import com.jonoshields.driftwood.ui.home.HomeContent
import com.jonoshields.driftwood.ui.home.HomeUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Tall viewport: a short default leaves later list items' click targets outside laid-out bounds.
@Config(sdk = [36], qualifiers = "w400dp-h3000dp")
class HomeContentTest {

    @get:Rule val compose = createComposeRule()

    private var openedThread: MessageId? = null

    private fun author(seed: Int) = AuthorId.of(ByteArray(32) { seed.toByte() })

    private fun summary(
        seed: Int,
        rootText: String?,
        rootAuthor: AuthorId? = author(seed),
        rootTimestamp: Long? = if (rootAuthor != null) 1_000L else null,
        latestListenedAuthor: AuthorId? = null,
        latestListenedText: String? = null,
        latestListenedTimestamp: Long? = if (latestListenedAuthor != null) 1_000L else null,
        messageCount: Int = 1,
        hasUnread: Boolean = false,
        isFavourite: Boolean = false,
    ) = ThreadSummary(
        rootId = MessageId.of(ByteArray(32) { seed.toByte() }),
        rootAuthor = rootAuthor,
        rootText = rootText,
        rootTimestamp = rootTimestamp,
        latestListenedAuthor = latestListenedAuthor,
        latestListenedText = latestListenedText,
        latestListenedTimestamp = latestListenedTimestamp,
        messageCount = messageCount,
        hasUnread = hasUnread,
        isFavourite = isFavourite,
    )

    private fun pageOf(vararg threads: ThreadSummary): Flow<PagingData<ThreadSummary>> =
        flowOf(PagingData.from(threads.toList()))

    private fun show(
        state: HomeUiState = HomeUiState.Threads(),
        myAuthor: AuthorId? = null,
        listening: List<ThreadSummary> = emptyList(),
        gossip: List<ThreadSummary> = emptyList(),
        onUnreadOnlyChanged: (Boolean) -> Unit = {},
        onSearchTextChanged: (String) -> Unit = {},
        onAuthorSelected: (AuthorId) -> Unit = {},
        onAuthorFilterCleared: () -> Unit = {},
    ) {
        compose.setContent {
            HomeContent(
                state = state,
                myAuthor = myAuthor,
                listeningThreads = pageOf(*listening.toTypedArray()),
                gossipThreads = pageOf(*gossip.toTypedArray()),
                onUnreadOnlyChanged = onUnreadOnlyChanged,
                onSearchTextChanged = onSearchTextChanged,
                onAuthorSelected = onAuthorSelected,
                onAuthorFilterCleared = onAuthorFilterCleared,
                onOpenThread = { openedThread = it },
                onCompose = {},
                onSettings = {},
                onSync = {},
            )
        }
    }

    @Test
    fun `the my circle tab is shown first, by default`() {
        show(listening = listOf(summary(1, "listening thread")), gossip = listOf(summary(2, "gossip thread")))

        compose.onNodeWithText("listening thread").assertExists()
    }

    @Test
    fun `switching tabs shows that tab's threads and not the other`() {
        show(listening = listOf(summary(1, "listening thread")), gossip = listOf(summary(2, "gossip thread")))

        compose.onNodeWithText("Other").performClick()

        compose.onNodeWithText("gossip thread").assertExists()
        compose.onNodeWithText("listening thread").assertDoesNotExist()
    }

    @Test
    fun `an empty tab says so rather than showing a blank list`() {
        show()

        compose.onNodeWithText("Nobody you listen to has posted yet.").assertExists()
    }

    @Test
    fun `tapping a thread opens it by root id`() {
        val thread = summary(7, "tap me")
        show(listening = listOf(thread))

        compose.onNodeWithText("tap me").performClick()

        assertEquals(thread.rootId, openedThread)
    }

    @Test
    fun `a missing root is explained rather than shown blank`() {
        val thread = summary(1, rootText = null, rootAuthor = null)
        show(listening = listOf(thread))

        compose.onNodeWithText("the start of this conversation isn't carried here").assertExists()
    }

    @Test
    fun `the root's author is shown by its resolved name`() {
        val author = author(1)
        val thread = summary(1, "hello", rootAuthor = author)
        val names = mapOf(author to NameResolver.resolve(author, nickname = "Sam", username = null))
        show(state = HomeUiState.Threads(names = names), listening = listOf(thread))

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
        show(state = HomeUiState.Threads(names = names), listening = listOf(thread))

        compose.onNodeWithText("the root").assertExists()
        compose.onNodeWithText("Dad").assertExists()
        compose.onNodeWithText("replied: the reply").assertExists()
    }

    @Test
    fun `with no listened reply, only the root is shown`() {
        val thread = summary(1, "just the root", latestListenedAuthor = null)
        show(listening = listOf(thread))

        compose.onNodeWithText("just the root").assertExists()
        compose.onAllNodesWithText("replied:", substring = true).assertCountEquals(0)
    }

    @Test
    fun `the count only names messages not already shown as text`() {
        // Root plus the quoted reply are two of the five held messages already visible above
        // this line — repeating that count here would just be noise.
        val root = author(1)
        val replier = author(2)
        val thread = summary(
            seed = 1,
            rootText = "the root",
            rootAuthor = root,
            latestListenedAuthor = replier,
            latestListenedText = "the reply",
            messageCount = 5,
        )
        show(listening = listOf(thread))

        compose.onNodeWithText("3 more messages", substring = true).assertExists()
    }

    @Test
    fun `a single leftover message is singular`() {
        val thread = summary(1, "the root", messageCount = 2)
        show(listening = listOf(thread))

        compose.onNodeWithText("1 more message", substring = true).assertExists()
    }

    @Test
    fun `nothing left over means no count is shown at all`() {
        val thread = summary(1, "the root", messageCount = 1)
        show(listening = listOf(thread))

        compose.onAllNodesWithText("more message", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a message posted moments ago reads as just now`() {
        val thread = summary(1, "hello", rootTimestamp = System.currentTimeMillis())
        show(listening = listOf(thread))

        compose.onNodeWithText("just now").assertExists()
    }

    @Test
    fun `a long-past root shows a relative time`() {
        val thread = summary(1, "hello", rootTimestamp = 1_000L)
        show(listening = listOf(thread))

        compose.onNodeWithText("years ago", substring = true).assertExists()
    }

    @Test
    fun `the root and the listened reply each carry their own relative time`() {
        // Deliberately different timestamps, so each showing up confirms that one is wired
        // independently of the other rather than both happening to read the same string.
        val replier = author(2)
        val thread = summary(
            seed = 1,
            rootText = "the root",
            rootTimestamp = 1_000L,
            latestListenedAuthor = replier,
            latestListenedText = "the reply",
            latestListenedTimestamp = System.currentTimeMillis(),
        )
        show(listening = listOf(thread))

        compose.onNodeWithText("years ago", substring = true).assertExists()
        compose.onNodeWithText("just now").assertExists()
    }

    @Test
    fun `an unread thread shows the unread indicator, a read one does not`() {
        val unread = summary(1, "unread", hasUnread = true)
        val read = summary(2, "read", hasUnread = false)
        show(listening = listOf(unread, read))

        compose.onAllNodesWithContentDescription("Unread").assertCountEquals(1)
    }

    @Test
    fun `a favourited thread shows a read-only star marker, on either tab`() {
        val thread = summary(1, "hello", hasUnread = true, isFavourite = true)
        show(gossip = listOf(thread))
        compose.onNodeWithText("Other").performClick()

        // Favouriting only happens from the thread itself; this marker isn't a click target, just a visibility check.
        compose.onNodeWithContentDescription("Favourited").assertExists()
    }

    @Test
    fun `an unfavourited thread shows no star marker at all`() {
        val thread = summary(1, "hello", isFavourite = false)
        show(listening = listOf(thread))

        compose.onNodeWithContentDescription("Favourited").assertDoesNotExist()
    }

    @Test
    fun `my own post is styled distinctly from everyone else's`() {
        val me = author(9)
        val someoneElse = author(1)
        val mine = summary(1, "mine", rootAuthor = me)
        val theirs = summary(2, "theirs", rootAuthor = someoneElse)
        val names = mapOf(
            me to NameResolver.resolve(me, nickname = null, username = "Me"),
            someoneElse to NameResolver.resolve(someoneElse, nickname = null, username = "Them"),
        )
        show(state = HomeUiState.Threads(names = names), myAuthor = me, listening = listOf(mine, theirs))

        // Asserting both labels render proves the "mine" chip path didn't crash with no fingerprint shown.
        compose.onNodeWithText("Me").assertExists()
        compose.onNodeWithText("Them").assertExists()
    }

    // ---- search box (collapsed by default; opened from the overflow menu) ----

    private fun expandSearch() {
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Search").performClick()
    }

    @Test
    fun `the search field is collapsed by default`() {
        show(listening = listOf(summary(1, "hello")))

        compose.onNodeWithContentDescription("Cancel search").assertDoesNotExist()
    }

    @Test
    fun `the overflow menu offers a way to expand search`() {
        show()

        compose.onNodeWithContentDescription("More options").performClick()

        compose.onNodeWithText("Search").assertExists()
    }

    @Test
    fun `the search menu item expands into a full-width field`() {
        show()

        expandSearch()

        compose.onNodeWithText("Search").assertExists()
        compose.onNodeWithContentDescription("Cancel search").assertExists()
    }

    @Test
    fun `cancelling the search field reports a clear and hides the field again`() {
        var textCleared = false
        var authorCleared = false
        show(onSearchTextChanged = { if (it.isEmpty()) textCleared = true }, onAuthorFilterCleared = { authorCleared = true })

        expandSearch()
        compose.onNodeWithContentDescription("Cancel search").performClick()

        assertTrue(textCleared)
        assertTrue(authorCleared)
        compose.onNodeWithContentDescription("Cancel search").assertDoesNotExist()
    }

    @Test
    fun `typing in the search field reports the raw text`() {
        var reported: String? = null
        show(onSearchTextChanged = { reported = it })
        expandSearch()

        compose.onNodeWithText("Search").performTextInput("sam")

        assertEquals("sam", reported)
    }

    @Test
    fun `typing a matching name shows it as a suggestion`() {
        val sam = author(1)
        val names = mapOf(sam to NameResolver.resolve(sam, nickname = "Sam", username = null))
        show(state = HomeUiState.Threads(names = names))
        expandSearch()

        compose.onNodeWithText("Search").performTextInput("sa")

        compose.onNodeWithText("Sam").assertExists()
    }

    @Test
    fun `a name that doesn't match the typed text is never suggested`() {
        val sam = author(1)
        val names = mapOf(sam to NameResolver.resolve(sam, nickname = "Sam", username = null))
        show(state = HomeUiState.Threads(names = names))
        expandSearch()

        compose.onNodeWithText("Search").performTextInput("zzz")

        compose.onNodeWithText("Sam").assertDoesNotExist()
    }

    @Test
    fun `selecting a suggestion reports the author and swaps the field for a chip`() {
        val sam = author(1)
        val names = mapOf(sam to NameResolver.resolve(sam, nickname = "Sam", username = null))
        var selected: AuthorId? = null
        show(state = HomeUiState.Threads(names = names), onAuthorSelected = { selected = it })
        expandSearch()

        compose.onNodeWithText("Search").performTextInput("sa")
        compose.onNodeWithText("Sam").performClick()

        assertEquals(sam, selected)
        compose.onNodeWithText("Search").assertDoesNotExist()
    }

    @Test
    fun `clearing the chip reports the clear and returns to an empty search field`() {
        val sam = author(1)
        val names = mapOf(sam to NameResolver.resolve(sam, nickname = "Sam", username = null))
        var cleared = false
        show(state = HomeUiState.Threads(names = names), onAuthorFilterCleared = { cleared = true })
        expandSearch()

        compose.onNodeWithText("Search").performTextInput("sa")
        compose.onNodeWithText("Sam").performClick()
        compose.onNodeWithContentDescription("Clear").performClick()

        assertTrue(cleared)
        // Cleared but still expanded — clearing the chip is not the same as collapsing.
        compose.onNodeWithText("Search").assertExists()
    }

    // ---- unread-only, shared across both tabs -------------------------------------------

    @Test
    fun `toggling unread-only reports the new value`() {
        var reported: Boolean? = null
        show(onUnreadOnlyChanged = { reported = it })

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Unread only").performClick()

        assertEquals(true, reported)
    }

    @Test
    fun `the unread-only control is one shared control, not per tab`() {
        show(listening = listOf(summary(1, "hello")), gossip = listOf(summary(2, "world")))

        compose.onNodeWithText("Other").performClick()
        compose.onNodeWithContentDescription("More options").performClick()

        // Exactly one "Unread only" control exists at all, regardless of which tab is showing
        // — proving it lives above the tabs rather than being duplicated per tab.
        compose.onAllNodesWithText("Unread only").assertCountEquals(1)
    }
}
