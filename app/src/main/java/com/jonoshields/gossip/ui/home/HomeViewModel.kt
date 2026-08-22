package com.jonoshields.gossip.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jonoshields.gossip.core.data.DirectoryRepository
import com.jonoshields.gossip.core.data.MessageRepository
import com.jonoshields.gossip.core.identity.IdentityStore
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
    identity: IdentityStore,
) : ViewModel() {

    private val myAuthor = runCatching { identity.publicKey() }.getOrNull()

    val uiState: StateFlow<HomeUiState> = combine(
        repository.observeAll(),
        directory.observeListenScope(),
        directory.observeNames(),
    ) { messages, listenScope, names ->
        if (messages.isEmpty()) {
            HomeUiState.Empty
        } else {
            HomeThreadClassifier.classify(messages, listenScope, myAuthor).copy(names = names)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState.Loading)
}
