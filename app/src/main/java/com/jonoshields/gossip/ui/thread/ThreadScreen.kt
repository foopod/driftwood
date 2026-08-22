package com.jonoshields.gossip.ui.thread

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.model.Message
import com.jonoshields.gossip.core.model.MessageId
import com.jonoshields.gossip.core.store.DisplayName
import com.jonoshields.gossip.core.store.NameResolver
import com.jonoshields.gossip.core.store.RelativeTime
import com.jonoshields.gossip.core.store.ThreadNode
import com.jonoshields.gossip.ui.common.AuthorName
import com.jonoshields.gossip.ui.common.ContactActionsContent
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
        myAuthor = viewModel.myAuthor,
        onReply = onReply,
        onBack = onBack,
        onToggleStar = viewModel::toggleStar,
        onSetNickname = viewModel::setNickname,
        onToggleListen = viewModel::toggleListen,
        onBlock = viewModel::block,
        onUnblock = viewModel::unblock,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThreadContent(
    state: ThreadUiState,
    myAuthor: AuthorId?,
    onReply: (MessageId, MessageId?) -> Unit,
    onBack: () -> Unit,
    onToggleStar: () -> Unit,
    onSetNickname: (AuthorId, String) -> Unit,
    onToggleListen: (AuthorId) -> Unit,
    onBlock: (AuthorId) -> Unit,
    onUnblock: (AuthorId) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Not rememberSaveable: AuthorId isn't a saveable type, and losing the open dialog on a
    // rotation is a fine trade for not writing a custom Saver for this.
    var selectedAuthor by remember { mutableStateOf<AuthorId?>(null) }

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
        floatingActionButton = {
            // Always replies to the thread itself — parent is the root when it's held, or
            // null when it isn't (the assembler treats those identically), the same target
            // every per-message "Reply" button used to offer from the root's own card.
            if (state is ThreadUiState.Loaded && selectedAuthor == null) {
                ExtendedFloatingActionButton(
                    onClick = { onReply(state.thread.rootId, state.thread.root?.id) },
                    modifier = Modifier.testTag("thread-reply-fab"),
                ) {
                    Text("Reply")
                }
            }
        },
    ) { padding ->
        when (state) {
            ThreadUiState.Loading -> Unit
            is ThreadUiState.Loaded -> {
                val author = selectedAuthor
                if (author != null) {
                    // Swaps in for the message list rather than floating over it as a
                    // dialog — the same plain content-swap every screen here already uses,
                    // and one that doesn't need a separate Android window to test.
                    ContactActionsContent(
                        displayName = state.nameOf(author),
                        isListening = author in state.listenScope,
                        isBlocked = author in state.blockedAuthors,
                        onSetNickname = { name -> onSetNickname(author, name) },
                        onToggleListen = { onToggleListen(author) },
                        onBlock = {
                            onBlock(author)
                            selectedAuthor = null
                        },
                        onUnblock = { onUnblock(author) },
                        onClose = { selectedAuthor = null },
                        modifier = Modifier.padding(padding),
                    )
                } else {
                    ThreadBody(
                        thread = state.thread,
                        starred = state.starred,
                        nameOf = state::nameOf,
                        onReply = onReply,
                        // Your own messages have nothing to listen to, block, or name —
                        // there is nothing to open for tapping yourself.
                        onAuthorClick = { a -> if (a != myAuthor) selectedAuthor = a },
                        modifier = Modifier.padding(padding),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadBody(
    thread: ThreadView,
    starred: Boolean,
    nameOf: (AuthorId) -> DisplayName,
    onReply: (MessageId, MessageId?) -> Unit,
    onAuthorClick: (AuthorId) -> Unit,
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
                // The floating Reply button covers replying to the thread itself here too
                // (parent null, same as a root's own id would resolve to).
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
                    name = nameOf(root.body.author),
                    depth = 0,
                    detached = false,
                    onReply = { onReply(thread.rootId, root.id) },
                    onAuthorClick = { onAuthorClick(root.body.author) },
                )
            }
        }

        renderNodes(thread.replies, depth = 1, rootId = thread.rootId, nameOf = nameOf, onReply = onReply, onAuthorClick = onAuthorClick)
    }
}

/** Flattens the tree into list items, carrying depth through as an indent. */
private fun LazyListScope.renderNodes(
    nodes: List<ThreadNode>,
    depth: Int,
    rootId: MessageId,
    nameOf: (AuthorId) -> DisplayName,
    onReply: (MessageId, MessageId?) -> Unit,
    onAuthorClick: (AuthorId) -> Unit,
) {
    nodes.forEach { node ->
        item(key = node.message.id.toHex()) {
            MessageCard(
                message = node.message,
                name = nameOf(node.message.body.author),
                depth = depth,
                detached = node.detached,
                // Every message is a reply target, and the reply carries both the thread's
                // root id and this specific message as its parent.
                onReply = { onReply(rootId, node.message.id) },
                onAuthorClick = { onAuthorClick(node.message.body.author) },
            )
        }
        renderNodes(node.children, depth + 1, rootId, nameOf, onReply, onAuthorClick)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageCard(
    message: Message,
    name: DisplayName,
    depth: Int,
    detached: Boolean,
    onReply: () -> Unit,
    onAuthorClick: () -> Unit,
) {
    // Read once per card rather than tied to a recomposition key — a relative time drifting
    // a few seconds stale while this card sits on screen is not worth chasing with a ticker.
    val now = remember { System.currentTimeMillis() }

    // Not rememberSaveable: same reasoning as ThreadContent's selectedAuthor — losing an
    // open menu on rotation is a fine trade.
    var showMenu by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth().padding(start = (depth.coerceAtMost(5) * 14).dp)) {
        Box(Modifier.fillMaxWidth()) {
            Card(
                Modifier.fillMaxWidth()
                    // Long-press is the only whole-card gesture; a plain tap does nothing
                    // here; specific actions live on the author name (tap) and this menu.
                    .combinedClickable(onClick = {}, onLongClick = { showMenu = true }),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (detached) {
                        // The quiet marker from plan.md §6 — never styled as a failure.
                        Text(
                            "replying to a message not carried here",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        AuthorName(name, modifier = Modifier.clickable(onClick = onAuthorClick))
                        Text(
                            RelativeTime.describe(message.body.timestampMillis, now),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(message.body.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Reply") },
                    onClick = { showMenu = false; onReply() },
                    modifier = Modifier.testTag("message-context-reply"),
                )
                DropdownMenuItem(
                    text = { Text("User Profile") },
                    onClick = { showMenu = false; onAuthorClick() },
                    modifier = Modifier.testTag("message-context-profile"),
                )
            }
        }
    }
}
