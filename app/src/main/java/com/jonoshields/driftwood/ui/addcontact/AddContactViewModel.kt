package com.jonoshields.driftwood.ui.addcontact

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jonoshields.driftwood.core.data.DirectoryRepository
import com.jonoshields.driftwood.core.identity.IdentityStore
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.ContactQrPayload
import com.jonoshields.driftwood.core.store.DisplayName
import com.jonoshields.driftwood.core.store.NameResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Both halves of "Add contact" — showing your own code and scanning someone else's — live on one screen. */
sealed interface AddContactUiState {
    data class Ready(val myQrPayload: String?, val scanning: Boolean) : AddContactUiState

    /** Same shape as the sync-confirm screen's fingerprint step, deliberately. */
    data class Confirming(
        val scanned: AuthorId,
        val displayName: DisplayName,
        val isFollowing: Boolean,
    ) : AddContactUiState

    data class Added(val displayName: DisplayName) : AddContactUiState
}

private sealed interface Mode {
    data class Ready(val scanning: Boolean) : Mode
    data class Confirming(val author: AuthorId) : Mode
    data class Added(val author: AuthorId) : Mode
}

@HiltViewModel
class AddContactViewModel @Inject constructor(
    identity: IdentityStore,
    private val directory: DirectoryRepository,
) : ViewModel() {

    private val myAuthor = runCatching { identity.publicKey() }.getOrNull()

    /** Just the fingerprint, never a username — a QR code isn't a trustworthy place to carry one. */
    private val myQrPayload = myAuthor?.let(ContactQrPayload::encode)

    private val mode = MutableStateFlow<Mode>(Mode.Ready(scanning = false))

    val uiState: StateFlow<AddContactUiState> = combine(
        mode,
        directory.observeNames(),
        directory.observeFollowList(),
    ) { currentMode, names, followList ->
        fun nameOf(author: AuthorId) = names[author] ?: NameResolver.resolve(author, nickname = null, username = null)
        when (currentMode) {
            is Mode.Ready -> AddContactUiState.Ready(myQrPayload, currentMode.scanning)
            is Mode.Confirming -> AddContactUiState.Confirming(
                scanned = currentMode.author,
                displayName = nameOf(currentMode.author),
                isFollowing = currentMode.author in followList,
            )
            is Mode.Added -> AddContactUiState.Added(nameOf(currentMode.author))
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AddContactUiState.Ready(myQrPayload, scanning = false),
    )

    fun startScanning() {
        mode.value = Mode.Ready(scanning = true)
    }

    fun cancelScanning() {
        mode.value = Mode.Ready(scanning = false)
    }

    /** Called for every frame the camera analyzer decodes; silently ignores non-matches. */
    fun onQrScanned(payload: String) {
        if (mode.value !is Mode.Ready) return // already past this step; a late frame is a no-op
        val author = ContactQrPayload.decode(payload) ?: return
        if (author == myAuthor) return // your own code isn't a contact to add
        mode.value = Mode.Confirming(author)
    }

    fun setNickname(nickname: String) {
        val author = (mode.value as? Mode.Confirming)?.author ?: return
        viewModelScope.launch { directory.setNickname(author, nickname) }
    }

    fun toggleFollow() {
        val state = uiState.value as? AddContactUiState.Confirming ?: return
        viewModelScope.launch {
            if (state.isFollowing) directory.unfollow(state.scanned) else directory.follow(state.scanned)
        }
    }

    fun confirm() {
        val author = (mode.value as? Mode.Confirming)?.author ?: return
        viewModelScope.launch { directory.verify(author) }
        mode.value = Mode.Added(author)
    }

    fun done() {
        mode.value = Mode.Ready(scanning = false)
    }
}
