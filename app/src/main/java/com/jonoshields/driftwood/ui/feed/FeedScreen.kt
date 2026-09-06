package com.jonoshields.driftwood.ui.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.jonoshields.driftwood.core.data.ThreadSummary
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.MessageId
import com.jonoshields.driftwood.core.store.DisplayName
import com.jonoshields.driftwood.core.store.NameResolver
import com.jonoshields.driftwood.core.store.RelativeTime
import com.jonoshields.driftwood.theme.DriftwoodTheme
import com.jonoshields.driftwood.ui.common.AuthorName
import com.jonoshields.driftwood.ui.common.LinkifiedText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * One thread list — Feed or Discover, chosen by the caller's [threads] flow. No scaffold of its
 * own: [com.jonoshields.driftwood.ui.shell.MainShell] owns the top bar, FAB and bottom nav.
 * Search state is hoisted to the shell so it's shared across the Feed and Discover panes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FeedPane(
    state: FeedUiState,
    threads: Flow<PagingData<ThreadSummary>> = emptyPagingFlow(),
    emptyDefault: String = "Nothing here yet.",
    myAuthor: AuthorId? = null,
    searchExpanded: Boolean = false,
    searchText: String = "",
    selectedAuthor: AuthorId? = null,
    onSearchTextChanged: (String) -> Unit = {},
    onAuthorSelected: (AuthorId) -> Unit = {},
    onAuthorFilterCleared: () -> Unit = {},
    onSearchCollapsed: () -> Unit = {},
    onOpenThread: (MessageId) -> Unit,
    onOpenContact: (AuthorId) -> Unit = {},
    onOpenOwnProfile: () -> Unit = {},
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    when (state) {
        FeedUiState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        FeedUiState.Empty -> EmptyState(modifier)
        is FeedUiState.Threads -> {
            val items = threads.collectAsLazyPagingItems()
            Column(modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = searchExpanded,
                    enter = expandVertically(tween(SEARCH_ANIMATION_MILLIS), expandFrom = Alignment.Top) +
                        fadeIn(tween(SEARCH_ANIMATION_MILLIS)),
                    exit = shrinkVertically(tween(SEARCH_ANIMATION_MILLIS), shrinkTowards = Alignment.Top) +
                        fadeOut(tween(SEARCH_ANIMATION_MILLIS)),
                ) {
                    FeedSearchField(
                        names = state.names,
                        searchText = searchText,
                        selectedAuthor = selectedAuthor,
                        onSearchTextChanged = onSearchTextChanged,
                        onAuthorSelected = onAuthorSelected,
                        onClearAuthor = onAuthorFilterCleared,
                        onCollapse = onSearchCollapsed,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
                // Kept out of the repository layer, which stays name-agnostic.
                val nameOf: (AuthorId) -> DisplayName = { author ->
                    state.names[author] ?: NameResolver.resolve(author, nickname = null, username = null)
                }
                val searching = searchText.isNotBlank() || selectedAuthor != null
                ThreadList(
                    threads = items,
                    emptyMessage = if (searching) "Nothing matches your search." else emptyDefault,
                    nameOf = nameOf,
                    myAuthor = myAuthor,
                    onOpenThread = onOpenThread,
                    onOpenContact = onOpenContact,
                    onOpenOwnProfile = onOpenOwnProfile,
                    onRefresh = onRefresh,
                )
            }
        }
    }
}

