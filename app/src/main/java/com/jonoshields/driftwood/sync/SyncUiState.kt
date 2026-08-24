package com.jonoshields.driftwood.sync

import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.sync.SessionResult

/** What [SyncCoordinator] is doing, for whatever screen is currently showing it. */
sealed interface SyncUiState {
    data object Idle : SyncUiState

    /** Bound and waiting for one incoming connection on [port]. */
    data class Listening(val port: Int) : SyncUiState

    /** [label] is `"host:port"` for a LAN peer, or the peer's name for a Wi-Fi Direct one —
     * there's no address to show before a P2P group has even formed. */
    data class Connecting(val label: String) : SyncUiState

    /** `HELLO` exchanged; waiting on a human to confirm or decline [peer] before SCOPE goes out. */
    data class Confirming(val peer: AuthorId) : SyncUiState

    data object Running : SyncUiState

    data class Finished(val result: SessionResult) : SyncUiState

    data class Failed(val message: String) : SyncUiState
}
