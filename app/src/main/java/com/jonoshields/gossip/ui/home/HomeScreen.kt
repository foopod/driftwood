package com.jonoshields.gossip.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jonoshields.gossip.core.model.MessageId
import com.jonoshields.gossip.theme.GossipTheme

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
            FloatingActionButton(onClick = onCompose) { Text("＋", style = MaterialTheme.typography.headlineMedium) }
        },
    ) { padding ->
        when (state) {
            HomeUiState.Loading -> Unit
            HomeUiState.Empty -> EmptyState(Modifier.padding(padding))
            is HomeUiState.Threads -> LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.threads, key = { it.rootId.toHex() }) { thread ->
                    ThreadRow(thread, onClick = { onOpenThread(thread.rootId) })
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
        // Encouraging, not barren (plan.md §6).
        Text("Your network is quiet", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Post something, then sync with someone to begin.",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun ThreadRow(thread: ThreadSummary, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!thread.rootHeld) {
                // The start of this conversation is genuinely gone. Say so plainly rather
                // than showing a reply as though it were the opening line.
                Text(
                    "the start of this conversation isn't carried here",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(thread.opening, style = MaterialTheme.typography.bodyLarge, maxLines = 3)
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
    GossipTheme {
        HomeContent(
            HomeUiState.Threads(
                listOf(
                    ThreadSummary(MessageId.of(ByteArray(32) { 1 }), "Trying out this gossip thing.", 1, 0, true),
                    ThreadSummary(MessageId.of(ByteArray(32) { 2 }), "…and it kept going from there.", 4, 0, false),
                )
            ),
            {}, {}, {}, {},
        )
    }
}
