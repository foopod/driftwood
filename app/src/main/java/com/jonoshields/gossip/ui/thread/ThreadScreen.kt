package com.jonoshields.gossip.ui.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jonoshields.gossip.core.model.Message
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
        onToggleStar = viewModel::toggleStar,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThreadContent(
    state: ThreadUiState,
    onReply: (MessageId, MessageId?) -> Unit,
    onBack: () -> Unit,
    onToggleStar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Thread") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    if (state is ThreadUiState.Loaded) {
                        // One star for the whole conversation: keeping a thread means
                        // keeping all of it, including the parts other people wrote.
                        TextButton(
                            onClick = onToggleStar,
                            modifier = Modifier.semantics {
                                contentDescription =
                                    if (state.starred) "Unstar this thread" else "Star this thread"
                            },
                        ) {
                            Text(
                                text = if (state.starred) "★" else "☆",
                                style = MaterialTheme.typography.headlineSmall,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            ThreadUiState.Loading -> Unit
            is ThreadUiState.Loaded -> ThreadBody(
                thread = state.thread,
                starred = state.starred,
                onReply = onReply,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun ThreadBody(
    thread: ThreadView,
    starred: Boolean,
    onReply: (MessageId, MessageId?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (starred) {
            item {
                Text(
                    "Starred — this whole thread is kept, including replies that arrive later.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        item {
            val root = thread.root
            if (root == null) {
                // Calm, not an error: a thread outliving its root is a normal end state.
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "The start of this conversation isn't carried here.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "You can still reply to it — a reply only needs the thread's id.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                MessageCard(
                    message = root,
                    depth = 0,
                    detached = false,
                    label = "opens the thread",
                    onReply = { onReply(thread.rootId, root.id) },
                )
            }
        }

        // Replying to the thread itself, when you want to answer the conversation rather
        // than any particular message in it.
        item {
            TextButton(onClick = { onReply(thread.rootId, thread.root?.id) }) {
                Text("Reply to the thread")
            }
        }

        renderNodes(thread.replies, depth = 1, rootId = thread.rootId, onReply = onReply)
    }
}

/** Flattens the tree into list items, carrying depth through as an indent. */
private fun LazyListScope.renderNodes(
    nodes: List<ThreadNode>,
    depth: Int,
    rootId: MessageId,
    onReply: (MessageId, MessageId?) -> Unit,
) {
    nodes.forEach { node ->
        item(key = node.message.id.toHex()) {
            MessageCard(
                message = node.message,
                depth = depth,
                detached = node.detached,
                label = null,
                // Every message is a reply target, and the reply carries both the thread's
                // root id and this specific message as its parent.
                onReply = { onReply(rootId, node.message.id) },
            )
        }
        renderNodes(node.children, depth + 1, rootId, onReply)
    }
}

@Composable
private fun MessageCard(
    message: Message,
    depth: Int,
    detached: Boolean,
    label: String?,
    onReply: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = (depth.coerceAtMost(5) * 14).dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (detached) {
                    // The quiet marker from plan.md §6 — never styled as a failure.
                    Text(
                        "replying to a message not carried here",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (label != null) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(message.body.text, style = MaterialTheme.typography.bodyLarge)

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onReply) { Text("Reply", textAlign = TextAlign.End) }
                }
            }
        }
    }
}
