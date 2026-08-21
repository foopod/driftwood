package com.jonoshields.gossip.ui.sync

import androidx.lifecycle.ViewModel
import com.jonoshields.gossip.sync.SyncCoordinator
import com.jonoshields.gossip.sync.SyncUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * A thin adapter onto [SyncCoordinator], which is application-scoped on purpose: leaving
 * this screen (this ViewModel's lifecycle) must not abort a session two people are mid-way
 * through, so all the state genuinely lives one level up.
 */
@HiltViewModel
class SyncViewModel @Inject constructor(
    private val coordinator: SyncCoordinator,
) : ViewModel() {

    val uiState: StateFlow<SyncUiState> = coordinator.state

    fun startListening() = coordinator.startListening()

    fun connectTo(host: String, port: Int) = coordinator.connectTo(host, port)

    fun confirmPeer() = coordinator.confirmPeer()

    fun declinePeer() = coordinator.declinePeer()

    fun cancel() = coordinator.cancel()

    fun reset() = coordinator.reset()
}
