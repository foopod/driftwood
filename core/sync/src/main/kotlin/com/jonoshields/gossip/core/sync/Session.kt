package com.jonoshields.gossip.core.sync

import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.model.Message
import com.jonoshields.gossip.core.model.MessageId
import com.jonoshields.gossip.core.model.MessageVerifier
import com.jonoshields.gossip.core.model.Profile
import com.jonoshields.gossip.core.model.ProfileCodec
import com.jonoshields.gossip.core.model.ProfileVerifyResult
import com.jonoshields.gossip.core.model.RejectionReason
import com.jonoshields.gossip.core.model.VerifyResult
import com.jonoshields.gossip.core.store.Blocklist
import com.jonoshields.gossip.core.store.Clock
import com.jonoshields.gossip.core.store.HeldMessage
import kotlinx.coroutines.withTimeoutOrNull

/** plan.md §3.4. Exchanged in the handshake; a mismatch refuses rather than negotiates. */
const val PROTOCOL_VERSION: Int = 1

/** plan.md §3.4: unverifiable messages from one peer before the session is torn down. */
const val VERIFY_FAIL_CUTOFF: Int = 20

/**
 * A runaway guard, not a policy cap.
 *
 * plan.md §5 leaves the priority phase uncapped on purpose: content from people you follow is
 * bounded by what those people actually wrote. But nothing stops a peer *replaying* valid
 * messages forever — each verifies, each dedups to a no-op, and the session never ends. This
 * ceiling is far above any honest session (the whole store at the default budget is 131,072
 * messages) and exists only so a session cannot run without limit.
 */
const val MAX_MESSAGES_PER_PHASE: Int = 50_000

/**
 * How long we will wait to hand a peer an `ABORT` before giving up and just closing.
 * Short on purpose: the frame is a courtesy to an honest peer, and a hostile one must not be
 * able to stall our teardown by refusing to read.
 */
const val ABORT_SEND_TIMEOUT_MILLIS: Long = 2_000

/** Which side speaks first. The protocol is symmetric; this decides only the order. */
enum class Role { INITIATOR, RESPONDER }

/** What a session did, for the sync summary the UI shows (plan.md §5). */
data class SyncSummary(
    val messagesSent: Int = 0,
    val messagesAccepted: Int = 0,
    val profilesAccepted: Int = 0,
    /** Verification failures — the bad-actor signal, counted per peer. */
    val rejected: Map<RejectionReason, Int> = emptyMap(),
    /** Dropped by our blocklist. Local policy, deliberately *not* a rejection. */
    val blockedDropped: Int = 0,
    /** Dropped for falling outside our window, and not filling a want. */
    val staleDropped: Int = 0,
    val priorityPhaseCompleted: Boolean = false,
) {
    val rejectionCount: Int get() = rejected.values.sum()
}

sealed interface SessionResult {
    data class Completed(val summary: SyncSummary) : SessionResult

    /** The priority phase's results are already persisted if it got that far. */
    data class Aborted(val reason: AbortReason, val summary: SyncSummary) : SessionResult
}

/** Thrown internally to unwind to the top; never escapes [Session.run]. */
private class Abort(val reason: AbortReason) : Exception(reason.name)

/**
 * One sync session (plan.md §5, sync-spec.md §4).
 *
 * **Strictly alternating.** Exactly one side is writing at any moment. If both streamed their
 * deltas before reading, each would fill the other's socket buffer and both would block — a
 * deadlock an unbounded in-process mock would never reproduce, so it would surface first on
 * real hardware. The cost is that a session takes the sum of both deliveries rather than the
 * larger; pipelining is a later optimisation.
 *
 * **Each phase is applied as it completes**, not at session end: a link dying during gossip
 * must leave the priority phase persisted, because two people syncing in the world get
 * interrupted and a sync that is ninety percent done should be worth ninety percent.
 */
