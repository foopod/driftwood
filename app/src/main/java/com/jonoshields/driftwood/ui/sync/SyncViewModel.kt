package com.jonoshields.driftwood.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jonoshields.driftwood.core.data.DirectoryRepository
import com.jonoshields.driftwood.core.identity.IdentityStore
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.store.DisplayName
import com.jonoshields.driftwood.sync.NearbyPeer
import com.jonoshields.driftwood.sync.NsdPeerDiscovery
import com.jonoshields.driftwood.sync.PeerRef
import com.jonoshields.driftwood.sync.SyncCoordinator
import com.jonoshields.driftwood.sync.SyncLog
import com.jonoshields.driftwood.sync.SyncUiState
import com.jonoshields.driftwood.sync.WifiDirectPeerDiscovery
import com.jonoshields.driftwood.sync.mergeNearbyPeers
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A thin adapter onto the application-scoped [SyncCoordinator]; peer discovery is screen-scoped instead. */
@HiltViewModel
class SyncViewModel @Inject constructor(
    private val coordinator: SyncCoordinator,
    private val directory: DirectoryRepository,
    private val syncLog: SyncLog,
    identity: IdentityStore,
    discovery: NsdPeerDiscovery,
    wifiDirectDiscovery: WifiDirectPeerDiscovery,
) : ViewModel() {

    /** So the confirm screen can show it labelled "Mine", beside the peer's "Theirs". */
    val myAuthor: AuthorId? = runCatching { identity.publicKey() }.getOrNull()

    val uiState: StateFlow<SyncUiState> = coordinator.state

    /** Both discovery sources merged; `onStart` seeds each so `combine` emits before both have found a peer. */
    val discoveredPeers: StateFlow<List<NearbyPeer>> =
        combine(
            discovery.discover().onStart { emit(emptyList()) },
            wifiDirectDiscovery.discover().onStart { emit(emptyList()) },
            ::mergeNearbyPeers,
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** For the confirmation step's nickname/follow controls, independent of Confirm/Decline. */
    val names: StateFlow<Map<AuthorId, DisplayName>> = directory.observeNames()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val followList: StateFlow<Set<AuthorId>> = directory.observeFollowList()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun startListening() = coordinator.startListening()

    /** Manual address entry — LAN only, since a Wi-Fi Direct peer isn't addressable by typed IP:port. */
    fun connectTo(host: String, port: Int) = coordinator.connectTo(PeerRef.Lan(host, port), "$host:$port")

    /** A tap on an entry in the merged nearby list — either transport. */
    fun connectTo(peer: NearbyPeer) = coordinator.connectTo(peer.ref, peer.name)

    /** Tapping "Sync" is the verifying act once the hashes-match checkbox has been ticked (or the peer was already verified). */
    fun confirmPeer() {
        (uiState.value as? SyncUiState.Confirming)?.peer?.let { peer ->
            viewModelScope.launch { directory.verify(peer) }
        }
        coordinator.confirmPeer()
    }

    /** Snapshot of what happened during the current/most recent attempt, for the "send log" action. */
    fun logSnapshot(): String = syncLog.snapshot()

    fun cancel() = coordinator.cancel()

    fun reset() = coordinator.reset()

    fun setNickname(author: AuthorId, nickname: String) {
        viewModelScope.launch { directory.setNickname(author, nickname) }
    }

    fun toggleFollow(author: AuthorId) {
        viewModelScope.launch {
            if (author in followList.value) directory.unfollow(author) else directory.follow(author)
        }
    }
}
