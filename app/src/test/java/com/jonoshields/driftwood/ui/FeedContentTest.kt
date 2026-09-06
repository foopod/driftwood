package com.jonoshields.driftwood.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.jonoshields.driftwood.ui.feed.FeedPane
import com.jonoshields.driftwood.ui.feed.FeedUiState
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
@Config(sdk = [36], qualifiers = "w400dp-h3000dp")
class FeedContentTest {

    @get:Rule val compose = createComposeRule()

    private var openedThread: MessageId? = null
    private var openedContact: AuthorId? = null

    private fun author(seed: Int) = AuthorId.of(ByteArray(32) { seed.toByte() })

    private fun summary(
        seed: Int,
        rootText: String?,
        rootAuthor: AuthorId? = author(seed),
        rootTimestamp: Long? = if (rootAuthor != null) 1_000L else null,
        latestKnownReplyAuthor: AuthorId? = null,
        latestKnownReplyText: String? = null,
        replyCount: Int = if (latestKnownReplyAuthor != null) 1 else 0,
        knownReplyCount: Int = if (latestKnownReplyAuthor != null) 1 else 0,
        isPinned: Boolean = false,
    ) = ThreadSummary(
        rootId = MessageId.of(ByteArray(32) { seed.toByte() }),
        rootAuthor = rootAuthor,
        rootText = rootText,
        rootTimestamp = rootTimestamp,
        rootUnread = false,
        replyCount = replyCount,
        unreadReplyCount = 0,
        knownReplyCount = knownReplyCount,
        knownUnreadReplyCount = 0,
        latestKnownReplyAuthor = latestKnownReplyAuthor,
        latestKnownReplyText = latestKnownReplyText,
        latestKnownReplyTimestamp = if (latestKnownReplyAuthor != null) 1_000L else null,
        secondKnownReplyAuthor = null,
        secondKnownReplyText = null,
        secondKnownReplyTimestamp = null,
        latestKnownUnreadReplyAuthor = null,
        latestKnownUnreadReplyText = null,
        latestKnownUnreadReplyTimestamp = null,
        secondKnownUnreadReplyAuthor = null,
        secondKnownUnreadReplyText = null,
        secondKnownUnreadReplyTimestamp = null,
        isPinned = isPinned,
    )

    private fun pageOf(vararg threads: ThreadSummary): Flow<PagingData<ThreadSummary>> =
        flowOf(PagingData.from(threads.toList()))

    private fun show(
        state: FeedUiState = FeedUiState.Threads(),
        myAuthor: AuthorId? = null,
        threads: List<ThreadSummary> = emptyList(),
        emptyDefault: String = "Nothing here yet.",
        searchExpanded: Boolean = false,
        onSearchTextChanged: (String) -> Unit = {},
        onAuthorSelected: (AuthorId) -> Unit = {},
        onAuthorFilterCleared: () -> Unit = {},
        onSearchCollapsed: () -> Unit = {},
        onOpenContact: (AuthorId) -> Unit = { openedContact = it },
        onOpenOwnProfile: () -> Unit = {},
    ) {
        compose.setContent {
            // Mirrors MainShell: search text / selected author are hoisted state the shell owns.
            var text by remember { mutableStateOf("") }
            var selected by remember { mutableStateOf<AuthorId?>(null) }
            FeedPane(
                state = state,
                threads = pageOf(*threads.toTypedArray()),
                emptyDefault = emptyDefault,
                myAuthor = myAuthor,
                searchExpanded = searchExpanded,
                searchText = text,
                selectedAuthor = selected,
                onSearchTextChanged = { text = it; selected = null; onSearchTextChanged(it) },
                onAuthorSelected = { selected = it; text = ""; onAuthorSelected(it) },
                onAuthorFilterCleared = { selected = null; onAuthorFilterCleared() },
                onSearchCollapsed = onSearchCollapsed,
                onOpenThread = { openedThread = it },
                onOpenContact = onOpenContact,
                onOpenOwnProfile = onOpenOwnProfile,
            )
        }
    }

    @Test
    fun `it renders the threads it is given`() {
        show(threads = listOf(summary(1, "from someone I follow"), summary(2, "a stranger's thread I joined")))

        compose.onNodeWithText("from someone I follow").assertExists()
        compose.onNodeWithText("a stranger's thread I joined").assertExists()
    }

    @Test
    fun `there is no unread indicator anywhere`() {
        show(threads = listOf(summary(1, "the root", latestKnownReplyAuthor = author(2), latestKnownReplyText = "a reply")))

        compose.onAllNodesWithContentDescription("Unread").assertCountEquals(0)
    }

    @Test
    fun `an empty list shows the default message`() {
        show(emptyDefault = "Nothing from people you follow yet.")

        compose.onNodeWithText("Nothing from people you follow yet.").assertExists()
    }

    @Test
    fun `tapping a thread opens it by root id`() {
        val thread = summary(7, "tap me")
        show(threads = listOf(thread))

        compose.onNodeWithText("tap me").performClick()

        assertEquals(thread.rootId, openedThread)
    }

