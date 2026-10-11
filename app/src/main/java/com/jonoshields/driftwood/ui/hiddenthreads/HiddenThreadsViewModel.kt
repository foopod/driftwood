package com.jonoshields.driftwood.ui.hiddenthreads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jonoshields.driftwood.core.data.DirectoryRepository
import com.jonoshields.driftwood.core.data.MessageRepository
import com.jonoshields.driftwood.core.model.MessageId
import com.jonoshields.driftwood.core.store.DisplayName
import com.jonoshields.driftwood.core.store.NameResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row on the Hidden threads screen — [displayName]/[text] fall back to a fingerprint-only /
 * "a hidden thread" placeholder when nothing in the thread is held locally any more. */
data class HiddenThreadEntry(
    val rootId: MessageId,
    val displayName: DisplayName?,
    val text: String?,
)

@HiltViewModel
class HiddenThreadsViewModel @Inject constructor(
    private val repository: MessageRepository,
    directory: DirectoryRepository,
) : ViewModel() {

    val entries: StateFlow<List<HiddenThreadEntry>> = combine(
        repository.observeHiddenThreadPreviews(),
        directory.observeNames(),
    ) { hidden, names ->
        hidden.map { entry ->
            val displayName = entry.author?.let { author ->
                names[author] ?: NameResolver.resolve(author, nickname = null, username = null)
            }
            HiddenThreadEntry(rootId = entry.rootId, displayName = displayName, text = entry.text)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun unhide(root: MessageId) {
        viewModelScope.launch { repository.unhideThread(root) }
    }
}
