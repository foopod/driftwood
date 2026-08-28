package com.jonoshields.driftwood.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Composable
fun HomeScreen(
    onOpenThread: (MessageId) -> Unit,
    onOpenContact: (AuthorId) -> Unit,
    onCompose: () -> Unit,
    onSettings: () -> Unit,
    onSync: () -> Unit,
    onAddContact: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeContent(
        state = state,
        myAuthor = viewModel.myAuthor,
        listeningThreads = viewModel.listeningThreads,
        gossipThreads = viewModel.gossipThreads,
        onUnreadOnlyChanged = viewModel::setUnreadOnly,
        onSearchTextChanged = viewModel::setSearchText,
        onAuthorSelected = viewModel::selectAuthor,
        onAuthorFilterCleared = viewModel::clearAuthorFilter,
        onOpenThread = onOpenThread,
        onOpenContact = onOpenContact,
        onCompose = onCompose,
        onSettings = onSettings,
        onSync = onSync,
        onAddContact = onAddContact,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeContent(
    state: HomeUiState,
    myAuthor: AuthorId? = null,
    listeningThreads: Flow<PagingData<ThreadSummary>> = emptyPagingFlow(),
    gossipThreads: Flow<PagingData<ThreadSummary>> = emptyPagingFlow(),
    onUnreadOnlyChanged: (Boolean) -> Unit = {},
    onSearchTextChanged: (String) -> Unit = {},
    onAuthorSelected: (AuthorId) -> Unit = {},
    onAuthorFilterCleared: () -> Unit = {},
    onOpenThread: (MessageId) -> Unit,
    onOpenContact: (AuthorId) -> Unit = {},
    onCompose: () -> Unit,
    onSettings: () -> Unit,
    onSync: () -> Unit,
    onAddContact: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 0 = My Circle, 1 = Other. Not tied to HomeUiState, so it survives the list reloading.
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // Search uses plain `remember`, not `rememberSaveable` — resetting on rotation is accepted.
    var unreadOnly by rememberSaveable { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var selectedAuthor by remember { mutableStateOf<AuthorId?>(null) }
    var searchExpanded by remember { mutableStateOf(false) }

    fun collapseSearch() {
        searchExpanded = false
        searchText = ""
        selectedAuthor = null
        onSearchTextChanged("")
        onAuthorFilterCleared()
    }

    // Both tabs collected always — cheap, since Paging only loads pages actually requested.
    val listeningItems = listeningThreads.collectAsLazyPagingItems()
    val gossipItems = gossipThreads.collectAsLazyPagingItems()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    if (unreadOnly) {
                        InputChip(
                            selected = true,
                            onClick = {
                                unreadOnly = false
                                onUnreadOnlyChanged(false)
                            },
                            label = { Text("Unread") },
                            trailingIcon = {
                                Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                },
                actions = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The one thing worth a permanent, always-visible slot; everything else is one tap further away.
                    Button(
                        onClick = onSync,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Sync")
                    }
                    // Boxed together so DropdownMenu anchors to the button, not the whole Row.
                    Box {
                        var menuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Search") },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                onClick = { menuOpen = false; searchExpanded = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Unread only") },
                                leadingIcon = if (unreadOnly) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else {
                                    null
                                },
                                // Stays open so flipping it shows the checkmark land before dismissing.
                                onClick = {
                                    val new = !unreadOnly
                                    unreadOnly = new
                                    onUnreadOnlyChanged(new)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Quick verify") },
                                onClick = { menuOpen = false; onAddContact() },
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                onClick = { menuOpen = false; onSettings() },
                            )
                        }
                    }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCompose,
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
                icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                text = { Text("Compose") },
            )
        },
    ) { padding ->
        when (state) {
            HomeUiState.Loading -> Unit
            HomeUiState.Empty -> EmptyState(Modifier.padding(padding))
            is HomeUiState.Threads -> Column(Modifier.padding(padding).fillMaxSize()) {
                AnimatedVisibility(
                    visible = searchExpanded,
                    enter = expandVertically(tween(SEARCH_ANIMATION_MILLIS), expandFrom = Alignment.Top) +
                        fadeIn(tween(SEARCH_ANIMATION_MILLIS)),
                    exit = shrinkVertically(tween(SEARCH_ANIMATION_MILLIS), shrinkTowards = Alignment.Top) +
                        fadeOut(tween(SEARCH_ANIMATION_MILLIS)),
                ) {
                    HomeSearchField(
                        names = state.names,
                        searchText = searchText,
                        selectedAuthor = selectedAuthor,
                        onSearchTextChanged = {
                            searchText = it
                            selectedAuthor = null
                            onSearchTextChanged(it)
                        },
                        onAuthorSelected = { author ->
                            selectedAuthor = author
                            searchText = ""
                            onAuthorSelected(author)
                        },
                        onClearAuthor = {
                            selectedAuthor = null
                            onAuthorFilterCleared()
                        },
                        onCollapse = { collapseSearch() },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("My Circle") },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Other") },
                    )
                }
                // Kept out of the repository layer, which stays name-agnostic.
                val nameOf: (AuthorId) -> DisplayName = { author ->
                    state.names[author] ?: NameResolver.resolve(author, nickname = null, username = null)
                }
                // An active search explains an empty tab more precisely than unread-only does,
                // on the rare occasion both are on at once.
                val emptyReason = when {
                    searchText.isNotBlank() || selectedAuthor != null -> "Nothing matches your search."
                    unreadOnly -> "You're all caught up."
                    else -> null
                }
                if (selectedTab == 0) {
                    ThreadTab(
                        threads = listeningItems,
                        emptyMessage = emptyReason ?: "Nobody you follow has posted yet.",
                        nameOf = nameOf,
                        myAuthor = myAuthor,
                        onOpenThread = onOpenThread,
                        onOpenContact = onOpenContact,
                        onOpenOwnProfile = onSettings,
                        onRefresh = onSync,
                    )
                } else {
                    ThreadTab(
                        threads = gossipItems,
                        emptyMessage = emptyReason ?: "Nothing incidental has turned up yet.",
                        nameOf = nameOf,
                        myAuthor = myAuthor,
                        onOpenThread = onOpenThread,
                        onOpenContact = onOpenContact,
                        onOpenOwnProfile = onSettings,
                        onRefresh = onSync,
                    )
                }
            }
        }
    }
}

