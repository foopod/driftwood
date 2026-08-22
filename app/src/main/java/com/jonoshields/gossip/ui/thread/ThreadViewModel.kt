package com.jonoshields.gossip.ui.thread

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jonoshields.gossip.core.data.DirectoryRepository
import com.jonoshields.gossip.core.data.MessageRepository
import com.jonoshields.gossip.core.identity.IdentityStore
import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.store.DisplayName
import com.jonoshields.gossip.core.store.NameResolver
import com.jonoshields.gossip.core.model.MessageId
import com.jonoshields.gossip.core.store.ThreadView
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ThreadUiState {
    data object Loading : ThreadUiState

    /** [starred] is a property of the whole thread, not of any message in it. */
    data class Loaded(
        val thread: ThreadView,
        val starred: Boolean,
        val names: Map<AuthorId, DisplayName> = emptyMap(),
        val listenScope: Set<AuthorId> = emptySet(),
        val blockedAuthors: Set<AuthorId> = emptySet(),
    ) : ThreadUiState {
        /**
         * Falls back to the bare fingerprint for anyone with no name at all, so a message
         * is never attributed to nothing.
         */
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
        // flatMapLatest so opening a different thread cancels the previous query rather
        // than leaving two observers racing to set the same state.
        .flatMapLatest { id ->
            combine(
                repository.observeThread(id),
                repository.observeThreadFavourite(id),
                directory.observeNames(),
                directory.observeListenScope(),
                repository.observeBlockedAuthors(),
            ) { thread, starred, names, listenScope, blockedAuthors ->
                ThreadUiState.Loaded(thread, starred, names, listenScope, blockedAuthors)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThreadUiState.Loading)

    fun bind(id: MessageId) {
        rootId.value = id
    }

    /**
     * Starring keys on the thread's root id, which exists whether or not the root message
     * is held — so the star works even on a thread whose opening has been pruned away.
     */
    fun toggleStar() {
        val id = rootId.value ?: return
        val current = (uiState.value as? ThreadUiState.Loaded)?.starred ?: return
        viewModelScope.launch { repository.setThreadFavourite(id, !current) }
    }

    fun setNickname(author: AuthorId, nickname: String) {
        viewModelScope.launch { directory.setNickname(author, nickname) }
    }

    fun toggleListen(author: AuthorId) {
        val listening = (uiState.value as? ThreadUiState.Loaded)?.listenScope?.contains(author) ?: false
        viewModelScope.launch {
            if (listening) directory.stopListening(author) else directory.listenTo(author)
        }
    }

    fun block(author: AuthorId) {
        // Data must never disagree with the UI's "not both" rule (plan.md §4): blocking
        // someone you listen to stops the listening too, not just the display.
        viewModelScope.launch {
            repository.block(author)
            directory.stopListening(author)
        }
    }

    fun unblock(author: AuthorId) {
        viewModelScope.launch { repository.unblock(author) }
    }
}
