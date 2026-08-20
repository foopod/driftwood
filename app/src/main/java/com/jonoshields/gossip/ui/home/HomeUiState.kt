package com.jonoshields.gossip.ui.home

import com.jonoshields.gossip.core.model.MessageId

/**
 * One conversation as it appears in the list. Grouped by thread rather than listed by
 * message so a burst of replies reads as one updated conversation (plan.md §6) — the shape
 * the two-tab feed will need in M4.
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

    data object Empty : HomeUiState

    data class Threads(val threads: List<ThreadSummary>) : HomeUiState
}
