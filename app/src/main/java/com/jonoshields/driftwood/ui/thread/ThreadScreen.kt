package com.jonoshields.driftwood.ui.thread

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.Message
import com.jonoshields.driftwood.core.model.MessageId
import com.jonoshields.driftwood.core.store.DisplayName
import com.jonoshields.driftwood.core.store.NameResolver
import com.jonoshields.driftwood.core.store.RelativeTime
import com.jonoshields.driftwood.core.store.ThreadNode
import com.jonoshields.driftwood.ui.common.AuthorName
import com.jonoshields.driftwood.ui.common.ContactActionsContent
import com.jonoshields.driftwood.core.store.ThreadView

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
    // Not rememberSaveable: AuthorId isn't a saveable type; losing this on rotation is a fine trade.
    var selectedAuthor by remember { mutableStateOf<AuthorId?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            // Swapping in the contact panel below swaps this bar's identity too.
            TopAppBar(
                title = { Text(if (selectedAuthor != null) "User" else "Thread") },
                navigationIcon = {
                    TextButton(onClick = { if (selectedAuthor != null) selectedAuthor = null else onBack() }) {
                        Text(if (selectedAuthor != null) "Close" else "Back")
                    }
                },
                actions = {
                    if (state is ThreadUiState.Loaded && selectedAuthor == null) {
                        // One star for the whole conversation, including parts other people wrote.
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
            // Always replies to the thread itself — parent is the root when held, else null.
            if (state is ThreadUiState.Loaded && selectedAuthor == null) {
                ExtendedFloatingActionButton(
                    onClick = { onReply(state.thread.rootId, state.thread.root?.id) },
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
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
                    // Swaps in for the message list rather than floating over it as a dialog.
                    ContactActionsContent(
                        author = author,
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
                        modifier = Modifier.padding(padding),
                    )
                } else {
                    ThreadBody(
                        thread = state.thread,
                        starred = state.starred,
                        nameOf = state::nameOf,
                        myAuthor = myAuthor,
                        onReply = onReply,
                        // Your own messages have nothing to open — nothing to listen to, block, or name.
                        onAuthorClick = { a -> if (a != myAuthor) selectedAuthor = a },
                        onToggleStar = onToggleStar,
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
    myAuthor: AuthorId?,
    onReply: (MessageId, MessageId?) -> Unit,
    onAuthorClick: (AuthorId) -> Unit,
    onToggleStar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        // Extra bottom clearance so the reply FAB never sits over the last message.
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
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
                // Calm, not an error — a thread outliving its root is a normal end state.
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
                    isMine = root.body.author == myAuthor,
                    depth = 0,
                    detached = false,
                    starred = starred,
                    onReply = { onReply(thread.rootId, root.id) },
                    onAuthorClick = { onAuthorClick(root.body.author) },
                    onToggleStar = onToggleStar,
                )
            }
        }

        renderNodes(
            thread.replies,
            depth = 1,
            rootId = thread.rootId,
            nameOf = nameOf,
            myAuthor = myAuthor,
            starred = starred,
            onReply = onReply,
            onAuthorClick = onAuthorClick,
            onToggleStar = onToggleStar,
        )
    }
}

/** Flattens the tree into list items, carrying depth through as an indent. */
private fun LazyListScope.renderNodes(
    nodes: List<ThreadNode>,
    depth: Int,
    rootId: MessageId,
    nameOf: (AuthorId) -> DisplayName,
    myAuthor: AuthorId?,
    starred: Boolean,
    onReply: (MessageId, MessageId?) -> Unit,
    onAuthorClick: (AuthorId) -> Unit,
    onToggleStar: () -> Unit,
) {
    nodes.forEach { node ->
        item(key = node.message.id.toHex()) {
            MessageCard(
                message = node.message,
                name = nameOf(node.message.body.author),
                isMine = node.message.body.author == myAuthor,
                depth = depth,
                detached = node.detached,
                starred = starred,
                // Every message is a reply target, carrying both the root id and this message.
                onReply = { onReply(rootId, node.message.id) },
                onAuthorClick = { onAuthorClick(node.message.body.author) },
                onToggleStar = onToggleStar,
            )
        }
        renderNodes(node.children, depth + 1, rootId, nameOf, myAuthor, starred, onReply, onAuthorClick, onToggleStar)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageCard(
    message: Message,
    name: DisplayName,
    isMine: Boolean,
    depth: Int,
    detached: Boolean,
    starred: Boolean,
    onReply: () -> Unit,
    onAuthorClick: () -> Unit,
    onToggleStar: () -> Unit,
) {
    // Read once per card — a relative time drifting a few seconds stale isn't worth a ticker.
    val now = remember { System.currentTimeMillis() }

    // Not rememberSaveable — losing an open menu on rotation is a fine trade.
    var showMenu by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth().padding(start = (depth.coerceAtMost(5) * 14).dp)) {
        Box(Modifier.fillMaxWidth()) {
            Card(
                Modifier.fillMaxWidth()
                    // Long-press is the only whole-card gesture; other actions live on the author name/menu.
                    .combinedClickable(onClick = {}, onLongClick = { showMenu = true }),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (detached) {
                        Text(
                            "replying to a message not carried here",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        AuthorName(name, isMine = isMine, modifier = Modifier.clickable(onClick = onAuthorClick))
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
                // Favourites the whole thread, same as the top-bar star, offered from any message.
                DropdownMenuItem(
                    text = { Text(if (starred) "Unfavourite thread" else "Favourite thread") },
                    onClick = { showMenu = false; onToggleStar() },
                    modifier = Modifier.testTag("message-context-favourite"),
                )
            }
        }
    }
}
