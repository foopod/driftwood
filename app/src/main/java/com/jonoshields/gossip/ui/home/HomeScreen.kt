package com.jonoshields.gossip.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.model.MessageId
import com.jonoshields.gossip.core.store.DisplayName
import com.jonoshields.gossip.core.store.NameResolver
import com.jonoshields.gossip.theme.GossipTheme
import com.jonoshields.gossip.ui.common.AuthorName

@Composable
fun HomeScreen(
    onOpenThread: (MessageId) -> Unit,
    onCompose: () -> Unit,
    onSettings: () -> Unit,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeContent(state, onOpenThread, onCompose, onSettings, onSync, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeContent(
    state: HomeUiState,
    onOpenThread: (MessageId) -> Unit,
    onCompose: () -> Unit,
    onSettings: () -> Unit,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 0 = Listening, 1 = Gossip (plan.md §6's two tabs). Not tied to HomeUiState, since which
    // tab you're looking at should survive the thread list itself reloading underneath it.
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Gossip") },
                actions = {
                    TextButton(onClick = onSync) { Text("Sync") }
                    TextButton(onClick = onSettings) { Text("Settings") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCompose) { Text("+", style = MaterialTheme.typography.headlineMedium) }
        },
    ) { padding ->
        when (state) {
            HomeUiState.Loading -> Unit
            HomeUiState.Empty -> EmptyState(Modifier.padding(padding))
            is HomeUiState.Threads -> Column(Modifier.padding(padding).fillMaxSize()) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Listening (${state.listening.size})") },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Gossip (${state.gossip.size})") },
                    )
                }
                // Kept out of the classifier, which stays pure and name-agnostic: this is
                // the one fallback every other name lookup in the app already uses.
                val nameOf: (AuthorId) -> DisplayName = { author ->
                    state.names[author] ?: NameResolver.resolve(author, nickname = null, username = null)
                }
                if (selectedTab == 0) {
                    ThreadTab(
                        threads = state.listening,
                        emptyMessage = "Nobody you listen to has posted yet.",
                        nameOf = nameOf,
                        onOpenThread = onOpenThread,
                    )
                } else {
                    ThreadTab(
                        threads = state.gossip,
                        emptyMessage = "Nothing incidental has turned up yet.",
                        nameOf = nameOf,
                        onOpenThread = onOpenThread,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadTab(
    threads: List<ThreadSummary>,
    emptyMessage: String,
    nameOf: (AuthorId) -> DisplayName,
    onOpenThread: (MessageId) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (threads.isEmpty()) {
            item {
                Text(
                    emptyMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(threads, key = { it.rootId.toHex() }) { thread ->
                ThreadRow(thread, nameOf, onClick = { onOpenThread(thread.rootId) })
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
        // Encouraging, not barren (plan.md §6).
        Text("Your network is quiet", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Post something, then sync with someone to begin.",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun ThreadRow(thread: ThreadSummary, nameOf: (AuthorId) -> DisplayName, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val rootAuthor = thread.rootAuthor
            if (rootAuthor == null) {
                // The start of this conversation is genuinely gone. Say so plainly rather
                // than showing a reply as though it were the opening line.
                Text(
                    "the start of this conversation isn't carried here",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                AuthorName(nameOf(rootAuthor))
                Text(thread.rootText.orEmpty(), style = MaterialTheme.typography.bodyLarge, maxLines = 3)
            }

            val listenedAuthor = thread.latestListenedAuthor
            if (listenedAuthor != null) {
                // Root plus the reply, not the reply alone: what's worth seeing is *both*
                // that this conversation exists and that someone you follow answered it.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AuthorName(nameOf(listenedAuthor))
                    Text(
                        "replied: ${thread.latestListenedText.orEmpty()}",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                    )
                }
            }

            Text(
                if (thread.messageCount == 1) "1 message" else "${thread.messageCount} messages",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun EmptyPreview() {
    GossipTheme { HomeContent(HomeUiState.Empty, {}, {}, {}, {}) }
}

@Preview(showBackground = true)
@Composable
private fun ThreadsPreview() {
    val author = AuthorId.of(ByteArray(32) { 9 })
    GossipTheme {
        HomeContent(
            HomeUiState.Threads(
                listening = listOf(
                    ThreadSummary(
                        rootId = MessageId.of(ByteArray(32) { 1 }),
                        rootAuthor = author,
                        rootText = "Trying out this gossip thing.",
                        latestListenedAuthor = author,
                        latestListenedText = "Working nicely so far.",
                        messageCount = 2,
                        newestTimestamp = 1,
                    ),
                ),
                gossip = listOf(
                    ThreadSummary(
                        rootId = MessageId.of(ByteArray(32) { 2 }),
                        rootAuthor = null,
                        rootText = null,
                        latestListenedAuthor = null,
                        latestListenedText = null,
                        messageCount = 4,
                        newestTimestamp = 0,
                    ),
                ),
            ),
            {}, {}, {}, {},
        )
    }
}
