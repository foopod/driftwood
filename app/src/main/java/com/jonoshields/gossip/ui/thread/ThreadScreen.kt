package com.jonoshields.gossip.ui.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jonoshields.gossip.core.model.MessageId
import com.jonoshields.gossip.core.store.ThreadNode
import com.jonoshields.gossip.core.store.ThreadView

@Composable
fun ThreadScreen(
    rootId: MessageId,
    onReply: (root: MessageId, parent: MessageId?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ThreadViewModel = hiltViewModel(),
) {
    LaunchedEffect(rootId) { viewModel.bind(rootId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ThreadContent(
        state = state,
        onReply = onReply,
        onBack = onBack,
        onFavourite = viewModel::setFavourite,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThreadContent(
    state: ThreadUiState,
    onReply: (MessageId, MessageId?) -> Unit,
    onBack: () -> Unit,
    onFavourite: (MessageId, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Thread") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
        floatingActionButton = {
            if (state is ThreadUiState.Loaded) {
                FloatingActionButton(onClick = { onReply(state.thread.rootId, state.thread.root?.id) }) {
                    Text("Reply", Modifier.padding(horizontal = 12.dp))
                }
            }
        },
    ) { padding ->
        when (state) {
            ThreadUiState.Loading -> Unit
            is ThreadUiState.Loaded -> ThreadBody(state.thread, onFavourite, Modifier.padding(padding))
        }
    }
}

@Composable
private fun ThreadBody(
    thread: ThreadView,
    onFavourite: (MessageId, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            val root = thread.root
            if (root == null) {
                // Calm, not an error: a thread outliving its root is a normal end state.
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "The start of this conversation isn't carried here.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            thread.rootId.toHex().take(16) + "…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                MessageCard(root.body.text, depth = 0, detached = false) {
                    onFavourite(root.id, true)
                }
            }
        }

        renderNodes(thread.replies, depth = 1, onFavourite = onFavourite)
    }
}

/** Flattens the tree into list items, carrying depth through as an indent. */
private fun androidx.compose.foundation.lazy.LazyListScope.renderNodes(
    nodes: List<ThreadNode>,
    depth: Int,
    onFavourite: (MessageId, Boolean) -> Unit,
) {
    nodes.forEach { node ->
        item(key = node.message.id.toHex()) {
            MessageCard(node.message.body.text, depth = depth, detached = node.detached) {
                onFavourite(node.message.id, true)
            }
        }
        renderNodes(node.children, depth + 1, onFavourite)
    }
}

@Composable
private fun MessageCard(
    text: String,
    depth: Int,
    detached: Boolean,
    onFavourite: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = (depth * 16).dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (detached) {
                    // The quiet marker from plan.md §6 — never styled as a failure.
                    Text(
                        "replying to a message not carried here",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(text, style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = onFavourite) { Text("Favourite") }
            }
        }
    }
}
