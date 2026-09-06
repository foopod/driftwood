package com.jonoshields.driftwood.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.jonoshields.driftwood.core.data.ActivityItem
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.MessageId
import com.jonoshields.driftwood.core.store.DisplayName
import com.jonoshields.driftwood.core.store.NameResolver
import com.jonoshields.driftwood.core.store.RelativeTime
import com.jonoshields.driftwood.ui.common.AuthorName
import com.jonoshields.driftwood.ui.common.LinkifiedText
import com.jonoshields.driftwood.ui.feed.truncateForPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Composable
fun ActivityScreen(
    onOpenThread: (root: MessageId, focus: MessageId) -> Unit,
    onOpenContact: (AuthorId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ActivityViewModel = hiltViewModel(),
) {
    val names by viewModel.names.collectAsStateWithLifecycle()
    val unreadCount by viewModel.unreadCount.collectAsStateWithLifecycle()
    ActivityContent(
        items = viewModel.items,
        names = names,
        unreadCount = unreadCount,
        myAuthor = viewModel.myAuthor,
        onOpenThread = onOpenThread,
        onOpenContact = onOpenContact,
        modifier = modifier,
    )
}

/** Body only — [com.jonoshields.driftwood.ui.shell.MainShell] provides the scaffold and bottom nav. */
@Composable
internal fun ActivityContent(
    items: Flow<PagingData<ActivityItem>> = flowOf(PagingData.empty()),
    names: Map<AuthorId, DisplayName> = emptyMap(),
    unreadCount: Int = 0,
    myAuthor: AuthorId? = null,
    onOpenThread: (root: MessageId, focus: MessageId) -> Unit = { _, _ -> },
    onOpenContact: (AuthorId) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val rows = items.collectAsLazyPagingItems()
    val nameOf: (AuthorId) -> DisplayName = { author ->
        names[author] ?: NameResolver.resolve(author, nickname = null, username = null)
    }

    if (rows.itemCount == 0) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No replies yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        if (unreadCount == 0 && rows.itemCount > 0) {
            item {
                Text(
                    "You're all caught up",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        items(count = rows.itemCount, key = rows.itemKey { it.replyId.toHex() }) { index ->
            rows[index]?.let { item ->
                ActivityRow(
                    item = item,
                    name = nameOf(item.replyAuthor),
                    isMine = item.replyAuthor == myAuthor,
                    onClick = { onOpenThread(item.threadRoot, item.replyId) },
                    onAuthorClick = { onOpenContact(item.replyAuthor) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

@Composable
private fun ActivityRow(
    item: ActivityItem,
    name: DisplayName,
    isMine: Boolean,
    onClick: () -> Unit,
    onAuthorClick: () -> Unit,
) {
    val now = remember { System.currentTimeMillis() }
    val tint = if (item.isUnread) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(tint)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item.isUnread) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .semantics { contentDescription = "Unread" },
                )
            }
            AuthorName(name, isMine = isMine, modifier = Modifier.clickable(onClick = onAuthorClick))
            Text(
                if (item.repliedToRoot) "replied to your post" else "replied to your reply",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                RelativeTime.describe(item.timestamp, now),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinkifiedText(
            truncateForPreview(item.replyText),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
