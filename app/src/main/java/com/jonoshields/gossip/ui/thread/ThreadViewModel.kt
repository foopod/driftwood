package com.jonoshields.gossip.ui.thread

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jonoshields.gossip.core.data.MessageRepository
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
    data class Loaded(val thread: ThreadView, val starred: Boolean) : ThreadUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ThreadViewModel @Inject constructor(
    private val repository: MessageRepository,
) : ViewModel() {

    private val rootId = MutableStateFlow<MessageId?>(null)

    val uiState: StateFlow<ThreadUiState> = rootId
        .filterNotNull()
        // flatMapLatest so opening a different thread cancels the previous query rather
        // than leaving two observers racing to set the same state.
        .flatMapLatest { id ->
            combine(repository.observeThread(id), repository.observeThreadFavourite(id)) { thread, starred ->
                ThreadUiState.Loaded(thread, starred)
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
}
