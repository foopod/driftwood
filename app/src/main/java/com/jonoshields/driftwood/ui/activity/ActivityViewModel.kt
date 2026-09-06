package com.jonoshields.driftwood.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.jonoshields.driftwood.core.data.ActivityItem
import com.jonoshields.driftwood.core.data.DirectoryRepository
import com.jonoshields.driftwood.core.data.MessageRepository
import com.jonoshields.driftwood.core.identity.IdentityStore
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.store.DisplayName
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class ActivityViewModel @Inject constructor(
    repository: MessageRepository,
    directory: DirectoryRepository,
    identity: IdentityStore,
) : ViewModel() {

    val myAuthor: AuthorId? = runCatching { identity.publicKey() }.getOrNull()

    val items: Flow<PagingData<ActivityItem>> = repository.pagedActivity().cachedIn(viewModelScope)

    val names: StateFlow<Map<AuthorId, DisplayName>> = directory.observeNames()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val unreadCount: StateFlow<Int> = repository.observeActivityUnreadCount()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}
