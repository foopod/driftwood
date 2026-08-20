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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ThreadUiState {
    data object Loading : ThreadUiState
    data class Loaded(val thread: ThreadView) : ThreadUiState
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
        .flatMapLatest { repository.observeThread(it) }
        .map { ThreadUiState.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThreadUiState.Loading)

    fun bind(id: MessageId) {
        rootId.value = id
    }

    fun setFavourite(id: MessageId, favourite: Boolean) {
        viewModelScope.launch { repository.setFavourite(id, favourite) }
    }
}
