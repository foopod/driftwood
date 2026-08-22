package com.jonoshields.gossip.ui.listen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jonoshields.gossip.core.data.DirectoryRepository
import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.store.DisplayName
import com.jonoshields.gossip.core.store.NameResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row on the listening-list screen. */
data class ListenEntry(val author: AuthorId, val displayName: DisplayName)

@HiltViewModel
class ListenListViewModel @Inject constructor(
    private val directory: DirectoryRepository,
) : ViewModel() {

    val entries: StateFlow<List<ListenEntry>> = combine(
        directory.observeListenScope(),
        directory.observeNames(),
    ) { scope, names ->
        scope.map { author -> ListenEntry(author, names[author] ?: NameResolver.resolve(author, petname = null, claimed = null)) }
            .sortedBy { it.displayName.text }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun listenTo(author: AuthorId) {
        viewModelScope.launch { directory.listenTo(author) }
    }

    fun stopListening(author: AuthorId) {
        viewModelScope.launch { directory.stopListening(author) }
    }
}
