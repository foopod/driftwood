package com.jonoshields.driftwood.ui.thread

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jonoshields.driftwood.core.data.DirectoryRepository
import com.jonoshields.driftwood.core.data.MessageRepository
import com.jonoshields.driftwood.core.identity.IdentityStore
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.Message
import com.jonoshields.driftwood.core.store.DisplayName
import com.jonoshields.driftwood.core.store.NameResolver
import com.jonoshields.driftwood.core.model.MessageId
import com.jonoshields.driftwood.core.store.ThreadView
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ThreadUiState {
    data object Loading : ThreadUiState

    /** [pinned] is a property of the whole thread, not of any message in it. */
    data class Loaded(
        val thread: ThreadView,
        val pinned: Boolean,
        val names: Map<AuthorId, DisplayName> = emptyMap(),
        val followList: Set<AuthorId> = emptySet(),
        val blockedAuthors: Set<AuthorId> = emptySet(),
    ) : ThreadUiState {
        /** Falls back to the bare fingerprint for anyone with no name at all. */
        fun nameOf(author: AuthorId): DisplayName =
            names[author] ?: NameResolver.resolve(author, nickname = null, username = null)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ThreadViewModel @Inject constructor(
    private val repository: MessageRepository,
    private val directory: DirectoryRepository,
    identity: IdentityStore,
) : ViewModel() {

    private val rootId = MutableStateFlow<MessageId?>(null)

    /** So the contact actions never open for your own messages — there is nothing to do there. */
    val myAuthor: AuthorId? = runCatching { identity.publicKey() }.getOrNull()

    val uiState: StateFlow<ThreadUiState> = rootId
        .filterNotNull()
        // flatMapLatest so opening a different thread cancels the previous query.
        .flatMapLatest { id ->
            combine(
                repository.observeThread(id),
                repository.observeThreadPinned(id),
                directory.observeNames(),
                directory.observeFollowList(),
                repository.observeBlockedAuthors(),
            ) { thread, pinned, names, followList, blockedAuthors ->
                ThreadUiState.Loaded(thread, pinned, names, followList, blockedAuthors) as ThreadUiState
            }
                // Without this, switching threads flashes the previous thread's content for a frame.
                .onStart { emit(ThreadUiState.Loading) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThreadUiState.Loading)

    private val unreadIdsFlow = MutableStateFlow<Set<MessageId>>(emptySet())

    /**
     * A one-time snapshot of what was unread when this thread was opened, for scrolling straight
     * to it — not a live view. Deliberately not re-derived from [uiState]'s reactive `ThreadView`:
     * that stream's first emission can already reflect everything marked read below, since
     * `observeThread` and `markThreadRead` race. Reading this snapshot first, before marking read,
     * sidesteps that race entirely.
     */
    val unreadIds: StateFlow<Set<MessageId>> = unreadIdsFlow.asStateFlow()

    fun bind(id: MessageId) {
        rootId.value = id
        unreadIdsFlow.value = emptySet()
        viewModelScope.launch {
            unreadIdsFlow.value = repository.unreadMessageIds(id)
            // Opening a thread is what marks it read — not viewing the home list.
            repository.markThreadRead(id)
        }
    }

    /** Keys on the thread's root id, which exists even if the root message itself was pruned. */
    fun togglePin() {
        val id = rootId.value ?: return
        val current = (uiState.value as? ThreadUiState.Loaded)?.pinned ?: return
        viewModelScope.launch { repository.setThreadPinned(id, !current) }
    }

    fun setNickname(author: AuthorId, nickname: String) {
        viewModelScope.launch { directory.setNickname(author, nickname) }
    }

    fun toggleFollow(author: AuthorId) {
        val listening = (uiState.value as? ThreadUiState.Loaded)?.followList?.contains(author) ?: false
        viewModelScope.launch {
            if (listening) directory.unfollow(author) else directory.follow(author)
        }
    }

    fun block(author: AuthorId) {
        // Blocking someone you follow stops the following too, not just the display.
        viewModelScope.launch {
            repository.block(author)
            directory.unfollow(author)
        }
    }

    fun unblock(author: AuthorId) {
        viewModelScope.launch { repository.unblock(author) }
    }

    /** Fire-and-forget — the caller doesn't need to know when this lands, just that it will. Committed immediately, not on some later condition: a delete delayed until an on-screen timer or snackbar resolves would silently vanish if the screen is left first. */
    fun deleteMessage(id: MessageId) {
        viewModelScope.launch { repository.deleteMessage(id) }
    }

    /** "Undo" on a delete's snackbar — re-inserts the exact message that was just removed. */
    fun restoreMessage(message: Message) {
        viewModelScope.launch { repository.restoreMessage(message) }
    }

    /** Unlike [deleteMessage], the caller needs the result — the whole thread is gone on success, so the screen showing it has to leave. */
    suspend fun deleteThread(root: MessageId): Result<Unit> = repository.deleteThread(root)
}
