package com.jonoshields.gossip.ui.contact

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jonoshields.gossip.core.data.DirectoryRepository
import com.jonoshields.gossip.core.data.MessageRepository
import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.store.DisplayName
import com.jonoshields.gossip.core.store.NameResolver
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

sealed interface ContactUiState {
    data object Loading : ContactUiState

    data class Loaded(
        val displayName: DisplayName,
        val isListening: Boolean,
        val isBlocked: Boolean,
    ) : ContactUiState
}

/**
 * The one-author counterpart of `ThreadViewModel`'s in-place contact actions — reached as a
 * real screen from the listening list rather than by tapping a name in a thread, so it takes
 * its author from navigation instead of a selection state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ContactViewModel @Inject constructor(
    private val directory: DirectoryRepository,
    private val repository: MessageRepository,
) : ViewModel() {

    private val author = MutableStateFlow<AuthorId?>(null)

    val uiState: StateFlow<ContactUiState> = author
        .filterNotNull()
        // flatMapLatest so re-binding to a different author cancels the previous observer.
        .flatMapLatest { a ->
            combine(
                directory.observeNames(),
                directory.observeListenScope(),
                repository.observeBlockedAuthors(),
            ) { names, listenScope, blockedAuthors ->
                ContactUiState.Loaded(
                    displayName = names[a] ?: NameResolver.resolve(a, nickname = null, username = null),
                    isListening = a in listenScope,
                    isBlocked = a in blockedAuthors,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContactUiState.Loading)

    fun bind(author: AuthorId) {
        this.author.value = author
    }

    fun setNickname(nickname: String) {
        val a = author.value ?: return
        viewModelScope.launch { directory.setNickname(a, nickname) }
    }

    fun toggleListen() {
        val a = author.value ?: return
        val listening = (uiState.value as? ContactUiState.Loaded)?.isListening ?: false
        viewModelScope.launch {
            if (listening) directory.stopListening(a) else directory.listenTo(a)
        }
    }

    fun block() {
        val a = author.value ?: return
        // Data must never disagree with the UI's "not both" rule (plan.md §4): blocking
        // someone you listen to stops the listening too, not just the display.
        viewModelScope.launch {
            repository.block(a)
            directory.stopListening(a)
        }
    }

    fun unblock() {
        val a = author.value ?: return
        viewModelScope.launch { repository.unblock(a) }
    }
}
