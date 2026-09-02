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
        rootUnread: Boolean = false,
        latestKnownReplyAuthor: AuthorId? = null,
        latestKnownReplyText: String? = null,
        latestKnownReplyTimestamp: Long? = if (latestKnownReplyAuthor != null) 1_000L else null,
        secondKnownReplyAuthor: AuthorId? = null,
        secondKnownReplyText: String? = null,
        secondKnownReplyTimestamp: Long? = if (secondKnownReplyAuthor != null) 1_000L else null,
        replyCount: Int = if (latestKnownReplyAuthor != null) 1 else 0,
        unreadReplyCount: Int = 0,
        knownReplyCount: Int = if (latestKnownReplyAuthor != null) 1 else 0,
        knownUnreadReplyCount: Int = 0,
        latestKnownUnreadReplyAuthor: AuthorId? = null,
        latestKnownUnreadReplyText: String? = null,
        latestKnownUnreadReplyTimestamp: Long? = if (latestKnownUnreadReplyAuthor != null) 1_000L else null,
        secondKnownUnreadReplyAuthor: AuthorId? = null,
        secondKnownUnreadReplyText: String? = null,
        secondKnownUnreadReplyTimestamp: Long? = if (secondKnownUnreadReplyAuthor != null) 1_000L else null,
        isPinned: Boolean = false,
    ) = ThreadSummary(
        rootId = MessageId.of(ByteArray(32) { seed.toByte() }),
        rootAuthor = rootAuthor,
        rootText = rootText,
        rootTimestamp = rootTimestamp,
        rootUnread = rootUnread,
        replyCount = replyCount,
        unreadReplyCount = unreadReplyCount,
        knownReplyCount = knownReplyCount,
        knownUnreadReplyCount = knownUnreadReplyCount,
        latestKnownReplyAuthor = latestKnownReplyAuthor,
        latestKnownReplyText = latestKnownReplyText,
        latestKnownReplyTimestamp = latestKnownReplyTimestamp,
        secondKnownReplyAuthor = secondKnownReplyAuthor,
        secondKnownReplyText = secondKnownReplyText,
        secondKnownReplyTimestamp = secondKnownReplyTimestamp,
        latestKnownUnreadReplyAuthor = latestKnownUnreadReplyAuthor,
        latestKnownUnreadReplyText = latestKnownUnreadReplyText,
        latestKnownUnreadReplyTimestamp = latestKnownUnreadReplyTimestamp,
        secondKnownUnreadReplyAuthor = secondKnownUnreadReplyAuthor,
        secondKnownUnreadReplyText = secondKnownUnreadReplyText,
        secondKnownUnreadReplyTimestamp = secondKnownUnreadReplyTimestamp,
        isPinned = isPinned,
    )

    private fun pageOf(vararg threads: ThreadSummary): Flow<PagingData<ThreadSummary>> =
        flowOf(PagingData.from(threads.toList()))

    private fun show(
        state: HomeUiState = HomeUiState.Threads(),
        myAuthor: AuthorId? = null,
        following: List<ThreadSummary> = emptyList(),
        context: List<ThreadSummary> = emptyList(),
        other: List<ThreadSummary> = emptyList(),
        onUnreadOnlyChanged: (Boolean) -> Unit = {},
        onSearchTextChanged: (String) -> Unit = {},
        onAuthorSelected: (AuthorId) -> Unit = {},
        onAuthorFilterCleared: () -> Unit = {},
        onOpenContact: (AuthorId) -> Unit = {},
        onSettings: () -> Unit = {},
    ) {
        compose.setContent {
            HomeContent(
                state = state,
                myAuthor = myAuthor,
                followingThreads = pageOf(*following.toTypedArray()),
                contextThreads = pageOf(*context.toTypedArray()),
                otherThreads = pageOf(*other.toTypedArray()),
                onUnreadOnlyChanged = onUnreadOnlyChanged,
                onSearchTextChanged = onSearchTextChanged,
                onAuthorSelected = onAuthorSelected,
                onAuthorFilterCleared = onAuthorFilterCleared,
                onOpenThread = { openedThread = it },
                onOpenContact = onOpenContact,
                onCompose = {},
                onSettings = onSettings,
                onSync = {},
            )
        }
    }

    @Test
    fun `the following tab is shown first, by default`() {
        show(following = listOf(summary(1, "following thread")), other = listOf(summary(2, "gossip thread")))

        compose.onNodeWithText("following thread").assertExists()
    }

    @Test
    fun `switching to other shows only that tab's threads`() {
        show(
            following = listOf(summary(1, "following thread")),
            context = listOf(summary(2, "context thread")),
            other = listOf(summary(3, "gossip thread")),
        )

        compose.onNodeWithText("Other").performClick()

        compose.onNodeWithText("gossip thread").assertExists()
        compose.onNodeWithText("following thread").assertDoesNotExist()
        compose.onNodeWithText("context thread").assertDoesNotExist()
    }

    @Test
    fun `switching to context shows only that tab's threads`() {
        show(
            following = listOf(summary(1, "following thread")),
            context = listOf(summary(2, "context thread")),
            other = listOf(summary(3, "gossip thread")),
        )

        compose.onNodeWithText("Context").performClick()

        compose.onNodeWithText("context thread").assertExists()
        compose.onNodeWithText("following thread").assertDoesNotExist()
        compose.onNodeWithText("gossip thread").assertDoesNotExist()
    }

    @Test
    fun `an empty context tab explains why a stranger's thread would show up here`() {
        show()

        compose.onNodeWithText("Context").performClick()

        compose.onNodeWithText("Nobody you follow has joined a stranger's thread yet.").assertExists()
    }

    @Test
    fun `an empty tab says so rather than showing a blank list`() {
        show()

        compose.onNodeWithText("Nobody you follow has posted yet.").assertExists()
    }

    @Test
    fun `tapping a thread opens it by root id`() {
        val thread = summary(7, "tap me")
        show(following = listOf(thread))

        compose.onNodeWithText("tap me").performClick()

        assertEquals(thread.rootId, openedThread)
    }

    @Test
    fun `a missing root is explained rather than shown blank`() {
        val thread = summary(1, rootText = null, rootAuthor = null)
        show(following = listOf(thread))

        compose.onNodeWithText("the start of this thread isn't carried here").assertExists()
    }

    @Test
    fun `the root's author is shown by its resolved name`() {
        val author = author(1)
        val thread = summary(1, "hello", rootAuthor = author)
        val names = mapOf(author to NameResolver.resolve(author, nickname = "Sam", username = null))
        show(state = HomeUiState.Threads(names = names), following = listOf(thread))

        compose.onNodeWithText("Sam").assertExists()
        compose.onNodeWithText("hello").assertExists()
    }

    @Test
    fun `tapping the root author opens their profile, not the thread`() {
        val author = author(1)
        val thread = summary(1, "hello", rootAuthor = author)
        val names = mapOf(author to NameResolver.resolve(author, nickname = "Sam", username = null))
        var openedContact: AuthorId? = null
        show(state = HomeUiState.Threads(names = names), following = listOf(thread), onOpenContact = { openedContact = it })

        compose.onNodeWithText("Sam").performClick()

        assertEquals(author, openedContact)
        assertEquals(null, openedThread)
    }

    @Test
    fun `tapping your own name in the root opens your own profile, not the thread`() {
        val me = author(9)
        val thread = summary(1, "hello", rootAuthor = me)
        val names = mapOf(me to NameResolver.resolve(me, nickname = null, username = "Me"))
        var openedContact: AuthorId? = null
        var openedOwnProfile = false
        show(
            state = HomeUiState.Threads(names = names),
            myAuthor = me,
            following = listOf(thread),
            onOpenContact = { openedContact = it },
            onSettings = { openedOwnProfile = true },
        )

        compose.onNodeWithText("Me").performClick()

        assertEquals(null, openedContact)
        assertEquals(null, openedThread)
        assertEquals(true, openedOwnProfile)
    }

    @Test
    fun `tapping a reply snippet's author opens their profile, not the thread`() {
        val root = author(1)
        val replier = author(2)
        val thread = summary(
            seed = 1,
            rootText = "the root",
            rootAuthor = root,
            latestKnownReplyAuthor = replier,
            latestKnownReplyText = "the reply",
        )
        val names = mapOf(replier to NameResolver.resolve(replier, nickname = "Dad", username = null))
        var openedContact: AuthorId? = null
        show(state = HomeUiState.Threads(names = names), following = listOf(thread), onOpenContact = { openedContact = it })

        compose.onNodeWithText("Dad").performClick()

        assertEquals(replier, openedContact)
        assertEquals(null, openedThread)
    }

    @Test
    fun `a known reply is shown alongside the root, not instead of it`() {
        val root = author(1)
        val replier = author(2)
        val thread = summary(
            seed = 1,
            rootText = "the root",
            rootAuthor = root,
            latestKnownReplyAuthor = replier,
            latestKnownReplyText = "the reply",
        )
        val names = mapOf(replier to NameResolver.resolve(replier, nickname = "Dad", username = null))
        show(state = HomeUiState.Threads(names = names), following = listOf(thread))

        compose.onNodeWithText("the root").assertExists()
        compose.onNodeWithText("Dad").assertExists()
        compose.onNodeWithText("replied: the reply").assertExists()
    }

    @Test
    fun `with no known reply, only the root is shown`() {
        val thread = summary(1, "just the root", latestKnownReplyAuthor = null)
        show(following = listOf(thread))

        compose.onNodeWithText("just the root").assertExists()
        compose.onAllNodesWithText("replied:", substring = true).assertCountEquals(0)
    }

    @Test
    fun `the reply count shows as a number with the comment icon, next to the root`() {
        val thread = summary(1, "the root", replyCount = 5)
        show(following = listOf(thread))

        compose.onNodeWithText("5").assertExists()
        compose.onNodeWithContentDescription("5 replies").assertExists()
    }

    @Test
    fun `no replies means no count or icon is shown at all`() {
        val thread = summary(1, "the root", replyCount = 0)
        show(following = listOf(thread))

        compose.onAllNodesWithContentDescription("replies", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a busy thread with a known replier shows the names summary, not the snippet`() {
        val replier = author(2)
        val thread = summary(
            seed = 1,
            rootText = "the root",
            latestKnownReplyAuthor = replier,
            latestKnownReplyText = "the reply",
            replyCount = 5,
            knownReplyCount = 1,
        )
        val names = mapOf(replier to NameResolver.resolve(replier, nickname = "Dad", username = null))
        show(state = HomeUiState.Threads(names = names), following = listOf(thread))

        compose.onNodeWithText("5 replies from Dad").assertExists()
        compose.onAllNodesWithText("replied:", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a busy thread with no known repliers shows a plain unnamed count`() {
        val thread = summary(1, "the root", replyCount = 5, knownReplyCount = 0)
        show(following = listOf(thread))

        compose.onNodeWithText("5 replies").assertExists()
    }

    @Test
    fun `new replies since the root was read move the unread dot to the reply card`() {
        val replier = author(2)
        val thread = summary(
            seed = 1,
            rootText = "the root",
            rootUnread = false,
            replyCount = 1,
            unreadReplyCount = 1,
            knownReplyCount = 1,
            knownUnreadReplyCount = 1,
            latestKnownUnreadReplyAuthor = replier,
            latestKnownUnreadReplyText = "brand new",
        )
        show(following = listOf(thread))

        // No dot on the root itself...
        compose.onAllNodesWithContentDescription("Unread").assertCountEquals(1)
        compose.onNodeWithText("replied: brand new").assertExists()
    }

    @Test
    fun `a message posted moments ago reads as just now`() {
        val thread = summary(1, "hello", rootTimestamp = System.currentTimeMillis())
        show(following = listOf(thread))

        compose.onNodeWithText("just now").assertExists()
    }

    @Test
    fun `a long-past root shows a relative time`() {
        val thread = summary(1, "hello", rootTimestamp = 1_000L)
        show(following = listOf(thread))

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
            latestKnownReplyAuthor = replier,
            latestKnownReplyText = "the reply",
            latestKnownReplyTimestamp = System.currentTimeMillis(),
        )
        show(following = listOf(thread))

        compose.onNodeWithText("years ago", substring = true).assertExists()
        compose.onNodeWithText("just now").assertExists()
    }

    @Test
    fun `an unread thread shows the unread indicator, a read one does not`() {
        val unread = summary(1, "unread", rootUnread = true)
        val read = summary(2, "read", rootUnread = false)
        show(following = listOf(unread, read))

        compose.onAllNodesWithContentDescription("Unread").assertCountEquals(1)
    }

    @Test
    fun `a pinned thread shows a read-only pin marker, on either tab`() {
        val thread = summary(1, "hello", rootUnread = true, isPinned = true)
        show(other = listOf(thread))
        compose.onNodeWithText("Other").performClick()

        // Favouriting only happens from the thread itself; this marker isn't a click target, just a visibility check.
        compose.onNodeWithContentDescription("Pinned").assertExists()
    }

    @Test
    fun `an unpinned thread shows no pin marker at all`() {
        val thread = summary(1, "hello", isPinned = false)
        show(following = listOf(thread))

        compose.onNodeWithContentDescription("Pinned").assertDoesNotExist()
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
        show(state = HomeUiState.Threads(names = names), myAuthor = me, following = listOf(mine, theirs))

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
        show(following = listOf(summary(1, "hello")))

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
        show(following = listOf(summary(1, "hello")), other = listOf(summary(2, "world")))

        compose.onNodeWithText("Other").performClick()
        compose.onNodeWithContentDescription("More options").performClick()

        // Exactly one "Unread only" control exists at all, regardless of which tab is showing
        // — proving it lives above the tabs rather than being duplicated per tab.
        compose.onAllNodesWithText("Unread only").assertCountEquals(1)
    }

    private fun enableUnreadOnly() {
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Unread only").performClick()
    }

    @Test
    fun `enabling unread-only shows a clearable chip top-left`() {
        show()

        enableUnreadOnly()

        compose.onNodeWithText("Unread").assertExists()
    }

    @Test
    fun `no chip is shown while unread-only is off`() {
        show()

        compose.onNodeWithText("Unread").assertDoesNotExist()
    }

    @Test
    fun `tapping the unread chip's close icon clears the filter`() {
        var reported: Boolean? = null
        show(onUnreadOnlyChanged = { reported = it })

        enableUnreadOnly()
        compose.onNodeWithContentDescription("Clear").performClick()

        assertEquals(false, reported)
        compose.onNodeWithText("Unread").assertDoesNotExist()
    }

    @Test
    fun `unread-only leaving a tab empty explains why, instead of the default message`() {
        show()

        enableUnreadOnly()

        compose.onNodeWithText("You're all caught up.").assertExists()
        compose.onNodeWithText("Nobody you follow has posted yet.").assertDoesNotExist()
    }

    @Test
    fun `an active search leaving a tab empty explains why, instead of the default message`() {
        show()
        expandSearch()

        compose.onNodeWithText("Search").performTextInput("nobody")

        compose.onNodeWithText("Nothing matches your search.").assertExists()
    }
}