/** Free text with a name type-ahead, or — once picked — a removable chip; the two are mutually exclusive. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeSearchField(
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
                .filter { (_, name) -> name.label?.contains(searchText, ignoreCase = true) == true }
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
private fun ThreadTab(
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
                            // Unread is a colour, not a position — a dot, not a badge count.
                            if (preview.rootUnread) {
                                Box(
                                    Modifier.size(8.dp).clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                        .semantics { contentDescription = "Unread" },
                                )
                            }
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
                        Text(
                            thread.rootText.orEmpty(),
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
                // Shown oldest-first, like the conversation happened; the dot only ever marks the
                // newest one — it's one unread signal, not one per card.
                val newestTimestamp = replyPreview.replies.maxOf { it.timestamp }
                replyPreview.replies.forEach { snippet ->
                    Card(
                        Modifier.fillMaxWidth().padding(start = 24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (preview.previewUnread && snippet.timestamp == newestTimestamp) {
                                    Box(
                                        Modifier.size(8.dp).clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                            .semantics { contentDescription = "Unread" },
                                    )
                                }
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
                            Text(
                                "replied: ${snippet.text}",
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
                        if (preview.previewUnread) {
                            Box(
                                Modifier.size(8.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .semantics { contentDescription = "Unread" },
                            )
                        }
                        Text(
                            replySummaryText(replyPreview, preview.previewUnread, nameOf),
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

/** Default for the preview/test-friendly [HomeContent] parameters — an always-empty page. */
private fun emptyPagingFlow(): Flow<PagingData<ThreadSummary>> = flowOf(PagingData.empty())

/** Quicker than Compose's defaults — the search expand/collapse should feel snappy, not floaty. */
private const val SEARCH_ANIMATION_MILLIS = 120

@Preview(showBackground = true)
@Composable
private fun EmptyPreview() {
    DriftwoodTheme {
        HomeContent(state = HomeUiState.Empty, onOpenThread = {}, onCompose = {}, onSettings = {}, onSync = {})
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
        HomeContent(
            state = HomeUiState.Threads(),
            listeningThreads = flowOf(PagingData.from(listOf(listening))),
            gossipThreads = flowOf(PagingData.from(listOf(gossip))),
            onOpenThread = {},
            onCompose = {},
            onSettings = {},
            onSync = {},
        )
    }
}
