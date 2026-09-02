package com.jonoshields.driftwood.ui.thread

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.Message
import com.jonoshields.driftwood.core.model.MessageId
import com.jonoshields.driftwood.ui.common.LinkifiedText
import com.jonoshields.driftwood.core.store.DisplayName
import com.jonoshields.driftwood.core.store.NameResolver
import com.jonoshields.driftwood.core.store.RelativeTime
import com.jonoshields.driftwood.core.store.ThreadNode
import com.jonoshields.driftwood.ui.common.AuthorName
import com.jonoshields.driftwood.ui.common.ContactActionsContent
import com.jonoshields.driftwood.ui.common.copyToClipboard
import com.jonoshields.driftwood.core.store.ThreadView
import kotlinx.coroutines.launch

@Composable
fun ThreadScreen(
    rootId: MessageId,
    onReply: (root: MessageId, parent: MessageId?) -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ThreadViewModel = hiltViewModel(),
) {
    LaunchedEffect(rootId) { viewModel.bind(rootId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val unreadIds by viewModel.unreadIds.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    ThreadContent(
        state = state,
        unreadIds = unreadIds,
        myAuthor = viewModel.myAuthor,
        onReply = onReply,
        onBack = onBack,
        onSettings = onSettings,
        onTogglePin = viewModel::togglePin,
        onSetNickname = viewModel::setNickname,
        onToggleFollow = viewModel::toggleFollow,
        onBlock = viewModel::block,
        onUnblock = viewModel::unblock,
        onDeleteMessage = viewModel::deleteMessage,
        onRestoreMessage = viewModel::restoreMessage,
        // Nothing left to look at once the whole thread's gone — leave the screen on success.
        onDeleteThread = { root -> scope.launch { if (viewModel.deleteThread(root).isSuccess) onBack() } },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThreadContent(
    state: ThreadUiState,
    unreadIds: Set<MessageId> = emptySet(),
    myAuthor: AuthorId?,
    onReply: (MessageId, MessageId?) -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onTogglePin: () -> Unit,
    onSetNickname: (AuthorId, String) -> Unit,
    onToggleFollow: (AuthorId) -> Unit,
    onBlock: (AuthorId) -> Unit,
    onUnblock: (AuthorId) -> Unit,
    onDeleteMessage: (MessageId) -> Unit,
    onRestoreMessage: (Message) -> Unit,
    onDeleteThread: (MessageId) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Not rememberSaveable: AuthorId isn't a saveable type; losing this on rotation is a fine trade.
    var selectedAuthor by remember { mutableStateOf<AuthorId?>(null) }
    // Hosted here so it renders above the FAB via Scaffold's own slot, not floated ad hoc by a child.
    val snackbarHostState = remember { SnackbarHostState() }
    // Hoisted so both the message list and the back-to-top FAB (in Scaffold's own slot) share it.
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val scrolledDown by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

    // unreadIds starts empty and is set once, shortly after bind() — this fires a second time
    // once the real snapshot lands, and is a no-op on every emission after that (already scrolled,
    // and the index computed against a since-changed set of blocked authors etc. is deliberately
    // not re-derived once the thread is open — this is "jump to what was new", not a live tracker).
    if (state is ThreadUiState.Loaded) {
        LaunchedEffect(state.thread.rootId, unreadIds) {
            if (unreadIds.isNotEmpty()) {
                findFirstUnreadIndex(state.thread, state.pinned, state.blockedAuthors, unreadIds)
                    ?.let { index -> listState.scrollToItem(index) }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        // One pin for the whole thread, including parts other people wrote.
                        IconButton(
                            onClick = onTogglePin,
                            modifier = Modifier.semantics {
                                contentDescription =
                                    if (state.pinned) "Unpin this thread" else "Pin this thread"
                            },
                        ) {
                            Icon(
                                imageVector = if (state.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                contentDescription = null,
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (state is ThreadUiState.Loaded && selectedAuthor == null) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimatedVisibility(visible = scrolledDown) {
                        FloatingActionButton(
                            onClick = { scope.launch { listState.scrollToItem(0) } },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.testTag("thread-back-to-top-fab"),
                        ) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Back to top")
                        }
                    }
                    // Always replies to the thread itself — parent is the root when held, else null.
                    ExtendedFloatingActionButton(
                        onClick = { onReply(state.thread.rootId, state.thread.root?.id) },
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary,
                        modifier = Modifier.testTag("thread-reply-fab"),
                    ) {
                        Text("Reply")
                    }
                }
            }
        },
    ) { padding ->
        when (state) {
            ThreadUiState.Loading -> {
                Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is ThreadUiState.Loaded -> {
                val author = selectedAuthor
                if (author != null) {
                    // Swaps in for the message list rather than floating over it as a dialog.
                    ContactActionsContent(
                        author = author,
                        displayName = state.nameOf(author),
                        isFollowing = author in state.followList,
                        isBlocked = author in state.blockedAuthors,
                        onSetNickname = { name -> onSetNickname(author, name) },
                        onToggleFollow = { onToggleFollow(author) },
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
                        pinned = state.pinned,
                        blockedAuthors = state.blockedAuthors,
                        nameOf = state::nameOf,
                        myAuthor = myAuthor,
                        onReply = onReply,
                        // Your own name goes to Settings, same as tapping it from Home — everyone
                        // else's opens the in-place contact actions instead.
                        onAuthorClick = { a -> if (a == myAuthor) onSettings() else selectedAuthor = a },
                        onTogglePin = onTogglePin,
                        onDeleteMessage = onDeleteMessage,
                        onRestoreMessage = onRestoreMessage,
                        onDeleteThread = onDeleteThread,
                        snackbarHostState = snackbarHostState,
                        listState = listState,
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
    pinned: Boolean,
    blockedAuthors: Set<AuthorId>,
    nameOf: (AuthorId) -> DisplayName,
    myAuthor: AuthorId?,
    onReply: (MessageId, MessageId?) -> Unit,
    onAuthorClick: (AuthorId) -> Unit,
    onTogglePin: () -> Unit,
    onDeleteMessage: (MessageId) -> Unit,
    onRestoreMessage: (Message) -> Unit,
    onDeleteThread: (MessageId) -> Unit,
    snackbarHostState: SnackbarHostState,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var showRootDeleteDialog by remember { mutableStateOf(false) }

    // A tap on a leaf (or a non-root message, which never gets the whole-thread choice) deletes
    // it right away — never delayed on anything screen-scoped, since a coroutine waiting on this
    // composable's own snackbar dies the instant the screen is left, silently losing the delete
    // along with it. "Undo" is a real restore afterward, not a late commit.
    fun requestSingleDelete(message: Message) {
        onDeleteMessage(message.id)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Message deleted",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onRestoreMessage(message)
        }
    }

    if (showRootDeleteDialog) {
        // Explicit two-choice confirm already, so both options commit immediately — no separate
        // undo cushion stacked on top of a choice that was itself already the confirmation.
        RootDeleteDialog(
            messageCount = 1 + countNodes(thread.replies),
            onDismiss = { showRootDeleteDialog = false },
            onDeleteJustThisMessage = { showRootDeleteDialog = false; onDeleteMessage(thread.rootId) },
            onDeleteWholeThread = { showRootDeleteDialog = false; onDeleteThread(thread.rootId) },
        )
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        // Extra bottom clearance so the reply FAB never sits over the last message.
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (pinned) {
            item {
                Text(
                    "Pinned — this whole thread is kept, including replies that arrive later.",
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
                            "The start of this thread isn't carried here.",
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
                    pinned = pinned,
                    canDelete = thread.rootUnsent && root.body.author == myAuthor,
                    onReply = { onReply(thread.rootId, root.id) },
                    onAuthorClick = { onAuthorClick(root.body.author) },
                    onTogglePin = onTogglePin,
                    // Only the root ever offers the whole-thread choice, and only when there's
                    // something under it — a bare root just deletes itself, cushioned like any leaf.
                    onDeleteRequested = {
                        if (thread.replies.isNotEmpty()) showRootDeleteDialog = true
                        else requestSingleDelete(root)
                    },
                )
            }
        }

        renderNodes(
            thread.replies,
            depth = 1,
            rootId = thread.rootId,
            blockedAuthors = blockedAuthors,
            nameOf = nameOf,
            myAuthor = myAuthor,
            pinned = pinned,
            onReply = onReply,
            onAuthorClick = onAuthorClick,
            onTogglePin = onTogglePin,
            onDeleteRequested = ::requestSingleDelete,
        )
    }
}

/** Total node count in a subtree, root not included — used only for the whole-thread delete dialog's copy. */
private fun countNodes(nodes: List<ThreadNode>): Int = nodes.sumOf { 1 + countNodes(it.children) }

/**
 * The flattened `LazyColumn` index of the first unread message, mirroring exactly what
 * [renderNodes] actually emits: one optional leading "pinned" banner item, one item for the root
 * slot (held or not), then a depth-first walk of the reply tree that skips blocked-author nodes
 * entirely, same as the real render does. Returns null if nothing in [unreadIds] is rendered.
 */
private fun findFirstUnreadIndex(
    thread: ThreadView,
    pinned: Boolean,
    blockedAuthors: Set<AuthorId>,
    unreadIds: Set<MessageId>,
): Int? {
    var index = if (pinned) 1 else 0
    var found: Int? = null

    val rootId = thread.root?.id
    if (rootId != null && rootId in unreadIds) found = index
    index++

    fun walk(nodes: List<ThreadNode>) {
        for (node in nodes) {
            if (found != null) return
            if (node.message.body.author !in blockedAuthors) {
                if (node.message.id in unreadIds) found = index
                index++
            }
            walk(node.children)
            if (found != null) return
        }
    }
    walk(thread.replies)
    return found
}

@Composable
private fun RootDeleteDialog(
    messageCount: Int,
    onDismiss: () -> Unit,
    onDeleteJustThisMessage: () -> Unit,
    onDeleteWholeThread: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete this thread?") },
        text = {
            Text(
                "This hasn't synced to anyone yet, so deleting it only affects this device — " +
                    "nothing to undo elsewhere.",
            )
        },
        confirmButton = {
            TextButton(onClick = onDeleteWholeThread) { Text("Delete whole thread ($messageCount messages)") }
        },
        dismissButton = {
            TextButton(onClick = onDeleteJustThisMessage) { Text("Delete just this message") }
        },
    )
}

/** Flattens the tree into list items, carrying depth through as an indent. */
private fun LazyListScope.renderNodes(
    nodes: List<ThreadNode>,
    depth: Int,
    rootId: MessageId,
    blockedAuthors: Set<AuthorId>,
    nameOf: (AuthorId) -> DisplayName,
    myAuthor: AuthorId?,
    pinned: Boolean,
    onReply: (MessageId, MessageId?) -> Unit,
    onAuthorClick: (AuthorId) -> Unit,
    onTogglePin: () -> Unit,
    onDeleteRequested: (Message) -> Unit,
) {
    nodes.forEach { node ->
        // A blocked author's message is skipped entirely — not shown, not even marked — but its
        // children still render (same "context missing, replies carry on" idiom as `detached`).
        if (node.message.body.author !in blockedAuthors) {
            item(key = node.message.id.toHex()) {
                MessageCard(
                    message = node.message,
                    name = nameOf(node.message.body.author),
                    isMine = node.message.body.author == myAuthor,
                    depth = depth,
                    detached = node.detached,
                    pinned = pinned,
                    canDelete = node.unsent && node.message.body.author == myAuthor,
                    // Every message is a reply target, carrying both the root id and this message.
                    onReply = { onReply(rootId, node.message.id) },
                    onAuthorClick = { onAuthorClick(node.message.body.author) },
                    onTogglePin = onTogglePin,
                    // Non-root messages never get the whole-thread choice, even with local
                    // replies under them — just this message, same as any other leaf delete.
                    onDeleteRequested = { onDeleteRequested(node.message) },
                )
            }
        }
        renderNodes(node.children, depth + 1, rootId, blockedAuthors, nameOf, myAuthor, pinned, onReply, onAuthorClick, onTogglePin, onDeleteRequested)
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
    pinned: Boolean,
    canDelete: Boolean,
    onReply: () -> Unit,
    onAuthorClick: () -> Unit,
    onTogglePin: () -> Unit,
    onDeleteRequested: () -> Unit,
) {
    // Read once per card — a relative time drifting a few seconds stale isn't worth a ticker.
    val now = remember { System.currentTimeMillis() }

    // Not rememberSaveable — losing an open menu on rotation is a fine trade.
    var showMenu by remember { mutableStateOf(false) }
    var showAbsoluteTime by remember { mutableStateOf(false) }
    val context = LocalContext.current

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
                            if (showAbsoluteTime) {
                                RelativeTime.absolute(message.body.timestampMillis)
                            } else {
                                RelativeTime.describe(message.body.timestampMillis, now)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { showAbsoluteTime = !showAbsoluteTime },
                        )
                    }
                    LinkifiedText(message.body.text, style = MaterialTheme.typography.bodyLarge)
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
                DropdownMenuItem(
                    text = { Text("Copy text") },
                    onClick = { showMenu = false; copyToClipboard(context, "Message", message.body.text) },
                    modifier = Modifier.testTag("message-context-copy"),
                )
                DropdownMenuItem(
                    text = { Text("Share") },
                    onClick = { showMenu = false; shareText(context, message.body.text) },
                    modifier = Modifier.testTag("message-context-share"),
                )
                // Pins the whole thread, same as the top-bar pin, offered from any message.
                DropdownMenuItem(
                    text = { Text(if (pinned) "Unpin thread" else "Pin thread") },
                    onClick = { showMenu = false; onTogglePin() },
                    modifier = Modifier.testTag("message-context-pin"),
                )
                // Only ever present while this message is still unsent — see MessageEntity.unsent.
                if (canDelete) {
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = { showMenu = false; onDeleteRequested() },
                        modifier = Modifier.testTag("message-context-delete"),
                    )
                }
            }
        }
    }
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, null))
}