class Session(
    private val store: SyncStore,
    private val clock: Clock,
    private val protocolVersion: Int = PROTOCOL_VERSION,
) {

    suspend fun run(role: Role, connection: Connection): SessionResult {
        var summary = SyncSummary()
        return try {
            handshake(role, connection)
            summary = priorityPhase(role, connection, summary)
            store.pruneAfterSession(clock.nowMillis())
            SessionResult.Completed(summary)
        } catch (abort: Abort) {
            tearDown(connection, abort.reason)
            SessionResult.Aborted(abort.reason, summary)
        } catch (closed: ConnectionClosed) {
            // The peer went away. Whatever a completed phase already applied stays applied.
            SessionResult.Aborted(AbortReason.PEER_CLOSED, summary)
        }
    }

    /**
     * Abandons a session, telling the peer why **if it will listen**.
     *
     * The `ABORT` frame is a courtesy, not part of the protocol's correctness: it lets an
     * honest peer log a real reason instead of guessing from a dropped link. So closing is
     * what actually ends things, and the session closes on abort even though the caller
     * otherwise owns the connection — an aborted session leaves the protocol state undefined,
     * so the connection is of no use to anyone afterwards.
     *
     * The timeout is deliberately belt-and-braces: **no in-process test reaches it**, because
     * closing a [Pipe] frees both ends at once. On a real socket it is not redundant. A write
     * to a peer that has stopped reading blocks against its receive window, and closing our
     * own end does nothing to release a write already in flight — the peer has to drain, or
     * we have to give up. This is where we give up.
     */
    private suspend fun tearDown(connection: Connection, reason: AbortReason) {
        withTimeoutOrNull(ABORT_SEND_TIMEOUT_MILLIS) {
            runCatching { connection.send(FrameCodec.encode(Record.Abort(reason))) }
        }
        runCatching { connection.close() }
    }

    // ---- handshake -------------------------------------------------------------------

    private suspend fun handshake(role: Role, connection: Connection) {
        val mine = Record.Hello(protocolVersion)
        val theirs = swap(role, connection, mine) as? Record.Hello
            ?: throw Abort(AbortReason.OUT_OF_PHASE)
        if (theirs.protocolVersion != protocolVersion) throw Abort(AbortReason.VERSION_MISMATCH)
    }

    // ---- priority phase --------------------------------------------------------------

    private suspend fun priorityPhase(
        role: Role,
        connection: Connection,
        summary: SyncSummary,
    ): SyncSummary {
        val now = clock.nowMillis()
        val myScope = ScopeDeclaration(
            listen = store.listenScope(),
            windowCutoff = store.windowCutoff(now),
            wants = store.wants(),
        )

        val theirScope = (swap(role, connection, Record.Scope(myScope)) as? Record.Scope
            ?: throw Abort(AbortReason.OUT_OF_PHASE)).declaration

        // Our hash-list covers our *own* scope: a peer only ever sends us content in our
        // scope, so listing anything else costs bytes and discloses holdings for nothing.
        val myHashList = Reconciler.hashList(store.heldBy(myScope.listen), myScope)
        val theirHashList = (swap(role, connection, Record.HashList(myHashList)) as? Record.HashList
            ?: throw Abort(AbortReason.OUT_OF_PHASE)).ids

        val delivery = planFor(theirScope, theirHashList)

        // Alternating: the initiator delivers, then listens; the responder the other way.
        var running = summary
        if (role == Role.INITIATOR) {
            val sent = deliver(connection, delivery)
            running = receiveUntilPhaseDone(connection, myScope, running)
            running = running.copy(messagesSent = sent)
        } else {
            running = receiveUntilPhaseDone(connection, myScope, running)
            val sent = deliver(connection, delivery)
            running = running.copy(messagesSent = sent)
        }
        return running.copy(priorityPhaseCompleted = true)
    }

    /**
     * Assembles just enough of our holdings for [Reconciler] to decide, and no more.
     *
     * The anchors are fetched **without** a window filter on purpose: whether a thread counts
     * as context depends on it containing a message from someone they follow, not on whether
     * that particular message is still fresh enough to send. The window is applied to
     * delivery, which is where it belongs.
     */
    private suspend fun planFor(theirScope: ScopeDeclaration, theirHashList: Set<MessageId>): Delivery {
        val anchors = store.heldBy(theirScope.listen)
        val contextThreads = anchors.mapTo(mutableSetOf()) { it.threadRoot }

        val relevant = (
            anchors +
                store.heldInThreads(contextThreads, theirScope.windowCutoff) +
                store.heldWithIds(theirScope.wants)
            ).distinctBy { it.id }

        return Reconciler.plan(
            held = relevant,
            peer = theirScope,
            peerHolds = theirHashList,
            blocklist = store.blocklist(),
        )
    }

    private suspend fun deliver(connection: Connection, delivery: Delivery): Int {
        val ids = delivery.sendNow
        val wire = store.readMessages(ids)
        wire.forEach { connection.send(FrameCodec.encode(Record.Message(it))) }

        // Names ride with content: receiving somebody's message is exactly when their name
        // becomes useful, and it means a name can never be pushed for a key whose content the
        // peer was not already accepting (plan.md §3.5).
        val authors = store.heldWithIds(ids.toSet()).mapTo(mutableSetOf<AuthorId>()) { it.author }
        store.readProfiles(authors).forEach {
            connection.send(FrameCodec.encode(Record.Profile(it)))
        }

        connection.send(FrameCodec.encode(Record.PhaseDone))
        return wire.size
    }

    private suspend fun receiveUntilPhaseDone(
        connection: Connection,
        myScope: ScopeDeclaration,
        summary: SyncSummary,
    ): SyncSummary {
        val ingest = Ingest(
            blocklist = store.blocklist(),
            wants = myScope.wants,
            windowCutoff = myScope.windowCutoff,
        )

        var received = 0
        while (true) {
            when (val record = next(connection)) {
                is Record.Message -> {
                    if (++received > MAX_MESSAGES_PER_PHASE) throw Abort(AbortReason.OUT_OF_PHASE)
                    ingest.offerMessage(record.wire)
                    if (ingest.rejectionCount > VERIFY_FAIL_CUTOFF) {
                        throw Abort(AbortReason.TOO_MANY_REJECTIONS)
                    }
                }
                is Record.Profile -> ingest.offerProfile(record.wire)
                Record.PhaseDone -> break
                is Record.Abort -> throw Abort(record.reason)
                else -> throw Abort(AbortReason.OUT_OF_PHASE)
            }
        }

        val outcome = ingest.outcome()
        store.apply(outcome, clock.nowMillis())

        return summary.copy(
            messagesAccepted = summary.messagesAccepted + outcome.accepted.size,
            profilesAccepted = summary.profilesAccepted + outcome.profiles.size,
            rejected = summary.rejected.merge(outcome.rejected),
            blockedDropped = summary.blockedDropped + ingest.blockedDropped,
            staleDropped = summary.staleDropped + ingest.staleDropped,
        )
    }

    // ---- plumbing --------------------------------------------------------------------

    /** One alternating exchange: whoever leads sends first, the other answers. */
    private suspend fun swap(role: Role, connection: Connection, mine: Record): Record =
        if (role == Role.INITIATOR) {
            connection.send(FrameCodec.encode(mine))
            next(connection)
        } else {
            val theirs = next(connection)
            connection.send(FrameCodec.encode(mine))
            theirs
        }

    private suspend fun next(connection: Connection): Record {
        val frame = connection.receive() ?: throw ConnectionClosed("peer closed mid-session")
        return when (val result = FrameCodec.decode(frame)) {
            is FrameResult.Ok -> result.record
            is FrameResult.Malformed -> throw Abort(AbortReason.MALFORMED_FRAME)
        }
    }
}

