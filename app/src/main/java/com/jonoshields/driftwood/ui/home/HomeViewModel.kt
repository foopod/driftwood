package com.jonoshields.driftwood.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.jonoshields.driftwood.core.data.DirectoryRepository
import com.jonoshields.driftwood.core.data.MessageRepository
import com.jonoshields.driftwood.core.data.ThreadSummary
import com.jonoshields.driftwood.core.identity.IdentityStore
import com.jonoshields.driftwood.core.model.AuthorId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/** The search box's resolved filter, shared across both tabs; [authorFilter]/[textQuery] are mutually exclusive in the UI. */
data class ThreadListParams(
    val unreadOnly: Boolean = false,
    val authorFilter: AuthorId? = null,
    val textQuery: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: MessageRepository,
    private val directory: DirectoryRepository,
    identity: IdentityStore,
) : ViewModel() {

    val myAuthor = runCatching { identity.publicKey() }.getOrNull()

    val uiState: StateFlow<HomeUiState> = combine(
        repository.observeHasAnyMessage(),
        directory.observeListenScope(),
        directory.observeNames(),
    ) { hasAnyMessage, listenScope, names ->
        if (!hasAnyMessage) {
            HomeUiState.Empty
        } else {
            HomeUiState.Threads(names = names, listenScope = listenScope)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState.Loading)

    private val unreadOnly = MutableStateFlow(false)
    private val authorFilter = MutableStateFlow<AuthorId?>(null)
    private val rawSearchText = MutableStateFlow("")

    // A shared, hot StateFlow so the debounce below runs once for both tabs, not per collector.
    private val params: StateFlow<ThreadListParams> = combine(
        unreadOnly,
        authorFilter,
        rawSearchText.debounce(SEARCH_DEBOUNCE_MILLIS).distinctUntilChanged(),
    ) { unread, author, text ->
        ThreadListParams(unreadOnly = unread, authorFilter = author, textQuery = text.ifBlank { null })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThreadListParams())

    val listeningThreads: Flow<PagingData<ThreadSummary>> = pagedThreadsFor(wantListening = true)
    val gossipThreads: Flow<PagingData<ThreadSummary>> = pagedThreadsFor(wantListening = false)

    fun setUnreadOnly(value: Boolean) {
        unreadOnly.value = value
    }

    /** Every keystroke; also clears any selected author, since the two are mutually exclusive. */
    fun setSearchText(text: String) {
        rawSearchText.value = text
        authorFilter.value = null
    }

    /** Picking a type-ahead suggestion: filters immediately (no debounce) and clears typed text. */
    fun selectAuthor(author: AuthorId) {
        authorFilter.value = author
        rawSearchText.value = ""
    }

    /** The chip's close icon — back to an empty free-text box. */
    fun clearAuthorFilter() {
        authorFilter.value = null
    }

    private fun pagedThreadsFor(wantListening: Boolean) =
        params
            .flatMapLatest { p -> repository.pagedThreads(wantListening, p.unreadOnly, p.authorFilter, p.textQuery) }
            .cachedIn(viewModelScope)
}

private const val SEARCH_DEBOUNCE_MILLIS = 300L
