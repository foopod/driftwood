package com.jonoshields.driftwood.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.PagingData
import com.jonoshields.driftwood.core.data.ActivityItem
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.MessageId
import com.jonoshields.driftwood.core.store.NameResolver
import com.jonoshields.driftwood.ui.activity.ActivityContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h3000dp")
class ActivityContentTest {

    @get:Rule val compose = createComposeRule()

    private fun author(seed: Int) = AuthorId.of(ByteArray(32) { seed.toByte() })
    private fun msgId(seed: Int) = MessageId.of(ByteArray(32) { seed.toByte() })

    private fun item(
        seed: Int,
        author: AuthorId = author(seed),
        text: String = "a reply",
        isUnread: Boolean = true,
        repliedToRoot: Boolean = true,
        threadRoot: MessageId = msgId(100 + seed),
    ) = ActivityItem(
        replyId = msgId(seed),
        replyAuthor = author,
        replyText = text,
        timestamp = 1_000L,
        isUnread = isUnread,
        threadRoot = threadRoot,
        repliedToRoot = repliedToRoot,
        rootText = "the root",
    )

    private fun pageOf(vararg items: ActivityItem): Flow<PagingData<ActivityItem>> =
        flowOf(PagingData.from(items.toList()))

    private fun show(
        items: List<ActivityItem> = emptyList(),
        unreadCount: Int = items.count { it.isUnread },
        names: Map<AuthorId, com.jonoshields.driftwood.core.store.DisplayName> = emptyMap(),
        onOpenThread: (MessageId, MessageId) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            ActivityContent(
                items = pageOf(*items.toTypedArray()),
                names = names,
                unreadCount = unreadCount,
                onOpenThread = onOpenThread,
            )
        }
    }

    @Test
    fun `a reply to your root reads as replied to your post`() {
        val a = author(2)
        show(
            items = listOf(item(2, author = a, repliedToRoot = true)),
            names = mapOf(a to NameResolver.resolve(a, nickname = "Alice", username = null)),
        )

        compose.onNodeWithText("Alice").assertExists()
        compose.onNodeWithText("replied to your post").assertExists()
    }

    @Test
    fun `a reply to one of your replies reads as replied to your reply`() {
        show(items = listOf(item(2, repliedToRoot = false)))

        compose.onNodeWithText("replied to your reply").assertExists()
    }

    @Test
    fun `only unread rows carry the unread dot`() {
        show(items = listOf(item(1, isUnread = true), item(2, isUnread = false)))

        compose.onAllNodesWithContentDescription("Unread").assertCountEquals(1)
    }

    @Test
    fun `an empty list says there are no replies yet`() {
        show()

        compose.onNodeWithText("No replies yet.").assertExists()
    }

    @Test
    fun `once everything is read the list says you are caught up`() {
        show(items = listOf(item(1, isUnread = false)), unreadCount = 0)

        compose.onNodeWithText("You're all caught up").assertExists()
    }

    @Test
    fun `tapping a row opens its thread focused on that reply`() {
        var openedRoot: MessageId? = null
        var openedFocus: MessageId? = null
        val it = item(5, text = "tap me", threadRoot = msgId(200))
        show(items = listOf(it), onOpenThread = { r, f -> openedRoot = r; openedFocus = f })

        compose.onNodeWithText("tap me").performClick()

        assertEquals(msgId(200), openedRoot)
        assertEquals(it.replyId, openedFocus)
    }
}
