package com.jonoshields.gossip.sync

import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.sync.SessionResult

/** What [SyncCoordinator] is doing, for whatever screen is currently showing it. */
sealed interface SyncUiState {
    data object Idle : SyncUiState

    /** Bound and waiting for one incoming connection on [port]. */
    data class Listening(val port: Int) : SyncUiState

    data class Connecting(val host: String, val port: Int) : SyncUiState

    /** `HELLO` exchanged; waiting on a human to confirm or decline [peer] before SCOPE goes out. */
    data class Confirming(val peer: AuthorId) : SyncUiState

    data object Running : SyncUiState

    data class Finished(val result: SessionResult) : SyncUiState

    data class Failed(val message: String) : SyncUiState
}
