package com.jonoshields.gossip.ui.home

import com.jonoshields.gossip.core.model.MessageId

/**
 * One conversation as it appears in the list. Grouped by thread rather than listed by
 * message so a burst of replies reads as one updated conversation (plan.md §6).
 */
data class ThreadSummary(
    val rootId: MessageId,
    val opening: String,
    val messageCount: Int,
    val newestTimestamp: Long,
    val rootHeld: Boolean,
)

sealed interface HomeUiState {
    data object Loading : HomeUiState

    /** No messages held anywhere yet — the first-run empty state, not a per-tab one. */
    data object Empty : HomeUiState

    /**
     * The two tabs (plan.md §6): [listening] is every thread containing at least one
     * message from someone you listen to — including the stranger-replies in it, which is
     * what keeps a followed conversation whole rather than fragmenting it across tabs.
     * [gossip] is everything else.
     */
    data class Threads(
        val listening: List<ThreadSummary>,
        val gossip: List<ThreadSummary>,
    ) : HomeUiState
}
