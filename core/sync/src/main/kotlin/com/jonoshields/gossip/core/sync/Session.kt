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
    /** Gossip refused because our own intake budget for this session ran out. */
    val overBudgetDropped: Int = 0,
    val priorityPhaseCompleted: Boolean = false,
    val gossipPhaseCompleted: Boolean = false,
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

    /**
     * @param myAuthor Sent in `HELLO` so the peer can show a human who they're talking to.
     * @param confirmPeer Asked right after `HELLO`, before anything private (the listen
     * scope) goes on the wire — plan.md §5 step 1's "both users confirm before any data
     * moves". Defaults to always-accept for callers with nothing to confirm against yet
     * (tests, the debug peer).
     */
    suspend fun run(
        role: Role,
        connection: Connection,
        myAuthor: AuthorId,
        confirmPeer: suspend (AuthorId) -> Boolean = { true },
    ): SessionResult {
        // Held in a box rather than threaded through return values, so that an abort halfway
        // through a phase still reports what was already persisted. Returning it would lose
        // exactly the work a partial sync is supposed to keep.
        val progress = Progress()
        return try {
            handshake(role, connection, myAuthor, confirmPeer)
            val priority = priorityPhase(role, connection, progress)
            gossipPhase(role, connection, priority, progress)
            store.pruneAfterSession(clock.nowMillis())
            SessionResult.Completed(progress.summary)
        } catch (abort: Abort) {
            tearDown(connection, abort.reason)
            SessionResult.Aborted(abort.reason, progress.summary)
        } catch (closed: ConnectionClosed) {
            // The peer went away. Whatever was applied stays applied, and stays reported.
            SessionResult.Aborted(AbortReason.PEER_CLOSED, progress.summary)
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

    private suspend fun handshake(
        role: Role,
        connection: Connection,
        myAuthor: AuthorId,
        confirmPeer: suspend (AuthorId) -> Boolean,
    ) {
        val mine = Record.Hello(protocolVersion, myAuthor)
        val theirs = swap(role, connection, mine) as? Record.Hello
            ?: throw Abort(AbortReason.OUT_OF_PHASE)
        if (theirs.protocolVersion != protocolVersion) throw Abort(AbortReason.VERSION_MISMATCH)
        if (!confirmPeer(theirs.author)) throw Abort(AbortReason.PEER_DECLINED)
    }

    // ---- priority phase --------------------------------------------------------------

    /** What the session has done so far, updated as it happens rather than at the end. */
    private class Progress {
        var summary = SyncSummary()
    }

    /** What the priority phase leaves behind for the gossip phase to build on. */
    private data class PriorityResult(
        val myScope: ScopeDeclaration,
        /** Ids we already streamed, so gossip never offers the same content twice. */
        val sent: Set<MessageId>,
        /** Their hash-list: content they told us they already hold. */
        val theirHashList: Set<MessageId>,
    )

    private suspend fun priorityPhase(
        role: Role,
        connection: Connection,
        progress: Progress,
    ): PriorityResult {
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
        if (role == Role.INITIATOR) {
            val sent = deliver(connection, delivery.sendNow)
            receiveUntilPhaseDone(connection, myScope, progress)
            progress.summary = progress.summary.copy(messagesSent = sent)
        } else {
            receiveUntilPhaseDone(connection, myScope, progress)
            progress.summary = progress.summary.copy(
                messagesSent = deliver(connection, delivery.sendNow),
            )
        }
        return PriorityResult(
            myScope = myScope,
            sent = delivery.sendNow.toSet(),
            theirHashList = theirHashList,
        )
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

    private suspend fun deliver(connection: Connection, ids: List<MessageId>): Int {
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


    // ---- gossip phase ----------------------------------------------------------------

    /**
     * Incidental recent content, offered before it is sent (plan.md §5, sync-spec.md §6.2).
     *
     * **Gossip offers; context does not.** The trade is not the same in both directions.
     * Context is a bounded tail of threads the peer demonstrably cares about, so a round trip
     * to save a few duplicates is a poor deal. Gossip is unchosen content of unbounded volume
     * that the peer very likely already has some of, so paying 32 bytes an id to avoid sending
     * whole messages is obviously worth it.
     *
     * **Skippable by design.** Losing the link here costs only discovery: everything either
     * side actually asked for was persisted when the priority phase completed. This is the
     * whole reason the phases are applied separately, and it exists because two people syncing
     * on a train platform get interrupted.
     */
    private suspend fun gossipPhase(
        role: Role,
        connection: Connection,
        priority: PriorityResult,
        progress: Progress,
    ) {
        if (role == Role.INITIATOR) {
            progress.addSent(offerGossip(connection, priority))
            receiveGossip(connection, priority, progress)
        } else {
            receiveGossip(connection, priority, progress)
            progress.addSent(offerGossip(connection, priority))
        }

        swap(role, connection, Record.SessionDone) as? Record.SessionDone
            ?: throw Abort(AbortReason.OUT_OF_PHASE)

        progress.summary = progress.summary.copy(gossipPhaseCompleted = true)
    }

    private fun Progress.addSent(n: Int) {
        summary = summary.copy(messagesSent = summary.messagesSent + n)
    }

    /** Our half: offer our newest, send back whatever they ask for out of it. */
    private suspend fun offerGossip(connection: Connection, priority: PriorityResult): Int {
        // Anything already streamed, or that they told us they hold, would be wasted bytes.
        val covered = priority.sent + priority.theirHashList
        val blocklist = store.blocklist()
        val offer = store.newestHeld(GOSSIP_INTAKE_CAP, excluding = covered)
            .filterNot { it.author in blocklist.authors || it.threadRoot in blocklist.roots }
            .map { it.id }

        connection.send(FrameCodec.encode(Record.GossipOffer(offer)))

        val asked = (next(connection) as? Record.GossipRequest
            ?: throw Abort(AbortReason.OUT_OF_PHASE)).ids

        // Only what we actually offered. A peer is free to ask for any id it likes, and
        // answering an unoffered one would turn the request into a probe: name a hash, learn
        // from the reply whether we hold it. What we offered, we already disclosed.
        val offered = offer.toSet()
        return deliver(connection, asked.filter { it in offered })
    }

    /** Their half: take their offer, ask for what we lack, ingest the answer. */
    private suspend fun receiveGossip(
        connection: Connection,
        priority: PriorityResult,
        progress: Progress,
    ) {
        val offered = (next(connection) as? Record.GossipOffer
            ?: throw Abort(AbortReason.OUT_OF_PHASE)).ids

        val held = store.heldWithIds(offered.toSet()).mapTo(mutableSetOf()) { it.id }

        // Capped by *our* budget, not by what they chose to offer. Someone offering ten
        // thousand ids gets asked for a thousand, and the rest is simply not our problem.
        val asked = Reconciler.request(offered, held).take(GOSSIP_INTAKE_CAP)
        connection.send(FrameCodec.encode(Record.GossipRequest(asked)))

        receiveUntilPhaseDone(connection, priority.myScope, progress, intakeCap = GOSSIP_INTAKE_CAP)
    }

    /**
     * [intakeCap] bounds how much we will *accept*, and is null for the priority phase.
     *
     * The asymmetry is deliberate (sync-spec.md §6.7). Wants and in-scope content were asked
     * for, so refusing them part-way through would be refusing our own request; gossip was
     * not, so it gets a budget. A peer that ignores the cap and floods anyway simply has the
     * excess dropped — it is not a rejection, because sending too much is rudeness rather
     * than evidence of forgery.
     */
    private suspend fun receiveUntilPhaseDone(
        connection: Connection,
        myScope: ScopeDeclaration,
        progress: Progress,
        intakeCap: Int? = null,
    ) {
        val ingest = Ingest(
            blocklist = store.blocklist(),
            wants = myScope.wants,
            windowCutoff = myScope.windowCutoff,
            intakeCap = intakeCap,
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

        // Recorded the moment it is persisted. `priorityPhaseCompleted` is about what we took
        // in, not about whether we finished handing ours over: if the peer walks off before
        // reading our half, our side of the sync is still whole and still worth reporting.
        val summary = progress.summary
        progress.summary = summary.copy(
            messagesAccepted = summary.messagesAccepted + outcome.accepted.size,
            profilesAccepted = summary.profilesAccepted + outcome.profiles.size,
            rejected = summary.rejected.merge(outcome.rejected),
            blockedDropped = summary.blockedDropped + ingest.blockedDropped,
            staleDropped = summary.staleDropped + ingest.staleDropped,
            overBudgetDropped = summary.overBudgetDropped + ingest.overBudgetDropped,
            priorityPhaseCompleted = summary.priorityPhaseCompleted || intakeCap == null,
        )
    }

    // ---- plumbing --------------------------------------------------------------------

    /**
     * One alternating exchange: whoever leads sends first, the other answers.
     *
     * An `ABORT` arriving here is surfaced with its real reason rather than falling through
     * to the generic [AbortReason.OUT_OF_PHASE] a plain type mismatch would produce — the
     * same distinction [AbortReason.PEER_CLOSED] exists for: misreporting an honest "no
     * thanks" or a dropped link as a protocol violation makes an innocent peer look hostile
     * in the logs.
     */
    private suspend fun swap(role: Role, connection: Connection, mine: Record): Record {
        val theirs = if (role == Role.INITIATOR) {
            connection.send(FrameCodec.encode(mine))
            next(connection)
        } else {
            val received = next(connection)
            connection.send(FrameCodec.encode(mine))
            received
        }
        if (theirs is Record.Abort) throw Abort(theirs.reason)
        return theirs
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
    private val intakeCap: Int? = null,
) {
    private val accepted = mutableListOf<Message>()
    private val profiles = mutableListOf<Profile>()
    private val rejections = mutableMapOf<RejectionReason, Int>()

    var blockedDropped = 0; private set
    var staleDropped = 0; private set
    var overBudgetDropped = 0; private set

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

        // Last, so that a flood of gossip cannot push us past the budget by arriving before
        // the checks that would have thrown it away anyway.
        if (intakeCap != null && accepted.size >= intakeCap) {
            overBudgetDropped++
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
