package com.jonoshields.driftwood.ui.blocklist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jonoshields.driftwood.core.data.DirectoryRepository
import com.jonoshields.driftwood.core.data.MessageRepository
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.store.DisplayName
import com.jonoshields.driftwood.core.store.NameResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** One row on the blocklist screen. */
data class BlockedEntry(val author: AuthorId, val displayName: DisplayName)

@HiltViewModel
class BlocklistViewModel @Inject constructor(
    repository: MessageRepository,
    directory: DirectoryRepository,
) : ViewModel() {

    val entries: StateFlow<List<BlockedEntry>> = combine(
        repository.observeBlockedAuthors(),
        directory.observeNames(),
    ) { blocked, names ->
        blocked.map { author -> BlockedEntry(author, names[author] ?: NameResolver.resolve(author, nickname = null, username = null)) }
            .sortedBy { it.displayName.text }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
