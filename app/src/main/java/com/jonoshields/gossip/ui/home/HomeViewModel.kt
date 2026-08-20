package com.jonoshields.gossip.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jonoshields.gossip.core.data.MessageRepository
import com.jonoshields.gossip.core.model.Message
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class HomeViewModel @Inject constructor(
    repository: MessageRepository,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = repository.observeAll()
        .map { messages -> if (messages.isEmpty()) HomeUiState.Empty else summarise(messages) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState.Loading)

    private fun summarise(messages: List<Message>): HomeUiState.Threads {
        val threads = messages
            .groupBy { it.threadRoot }
            .map { (rootId, inThread) ->
                val root = inThread.firstOrNull { it.isRoot && it.id == rootId }
                ThreadSummary(
                    rootId = rootId,
                    // With the root pruned away the opening line is genuinely unknown, so
                    // fall back to the oldest reply still held rather than inventing one.
                    opening = root?.body?.text
                        ?: inThread.minByOrNull { it.body.timestampMillis }?.body?.text.orEmpty(),
                    messageCount = inThread.size,
                    newestTimestamp = inThread.maxOf { it.body.timestampMillis },
                    rootHeld = root != null,
                )
            }
        return HomeUiState.Threads(threads.sortedByDescending { it.newestTimestamp })
    }
}
