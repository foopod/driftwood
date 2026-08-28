package com.jonoshields.driftwood.core.sync

import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.Message
import com.jonoshields.driftwood.core.model.MessageId
import com.jonoshields.driftwood.core.model.Profile
import com.jonoshields.driftwood.core.model.RejectionReason
import com.jonoshields.driftwood.core.store.Blocklist
import com.jonoshields.driftwood.core.store.HeldMessage

/** What one batch within a phase produced, applied per batch (not per phase) so a mid-phase disconnect still keeps what arrived. */
data class PhaseOutcome(
    /** Verified, in arrival order. Anything that failed verification never reaches here. */
    val accepted: List<Message>,
    val profiles: List<Profile>,
    /** Counted per peer per session and surfaced in the sync summary. */
    val rejected: Map<RejectionReason, Int>,
) {
    val isEmpty: Boolean get() = accepted.isEmpty() && profiles.isEmpty()

    val rejectionCount: Int get() = rejected.values.sum()

    companion object {
        val EMPTY = PhaseOutcome(emptyList(), emptyList(), emptyMap())
    }
}

/** Everything the sync engine needs from local storage — a port so `:core:sync` never depends on Room, and never widens into a query shape. */
interface SyncStore {

    // ---- what we declare to a peer -----------------------------------------------------

    suspend fun followList(): Set<AuthorId>

    /** Lower bound on `effective_time` we will accept, derived from the configured window. */
    suspend fun windowCutoff(nowMillis: Long): Long

    /** Orphan parent ids we would accept. Parents only; roots are never wanted. */
    suspend fun wants(): Set<MessageId>

    // ---- what we hold ----------------------------------------------------------------

    /** Metadata for everything by these authors, optionally no older than [since]. */
    suspend fun heldBy(authors: Set<AuthorId>, since: Long? = null): List<HeldMessage>

    /** Metadata for everything in these threads, no older than [since]. */
    suspend fun heldInThreads(roots: Set<MessageId>, since: Long): List<HeldMessage>

    /** Metadata for whichever of [ids] we have. Used to answer a want-list and an offer. */
    suspend fun heldWithIds(ids: Set<MessageId>): List<HeldMessage>

    /** The newest we hold, skipping [excluding] — the gossip phase's offer. */
    suspend fun newestHeld(limit: Int, excluding: Set<MessageId>): List<HeldMessage>

    // ---- content, fetched only for what is actually being sent ------------------------

    /** Wire forms, in the order asked for. Ids we no longer hold are simply absent. */
    suspend fun readMessages(ids: List<MessageId>): List<ByteArray>

    /** Profile records for these authors, to ride along with their content. */
    suspend fun readProfiles(authors: Set<AuthorId>): List<ByteArray>

    // ---- local policy, never disclosed to a peer -------------------------------------

    /** Ours alone — applied when planning what to send, and again on ingest since the peer filters its own. */
    suspend fun blocklist(): Blocklist

    // ---- writing ---------------------------------------------------------------------

    /** Persists one batch in a transaction and updates the want-list from what arrived; safe to call many times per phase. */
    suspend fun apply(outcome: PhaseOutcome, receivedAtMillis: Long)

    /** Ends one phase, called exactly once regardless of batch count: ages every outstanding want and drops any past its TTL. */
    suspend fun finishPhase(nowMillis: Long)

    /** Runs pruning once over the merged result. Called after the session, not per phase. */
    suspend fun pruneAfterSession(nowMillis: Long)
}
