package com.jonoshields.driftwood.ui.feed

import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.store.DisplayName

/** Shared state for the Feed and Discover panes, apart from their paginated lists (which are
 * separate flows on [FeedViewModel]). */
sealed interface FeedUiState {
    data object Loading : FeedUiState

    /** No messages held anywhere yet — the first-run empty state. */
    data object Empty : FeedUiState

    data class Threads(
        val names: Map<AuthorId, DisplayName> = emptyMap(),
        /** So a thread row knows whether to offer "Follow" for its root author. */
        val followList: Set<AuthorId> = emptySet(),
    ) : FeedUiState
}
