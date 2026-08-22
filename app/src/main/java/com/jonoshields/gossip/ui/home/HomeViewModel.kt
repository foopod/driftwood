package com.jonoshields.gossip.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jonoshields.gossip.core.data.DirectoryRepository
import com.jonoshields.gossip.core.data.MessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class HomeViewModel @Inject constructor(
    repository: MessageRepository,
    directory: DirectoryRepository,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        repository.observeAll(),
        directory.observeListenScope(),
    ) { messages, listenScope ->
        if (messages.isEmpty()) HomeUiState.Empty else HomeThreadClassifier.classify(messages, listenScope)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState.Loading)
}
