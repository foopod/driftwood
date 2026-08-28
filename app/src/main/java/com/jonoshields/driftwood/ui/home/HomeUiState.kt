package com.jonoshields.driftwood.ui.home

import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.store.DisplayName

/** Home screen state outside its two paginated thread lists (kept as separate flows on [HomeViewModel]). */
sealed interface HomeUiState {
    data object Loading : HomeUiState

    /** No messages held anywhere yet — the first-run empty state, not a per-tab one. */
    data object Empty : HomeUiState

    data class Threads(
        val names: Map<AuthorId, DisplayName> = emptyMap(),
        /** So a thread row knows whether to offer "Follow" for its root author. */
        val followList: Set<AuthorId> = emptySet(),
    ) : HomeUiState
}
