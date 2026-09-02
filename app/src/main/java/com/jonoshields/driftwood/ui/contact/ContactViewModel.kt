package com.jonoshields.driftwood.ui.contact

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jonoshields.driftwood.core.data.DirectoryRepository
import com.jonoshields.driftwood.core.data.MessageRepository
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.store.DisplayName
import com.jonoshields.driftwood.core.store.NameResolver
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
        val isFollowing: Boolean,
        val isBlocked: Boolean,
        val lastHeardFromMillis: Long?,
    ) : ContactUiState
}

/** The one-author counterpart of `ThreadViewModel`'s in-place contact actions, reached as a real screen. */
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
                directory.observeFollowList(),
                repository.observeBlockedAuthors(),
                repository.observeLastMessageFrom(a),
            ) { names, followList, blockedAuthors, lastHeardFromMillis ->
                ContactUiState.Loaded(
                    displayName = names[a] ?: NameResolver.resolve(a, nickname = null, username = null),
                    isFollowing = a in followList,
                    isBlocked = a in blockedAuthors,
                    lastHeardFromMillis = lastHeardFromMillis,
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

    fun toggleFollow() {
        val a = author.value ?: return
        val listening = (uiState.value as? ContactUiState.Loaded)?.isFollowing ?: false
        viewModelScope.launch {
            if (listening) directory.unfollow(a) else directory.follow(a)
        }
    }

    fun block() {
        val a = author.value ?: return
        // Blocking someone you follow stops the following too, not just the display.
        viewModelScope.launch {
            repository.block(a)
            directory.unfollow(a)
        }
    }

    fun unblock() {
        val a = author.value ?: return
        viewModelScope.launch { repository.unblock(a) }
    }
}
