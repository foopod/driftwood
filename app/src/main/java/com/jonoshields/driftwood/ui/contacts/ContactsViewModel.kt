package com.jonoshields.driftwood.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jonoshields.driftwood.core.data.DirectoryRepository
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.store.DisplayName
import com.jonoshields.driftwood.core.store.NameResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** One row on the merged contacts screen — everyone confirmed, plus everyone listened to. */
data class ConfirmedEntry(val author: AuthorId, val displayName: DisplayName, val isListening: Boolean)

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val directory: DirectoryRepository,
) : ViewModel() {

    val entries: StateFlow<List<ConfirmedEntry>> = combine(
        directory.observeConfirmedAuthors(),
        directory.observeListenScope(),
        directory.observeNames(),
    ) { confirmed, listenScope, names ->
        val unsorted = (confirmed + listenScope).map { author ->
            ConfirmedEntry(
                author = author,
                displayName = names[author] ?: NameResolver.resolve(author, nickname = null, username = null, confirmed = author in confirmed),
                isListening = author in listenScope,
            )
        }
        sortConfirmedEntries(unsorted)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** Listened people first, then everyone else confirmed, alphabetical within each group. */
internal fun sortConfirmedEntries(entries: List<ConfirmedEntry>): List<ConfirmedEntry> =
    entries.sortedWith(compareByDescending<ConfirmedEntry> { it.isListening }.thenBy { it.displayName.text })
