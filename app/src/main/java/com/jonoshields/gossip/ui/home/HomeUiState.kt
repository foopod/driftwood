package com.jonoshields.gossip.ui.home

import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.model.MessageId
import com.jonoshields.gossip.core.store.DisplayName

/**
 * One conversation as it appears in the list. Grouped by thread rather than listed by
 * message so a burst of replies reads as one updated conversation (plan.md §6).
 *
 * [rootAuthor]/[rootText]/[rootTimestamp] are `null` together, exactly when the root itself
 * isn't held. [latestListenedAuthor]/[latestListenedText]/[latestListenedTimestamp] are set
 * only when someone you listen to has *replied* — never for the root itself, and never on a
 * Gossip-tab thread, where by construction no listened author appears at all.
 */
data class ThreadSummary(
    val rootId: MessageId,
    val rootAuthor: AuthorId?,
    val rootText: String?,
    val rootTimestamp: Long?,
    val latestListenedAuthor: AuthorId?,
    val latestListenedText: String?,
    val latestListenedTimestamp: Long?,
    val messageCount: Int,
    val newestTimestamp: Long,
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
        val names: Map<AuthorId, DisplayName> = emptyMap(),
    ) : HomeUiState
}