private fun Map<RejectionReason, Int>.merge(other: Map<RejectionReason, Int>) =
    (keys + other.keys).associateWith { (this[it] ?: 0) + (other[it] ?: 0) }

/**
 * Decides what to do with each thing a peer sends. The order is load-bearing (plan.md §5):
 *
 *  1. **Verify** — a failure is a *rejection*, counted, and enough of them tear the session
 *     down.
 *  2. **Blocked** — a *drop*, deliberately not a rejection. Blocking is local policy, not
 *     evidence of a bad actor, and counting it toward the abort threshold would end sessions
 *     with honest peers who happen to relay someone we blocked.
 *  3. **Out of window** — dropped, *unless* it fills one of our wants. A want is an id with no
 *     timestamp attached, so its age cannot be known until it arrives; refusing it here would
 *     turn down the very thing we asked for.
 *  4. **Accept.**
 */
private class Ingest(
    private val blocklist: Blocklist,
    private val wants: Set<MessageId>,
    private val windowCutoff: Long,
) {
    private val accepted = mutableListOf<Message>()
    private val profiles = mutableListOf<Profile>()
    private val rejections = mutableMapOf<RejectionReason, Int>()

    var blockedDropped = 0; private set
    var staleDropped = 0; private set

    val rejectionCount: Int get() = rejections.values.sum()

    fun offerMessage(wire: ByteArray) {
        val message = when (val result = MessageVerifier.verify(wire)) {
            is VerifyResult.Rejected -> {
                rejections.merge(result.reason.normalised(), 1, Int::plus)
                return
            }
            is VerifyResult.Valid -> result.message
        }

        if (message.body.author in blocklist.authors || message.threadRoot in blocklist.roots) {
            blockedDropped++
            return
        }

        // effective_time is min(claimed, received); received is now, so a message can only be
        // out of window by claiming to be old.
        if (message.id !in wants && message.body.timestampMillis < windowCutoff) {
            staleDropped++
            return
        }

        accepted += message
    }

    fun offerProfile(wire: ByteArray) {
        when (val result = ProfileCodec.verify(wire)) {
            is ProfileVerifyResult.Valid ->
                if (result.profile.author !in blocklist.authors) profiles += result.profile
            is ProfileVerifyResult.Rejected ->
                rejections.merge(result.reason.normalised(), 1, Int::plus)
        }
    }

    fun outcome() = PhaseOutcome(accepted.toList(), profiles.toList(), rejections.toMap())

    /** Malformed carries a detail string, which would make every failure its own map key. */
    private fun RejectionReason.normalised(): RejectionReason =
        if (this is RejectionReason.Malformed) RejectionReason.Malformed("malformed") else this
}