/** Free text with a name type-ahead, or — once picked — a removable chip; the two are mutually exclusive. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedSearchField(
    names: Map<AuthorId, DisplayName>,
    searchText: String,
    selectedAuthor: AuthorId?,
    onSearchTextChanged: (String) -> Unit,
    onAuthorSelected: (AuthorId) -> Unit,
    onClearAuthor: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selectedAuthor != null) {
        val name = names[selectedAuthor] ?: NameResolver.resolve(selectedAuthor, nickname = null, username = null)
        InputChip(
            selected = true,
            onClick = onClearAuthor,
            label = { Text(name.label ?: name.fingerprint) },
            trailingIcon = {
                Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(18.dp))
            },
            modifier = modifier,
        )
        return
    }

    var expanded by remember { mutableStateOf(false) }
    // Filtered client-side out of the already-loaded names map — the identity set is small.
    val suggestions = remember(names, searchText) {
        if (searchText.isBlank()) {
            emptyList()
        } else {
            names.entries
                .filter { (_, name) ->
                    name.label?.contains(searchText, ignoreCase = true) == true ||
                        name.fingerprint.contains(searchText, ignoreCase = true)
                }
                .sortedWith(
                    compareBy(
                        { !it.value.label.orEmpty().startsWith(searchText, ignoreCase = true) },
                        { it.value.label },
                    ),
                )
        }
    }

    ExposedDropdownMenuBox(
        expanded = expanded && suggestions.isNotEmpty(),
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        TextField(
            value = searchText,
            onValueChange = {
                onSearchTextChanged(it)
                expanded = true
            },
            placeholder = { Text("Search") },
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = onCollapse) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel search")
                }
            },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded && suggestions.isNotEmpty(),
            onDismissRequest = { expanded = false },
        ) {
            suggestions.forEach { (author, name) ->
                DropdownMenuItem(
                    text = { Text(name.label ?: name.fingerprint) },
                    onClick = {
                        onAuthorSelected(author)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThreadList(
    threads: LazyPagingItems<ThreadSummary>,
    emptyMessage: String,
    nameOf: (AuthorId) -> DisplayName,
    myAuthor: AuthorId?,
    onOpenThread: (MessageId) -> Unit,
    onOpenContact: (AuthorId) -> Unit,
    onOpenOwnProfile: () -> Unit,
    onRefresh: () -> Unit,
) {
    // Never settles into a spinning state of its own — pulling just opens Sync, same as the
    // top-bar button, since an actual sync needs a peer picked there, not a fire-and-forget refresh.
    PullToRefreshBox(isRefreshing = false, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Extra bottom clearance so the compose FAB never sits over the last thread.
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (threads.itemCount == 0) {
                item {
                    Text(
                        emptyMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(count = threads.itemCount, key = threads.itemKey { it.rootId.toHex() }) { index ->
                    threads[index]?.let { thread ->
                        ThreadRow(
                            thread = thread,
                            nameOf = nameOf,
                            myAuthor = myAuthor,
                            onClick = { onOpenThread(thread.rootId) },
                            onOpenContact = onOpenContact,
                            onOpenOwnProfile = onOpenOwnProfile,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Your network is quiet", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Post something, then sync with someone to begin.",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun ThreadRow(
    thread: ThreadSummary,
    nameOf: (AuthorId) -> DisplayName,
    myAuthor: AuthorId?,
    onClick: () -> Unit,
    onOpenContact: (AuthorId) -> Unit,
    onOpenOwnProfile: () -> Unit,
) {
    // Read once per row — a relative time drifting a few seconds stale isn't worth a ticker.
    val now = remember { System.currentTimeMillis() }
    val preview = remember(thread) { computeThreadPreview(thread) }

    // One clickable area around three standalone pieces, not one card holding all of them.
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val rootAuthor = thread.rootAuthor
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (rootAuthor == null) {
                        Text(
                            "the start of this thread isn't carried here",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            AuthorName(
                                nameOf(rootAuthor),
                                isMine = rootAuthor == myAuthor,
                                modifier = Modifier.clickable(
                                    onClick = {
                                        if (rootAuthor == myAuthor) onOpenOwnProfile() else onOpenContact(rootAuthor)
                                    },
                                ),
                            )
                            thread.rootTimestamp?.let {
                                Text(
                                    RelativeTime.describe(it, now),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        LinkifiedText(
                            truncateForPreview(thread.rootText.orEmpty()),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Read-only — pinning only happens from the thread itself now.
                    if (thread.isPinned) {
                        Icon(
                            imageVector = Icons.Filled.PushPin,
                            contentDescription = "Pinned",
                        )
                    }
                    preview.replyCount?.let { count ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                count.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Comment,
                                contentDescription = "$count replies",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        when (val replyPreview = preview.preview) {
            ReplyPreview.None -> Unit
            is ReplyPreview.Snippets -> {
                // Root plus each reply, not the reply alone — indented/muted so it reads as an answer.
                // Shown oldest-first, like the conversation happened.
                replyPreview.replies.forEach { snippet ->
                    Card(
                        Modifier.fillMaxWidth().padding(start = 24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                AuthorName(
                                    nameOf(snippet.author),
                                    isMine = snippet.author == myAuthor,
                                    modifier = Modifier.clickable(
                                        onClick = {
                                            if (snippet.author == myAuthor) onOpenOwnProfile() else onOpenContact(snippet.author)
                                        },
                                    ),
                                )
                                Text(
                                    RelativeTime.describe(snippet.timestamp, now),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            LinkifiedText(
                                "replied: ${truncateForPreview(snippet.text)}",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            is ReplyPreview.Summary -> {
                Card(
                    Modifier.fillMaxWidth().padding(start = 24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            replySummaryText(replyPreview, unread = false, nameOf),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun replySummaryText(
    summary: ReplyPreview.Summary,
    unread: Boolean,
    nameOf: (AuthorId) -> DisplayName,
): String {
    val count = when {
        summary.count == 1 && unread -> "1 unread reply"
        summary.count == 1 -> "1 reply"
        unread -> "${summary.count} unread replies"
        else -> "${summary.count} replies"
    }
    if (summary.names.isEmpty()) return count
    val labels = summary.names.map { nameOf(it).label ?: nameOf(it).fingerprint }
    val named = when (labels.size) {
        1 -> labels[0]
        else -> "${labels[0]} and ${labels[1]}"
    }
    val suffix = if (summary.moreCount > 0) " + ${summary.moreCount} more" else ""
    return "$count from $named$suffix"
}

/** Default for the preview/test-friendly [FeedPane] parameters — an always-empty page. */
private fun emptyPagingFlow(): Flow<PagingData<ThreadSummary>> = flowOf(PagingData.empty())

