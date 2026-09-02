package com.jonoshields.driftwood.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jonoshields.driftwood.core.data.DirectoryRepository
import com.jonoshields.driftwood.core.identity.IdentityStore
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.store.DisplayName
import com.jonoshields.driftwood.core.store.NameResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row on the Contacts screen — everyone with a claimed name, verified/following annotated. */
data class ContactEntry(val author: AuthorId, val displayName: DisplayName, val isFollowing: Boolean)

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val directory: DirectoryRepository,
    identity: IdentityStore,
) : ViewModel() {

    private val myAuthor = runCatching { identity.publicKey() }.getOrNull()

    val entries: StateFlow<List<ContactEntry>> = combine(
        directory.observeClaimedAuthors(),
        directory.observeFollowList(),
        directory.observeNames(),
    ) { claimed, followList, names ->
        // The active user manages their own identity from Settings, not this contact-actions list.
        val unsorted = claimed.filter { it != myAuthor }.map { author ->
            ContactEntry(
                author = author,
                displayName = names[author] ?: NameResolver.resolve(author, nickname = null, username = null),
                isFollowing = author in followList,
            )
        }
        sortContactEntries(unsorted)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setNickname(author: AuthorId, nickname: String) {
        viewModelScope.launch { directory.setNickname(author, nickname) }
    }
}

/** Verified first, then followed, alphabetical within each group. */
internal fun sortContactEntries(entries: List<ContactEntry>): List<ContactEntry> =
    entries.sortedWith(
        compareByDescending<ContactEntry> { it.displayName.verified }
            .thenByDescending { it.isFollowing }
            .thenBy { it.displayName.text },
    )