    @Test
    fun `a missing root is explained rather than shown blank`() {
        show(threads = listOf(summary(1, rootText = null, rootAuthor = null)))

        compose.onNodeWithText("the start of this thread isn't carried here").assertExists()
    }

    @Test
    fun `the root's author is shown by its resolved name`() {
        val a = author(1)
        val names = mapOf(a to NameResolver.resolve(a, nickname = "Sam", username = null))
        show(state = FeedUiState.Threads(names = names), threads = listOf(summary(1, "hello", rootAuthor = a)))

        compose.onNodeWithText("Sam").assertExists()
        compose.onNodeWithText("hello").assertExists()
    }

    @Test
    fun `tapping the root author opens their profile, not the thread`() {
        val a = author(1)
        val names = mapOf(a to NameResolver.resolve(a, nickname = "Sam", username = null))
        show(state = FeedUiState.Threads(names = names), threads = listOf(summary(1, "hello", rootAuthor = a)))

        compose.onNodeWithText("Sam").performClick()

        assertEquals(a, openedContact)
        assertEquals(null, openedThread)
    }

    @Test
    fun `tapping your own name in the root opens your own profile, not the thread`() {
        val me = author(9)
        val names = mapOf(me to NameResolver.resolve(me, nickname = null, username = "Me"))
        var openedOwnProfile = false
        show(
            state = FeedUiState.Threads(names = names),
            myAuthor = me,
            threads = listOf(summary(1, "hello", rootAuthor = me)),
            onOpenOwnProfile = { openedOwnProfile = true },
        )

        compose.onNodeWithText("Me").performClick()

        assertEquals(null, openedThread)
        assertEquals(true, openedOwnProfile)
    }

    @Test
    fun `a known reply is shown alongside the root, not instead of it`() {
        val replier = author(2)
        val names = mapOf(replier to NameResolver.resolve(replier, nickname = "Dad", username = null))
        show(
            state = FeedUiState.Threads(names = names),
            threads = listOf(summary(1, "the root", rootAuthor = author(1), latestKnownReplyAuthor = replier, latestKnownReplyText = "the reply")),
        )

        compose.onNodeWithText("the root").assertExists()
        compose.onNodeWithText("Dad").assertExists()
        compose.onNodeWithText("replied: the reply").assertExists()
    }

    @Test
    fun `the reply count shows as a number with the comment icon`() {
        show(threads = listOf(summary(1, "the root", replyCount = 5)))

        compose.onNodeWithText("5").assertExists()
        compose.onNodeWithContentDescription("5 replies").assertExists()
    }

    @Test
    fun `a busy thread with a known replier shows the names summary, not the snippet`() {
        val replier = author(2)
        val names = mapOf(replier to NameResolver.resolve(replier, nickname = "Dad", username = null))
        show(
            state = FeedUiState.Threads(names = names),
            threads = listOf(summary(1, "the root", latestKnownReplyAuthor = replier, latestKnownReplyText = "the reply", replyCount = 5, knownReplyCount = 1)),
        )

        compose.onNodeWithText("5 replies from Dad").assertExists()
        compose.onAllNodesWithText("replied:", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a pinned thread shows a read-only pin marker`() {
        show(threads = listOf(summary(1, "hello", isPinned = true)))

        compose.onNodeWithContentDescription("Pinned").assertExists()
    }

    // ---- search field (shown only when the shell expands it) ----

    @Test
    fun `the search field is hidden until expanded`() {
        show(threads = listOf(summary(1, "hello")))

        compose.onNodeWithContentDescription("Cancel search").assertDoesNotExist()
    }

    @Test
    fun `an expanded search field can be cancelled`() {
        var collapsed = false
        show(searchExpanded = true, onSearchCollapsed = { collapsed = true })

        compose.onNodeWithContentDescription("Cancel search").performClick()

        assertTrue(collapsed)
    }

    @Test
    fun `typing in the search field reports the raw text`() {
        var reported: String? = null
        show(searchExpanded = true, onSearchTextChanged = { reported = it })

        compose.onNodeWithText("Search").performTextInput("sam")

        assertEquals("sam", reported)
    }

    @Test
    fun `typing a matching name shows it as a suggestion and selecting it reports the author`() {
        val sam = author(1)
        val names = mapOf(sam to NameResolver.resolve(sam, nickname = "Sam", username = null))
        var selected: AuthorId? = null
        show(state = FeedUiState.Threads(names = names), searchExpanded = true, onAuthorSelected = { selected = it })

        compose.onNodeWithText("Search").performTextInput("sa")
        compose.onNodeWithText("Sam").performClick()

        assertEquals(sam, selected)
    }

    @Test
    fun `an active search leaving the list empty explains why`() {
        show(searchExpanded = true, emptyDefault = "Nothing from people you follow yet.")

        compose.onNodeWithText("Search").performTextInput("nobody")

        compose.onNodeWithText("Nothing matches your search.").assertExists()
    }
}
