package com.jonoshields.gossip.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jonoshields.gossip.sync.DiscoveredPeer
import com.jonoshields.gossip.sync.NsdPeerDiscovery
import com.jonoshields.gossip.sync.SyncCoordinator
import com.jonoshields.gossip.sync.SyncUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * A thin adapter onto [SyncCoordinator], which is application-scoped on purpose: leaving
 * this screen (this ViewModel's lifecycle) must not abort a session two people are mid-way
 * through, so all the state genuinely lives one level up.
 *
 * Peer *discovery* is the opposite: scoped to this screen rather than the coordinator, since
 * scanning for nearby peers is only useful while someone is actually looking at the list —
 * unlike an active sync, it should stop the moment this screen isn't shown.
 */
@HiltViewModel
class SyncViewModel @Inject constructor(
    private val coordinator: SyncCoordinator,
    discovery: NsdPeerDiscovery,
) : ViewModel() {

    val uiState: StateFlow<SyncUiState> = coordinator.state

    val discoveredPeers: StateFlow<List<DiscoveredPeer>> = discovery.discover()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun startListening() = coordinator.startListening()

    fun connectTo(host: String, port: Int) = coordinator.connectTo(host, port)

    fun confirmPeer() = coordinator.confirmPeer()

    fun declinePeer() = coordinator.declinePeer()

    fun cancel() = coordinator.cancel()

    fun reset() = coordinator.reset()
}