/** Quicker than Compose's defaults — the search expand/collapse should feel snappy, not floaty. */
private const val SEARCH_ANIMATION_MILLIS = 120

@Preview(showBackground = true)
@Composable
private fun EmptyPreview() {
    DriftwoodTheme {
        FeedPane(state = FeedUiState.Empty, onOpenThread = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun ThreadsPreview() {
    val author = AuthorId.of(ByteArray(32) { 9 })
    val listening = ThreadSummary(
        rootId = MessageId.of(ByteArray(32) { 1 }),
        rootAuthor = author,
        rootText = "Trying out this gossip thing.",
        rootTimestamp = System.currentTimeMillis() - 3_600_000,
        rootUnread = false,
        replyCount = 1,
        unreadReplyCount = 0,
        knownReplyCount = 1,
        knownUnreadReplyCount = 0,
        latestKnownReplyAuthor = author,
        latestKnownReplyText = "Working nicely so far.",
        latestKnownReplyTimestamp = System.currentTimeMillis() - 300_000,
        secondKnownReplyAuthor = null,
        secondKnownReplyText = null,
        secondKnownReplyTimestamp = null,
        latestKnownUnreadReplyAuthor = null,
        latestKnownUnreadReplyText = null,
        latestKnownUnreadReplyTimestamp = null,
        secondKnownUnreadReplyAuthor = null,
        secondKnownUnreadReplyText = null,
        secondKnownUnreadReplyTimestamp = null,
        isPinned = false,
    )
    val gossip = ThreadSummary(
        rootId = MessageId.of(ByteArray(32) { 2 }),
        rootAuthor = null,
        rootText = null,
        rootTimestamp = null,
        rootUnread = false,
        replyCount = 4,
        unreadReplyCount = 0,
        knownReplyCount = 0,
        knownUnreadReplyCount = 0,
        latestKnownReplyAuthor = null,
        latestKnownReplyText = null,
        latestKnownReplyTimestamp = null,
        secondKnownReplyAuthor = null,
        secondKnownReplyText = null,
        secondKnownReplyTimestamp = null,
        latestKnownUnreadReplyAuthor = null,
        latestKnownUnreadReplyText = null,
        latestKnownUnreadReplyTimestamp = null,
        secondKnownUnreadReplyAuthor = null,
        secondKnownUnreadReplyText = null,
        secondKnownUnreadReplyTimestamp = null,
        isPinned = false,
    )
    DriftwoodTheme {
        FeedPane(
            state = FeedUiState.Threads(),
            threads = flowOf(PagingData.from(listOf(listening, gossip))),
            onOpenThread = {},
        )
    }
}
