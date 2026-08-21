package com.jonoshields.gossip.core.sync

import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.model.Message
import com.jonoshields.gossip.core.model.MessageId
import com.jonoshields.gossip.core.model.Profile
import com.jonoshields.gossip.core.model.RejectionReason
import com.jonoshields.gossip.core.store.Blocklist
import com.jonoshields.gossip.core.store.HeldMessage

/**
 * What one phase of a session produced, ready to be persisted in a single transaction.
 *
 * The session is a pure function that returns one of these rather than writing as it goes,
 * so a convergence test can assert on the outcome directly instead of reconstructing what
 * happened from a store afterwards.
 *
 * **Applied per phase, not per session.** plan.md §5 requires that a connection dying during
 * the gossip phase still leaves the priority phase's results persisted — a sync that is
 * ninety percent done has to be worth ninety percent, because two people syncing in the world
 * get interrupted.
 */
data class PhaseOutcome(
    /** Verified, in arrival order. Anything that failed verification never reaches here. */
    val accepted: List<Message>,
    val profiles: List<Profile>,
    /** Counted per peer per session and surfaced in the sync summary (§5). */
    val rejected: Map<RejectionReason, Int>,
) {
    val isEmpty: Boolean get() = accepted.isEmpty() && profiles.isEmpty()

    val rejectionCount: Int get() = rejected.values.sum()

    companion object {
        val EMPTY = PhaseOutcome(emptyList(), emptyList(), emptyMap())
    }
}

/**
 * Everything the sync engine needs from local storage, and nothing else.
 *
 * This exists so `:core:sync` never depends on Room. The milestone's done-criterion is *"two
 * in-process stores converge"*, and with this seam that is an ordinary JVM test running two
 * fakes over a pipe — hundreds of scenarios in seconds. With the database on the wrong side
 * of the boundary it would be an instrumented test needing a phone.
 *
 * Every method is a **question about the domain**, never a query shape. That is the rule that
 * keeps the port from quietly widening into a database interface: if something here starts
 * taking a sort order, a limit-and-offset, or a column name, it has stopped being a port.
 *
 * All reads return metadata ([HeldMessage]) rather than content. Content is fetched only for
 * the ids actually being sent, so a session never loads a store's worth of message bodies to
 * decide what to do.
 */
interface SyncStore {

    // ---- what we declare to a peer (§5 step 2) ---------------------------------------

    suspend fun listenScope(): Set<AuthorId>

    /** Lower bound on `effective_time` we will accept, derived from the configured window. */
    suspend fun windowCutoff(nowMillis: Long): Long

    /** Orphan parent ids we would accept. Parents only; roots are never wanted (§3.2). */
    suspend fun wants(): Set<MessageId>

    // ---- what we hold ----------------------------------------------------------------

    /** Metadata for everything by these authors, optionally no older than [since]. */
    suspend fun heldBy(authors: Set<AuthorId>, since: Long? = null): List<HeldMessage>

    /** Metadata for everything in these threads, no older than [since]. */
    suspend fun heldInThreads(roots: Set<MessageId>, since: Long): List<HeldMessage>

    /** Metadata for whichever of [ids] we have. Used to answer a want-list and an offer. */
    suspend fun heldWithIds(ids: Set<MessageId>): List<HeldMessage>

    /** The newest we hold, skipping [excluding] — the gossip phase's offer (§5 step 5). */
    suspend fun newestHeld(limit: Int, excluding: Set<MessageId>): List<HeldMessage>

    // ---- content, fetched only for what is actually being sent ------------------------

    /** Wire forms, in the order asked for. Ids we no longer hold are simply absent. */
    suspend fun readMessages(ids: List<MessageId>): List<ByteArray>

    /** Profile records for these authors, to ride along with their content (§3.5). */
    suspend fun readProfiles(authors: Set<AuthorId>): List<ByteArray>

    // ---- local policy, never disclosed to a peer -------------------------------------

    /**
     * Ours alone. It is applied when planning, so we never relay for someone we blocked, and
     * again on ingest, because the peer's list is private and they filter their own (§4).
     */
    suspend fun blocklist(): Blocklist

    // ---- writing ---------------------------------------------------------------------

    /**
     * Persists one phase in a single transaction, and updates the want-list from what
     * arrived: satisfied wants are removed, and parents newly discovered to be missing are
     * added with a TTL.
     */
    suspend fun apply(outcome: PhaseOutcome, receivedAtMillis: Long)

    /** Runs pruning once over the merged result (§4). Called after the session, not per phase. */
    suspend fun pruneAfterSession(nowMillis: Long)
}
