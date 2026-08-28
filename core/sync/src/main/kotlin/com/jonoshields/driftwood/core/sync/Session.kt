package com.jonoshields.driftwood.core.sync

import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.Message
import com.jonoshields.driftwood.core.model.MessageId
import com.jonoshields.driftwood.core.model.MessageVerifier
import com.jonoshields.driftwood.core.model.Profile
import com.jonoshields.driftwood.core.model.ProfileCodec
import com.jonoshields.driftwood.core.model.ProfileVerifyResult
import com.jonoshields.driftwood.core.model.RejectionReason
import com.jonoshields.driftwood.core.model.VerifyResult
import com.jonoshields.driftwood.core.store.Blocklist
import com.jonoshields.driftwood.core.store.Clock
import com.jonoshields.driftwood.core.store.HeldMessage
import kotlinx.coroutines.withTimeoutOrNull

/** Exchanged in the handshake; a mismatch refuses rather than negotiates. */
const val PROTOCOL_VERSION: Int = 1

/** Unverifiable messages allowed from one peer before the session is torn down. */
const val VERIFY_FAIL_CUTOFF: Int = 20

/** How many messages one direction sends before pausing for a [Record.BatchDone] marker; each batch persists as soon as it drains. */
const val PHASE_BATCH_SIZE: Int = 10_000

/** How many batches one direction exchanges in a phase before stopping — bounds how long we keep reading from an uncooperative peer. */
const val MAX_BATCHES_PER_PHASE: Int = 50

/** How long we wait to hand a peer an `ABORT` before giving up and closing, so a hostile peer can't stall teardown by refusing to read. */
const val ABORT_SEND_TIMEOUT_MILLIS: Long = 2_000

/** Which side speaks first. The protocol is symmetric; this decides only the order. */
enum class Role { INITIATOR, RESPONDER }

/** What a session did, for the sync summary the UI shows. */
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
    /** True when the priority phase hit [MAX_BATCHES_PER_PHASE] before finishing — not a failure, just more to sync next time. */
    val priorityPhaseTruncated: Boolean = false,
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

/** One sync session: exactly one side writes at any moment, and each batch is persisted as it completes so an interrupted sync keeps what arrived. */
class Session(
    private val store: SyncStore,
    private val clock: Clock,
    private val protocolVersion: Int = PROTOCOL_VERSION,
    private val batchSize: Int = PHASE_BATCH_SIZE,
) {

    /** [confirmPeer] is asked right after `HELLO`, before any private data goes on the wire; defaults to always-accept. */
    suspend fun run(
        role: Role,
        connection: Connection,
        myAuthor: AuthorId,
        confirmPeer: suspend (AuthorId) -> Boolean = { true },
    ): SessionResult {
        // Boxed so an abort halfway through a phase still reports what was already persisted.
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

    /** Tells the peer why, if it's still listening, then closes — the timeout guards against a peer that stopped reading. */
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
            follow = store.followList(),
            windowCutoff = store.windowCutoff(now),
            wants = store.wants(),
        )

        val theirScope = (swap(role, connection, Record.Scope(myScope)) as? Record.Scope
            ?: throw Abort(AbortReason.OUT_OF_PHASE)).declaration

        // Our hash-list covers our own scope only — listing more costs bytes and discloses holdings for nothing.
        val myHashList = Reconciler.hashList(store.heldBy(myScope.follow), myScope)
        val theirHashList = (swap(role, connection, Record.HashList(myHashList)) as? Record.HashList
            ?: throw Abort(AbortReason.OUT_OF_PHASE)).ids

        val delivery = planFor(theirScope, theirHashList)

        val ingest = Ingest(
            blocklist = store.blocklist(),
            wants = myScope.wants,
            windowCutoff = myScope.windowCutoff,
        )

        val exchange = exchangeAlternating(role, connection, delivery.sendNow, ingest, progress)
        store.finishPhase(clock.nowMillis())

        // The other receive-half stats were already folded into progress.summary inside exchangeAlternating.
        progress.summary = progress.summary.copy(priorityPhaseTruncated = exchange.truncated)
        return PriorityResult(
            myScope = myScope,
            sent = delivery.sendNow.toSet(),
            theirHashList = theirHashList,
        )
    }

    /** Assembles just enough of our holdings for [Reconciler] to decide; anchors are fetched without a window filter, applied later at delivery. */
    private suspend fun planFor(theirScope: ScopeDeclaration, theirHashList: Set<MessageId>): Delivery {
        val anchors = store.heldBy(theirScope.follow)
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

    /** Outcome of the priority phase's bidirectional exchange. */
    private data class ExchangeResult(val truncated: Boolean)

    /** Both sides send [myIds] in batches of [batchSize], alternating turns; receive-half stats fold into [progress] as each batch lands. */
    private suspend fun exchangeAlternating(
        role: Role,
        connection: Connection,
        myIds: List<MessageId>,
        ingest: Ingest,
        progress: Progress,
    ): ExchangeResult {
        var cursor = 0
        var myBatches = 0
        var myFinal = false
        var myTruncated = false

        var peerBatches = 0
        var peerFinal = false

        suspend fun myTurn() {
            if (myFinal) return
            val end = minOf(cursor + batchSize, myIds.size)
            val chunk = myIds.subList(cursor, end)
            val sentThisBatch = deliverBatch(connection, chunk)
            progress.addSent(sentThisBatch)
            cursor = end
            myBatches++
            val exhausted = cursor >= myIds.size
            val overBatchLimit = !exhausted && myBatches >= MAX_BATCHES_PER_PHASE
            myFinal = exhausted || overBatchLimit
            myTruncated = overBatchLimit
            connection.send(FrameCodec.encode(if (myFinal) Record.PhaseDone else Record.BatchDone))
        }

        suspend fun peerTurn() {
            if (peerFinal) return
            peerFinal = receiveOneBatch(connection, ingest, progress)
            peerBatches++
            if (peerFinal) {
                val summary = progress.summary
                progress.summary = summary.copy(
                    rejected = summary.rejected.merge(ingest.rejectionTotals),
                    blockedDropped = summary.blockedDropped + ingest.blockedDropped,
                    staleDropped = summary.staleDropped + ingest.staleDropped,
                    priorityPhaseCompleted = true,
                )
                return
            }
            // A peer that never finishes within MAX_BATCHES_PER_PHASE is aborted; what it already sent stays persisted.
            if (peerBatches >= MAX_BATCHES_PER_PHASE) throw Abort(AbortReason.OUT_OF_PHASE)
        }

        while (!myFinal || !peerFinal) {
            if (role == Role.INITIATOR) {
                myTurn()
                peerTurn()
            } else {
                peerTurn()
                myTurn()
            }
        }
        return ExchangeResult(myTruncated)
    }

    /** Sends the messages (and their authors' profiles) for one batch. No boundary marker. */
    private suspend fun deliverBatch(connection: Connection, ids: List<MessageId>): Int {
        val wire = store.readMessages(ids)
        wire.forEach { connection.send(FrameCodec.encode(Record.Message(it))) }

        // Names ride with content: a name is only sent once its author's content is being accepted.
        val authors = store.heldWithIds(ids.toSet()).mapTo(mutableSetOf<AuthorId>()) { it.author }
        store.readProfiles(authors).forEach {
            connection.send(FrameCodec.encode(Record.Profile(it)))
        }
        return wire.size
    }

    /** A one-directional delivery that may span several batches with no interleaved reads (the gossip phase's shape). */
    private suspend fun deliverAll(connection: Connection, ids: List<MessageId>): Int {
        var cursor = 0
        var sent = 0
        var batches = 0
        while (true) {
            val end = minOf(cursor + batchSize, ids.size)
            val chunk = ids.subList(cursor, end)
            sent += deliverBatch(connection, chunk)
            cursor = end
            batches++
            val final = cursor >= ids.size || batches >= MAX_BATCHES_PER_PHASE
            connection.send(FrameCodec.encode(if (final) Record.PhaseDone else Record.BatchDone))
            if (final) return sent
        }
    }

    // ---- gossip phase ----------------------------------------------------------------

    /** Incidental recent content, offered before it's sent; skippable, since everything actually asked for was already persisted. */
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

        // Only answer what we actually offered — answering anything else would let a peer probe our holdings.
        val offered = offer.toSet()
        return deliverAll(connection, asked.filter { it in offered })
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

        // Capped by our own budget, not by what the peer chose to offer.
        val asked = Reconciler.request(offered, held).take(GOSSIP_INTAKE_CAP)
        connection.send(FrameCodec.encode(Record.GossipRequest(asked)))

        val ingest = Ingest(
            blocklist = store.blocklist(),
            wants = priority.myScope.wants,
            windowCutoff = priority.myScope.windowCutoff,
            intakeCap = GOSSIP_INTAKE_CAP,
        )
        receiveAllBatches(connection, ingest, progress)
        store.finishPhase(clock.nowMillis())

        val summary = progress.summary
        progress.summary = summary.copy(
            rejected = summary.rejected.merge(ingest.rejectionTotals),
            blockedDropped = summary.blockedDropped + ingest.blockedDropped,
            staleDropped = summary.staleDropped + ingest.staleDropped,
            overBudgetDropped = summary.overBudgetDropped + ingest.overBudgetDropped,
        )
    }

    /** Reads one direction's delivery in full, however many batches it takes, with nothing else happening on our end meanwhile. */
    private suspend fun receiveAllBatches(connection: Connection, ingest: Ingest, progress: Progress) {
        var batches = 0
        while (true) {
            val final = receiveOneBatch(connection, ingest, progress)
            batches++
            if (final) return
            if (batches >= MAX_BATCHES_PER_PHASE) throw Abort(AbortReason.OUT_OF_PHASE)
        }
    }

    /** Reads one batch until a [Record.BatchDone]/[Record.PhaseDone] marker and persists it; returns true if the delivery is now finished. */
    private suspend fun receiveOneBatch(
        connection: Connection,
        ingest: Ingest,
        progress: Progress,
    ): Boolean {
        var receivedThisBatch = 0
        var final: Boolean
        while (true) {
            when (val record = next(connection)) {
                is Record.Message -> {
                    // Generous slack for a batch already mid-flight, not an invitation to stream forever.
                    if (++receivedThisBatch > batchSize * 2) throw Abort(AbortReason.OUT_OF_PHASE)
                    ingest.offerMessage(record.wire)
                    if (ingest.rejectionCount > VERIFY_FAIL_CUTOFF) {
                        throw Abort(AbortReason.TOO_MANY_REJECTIONS)
                    }
                }
                is Record.Profile -> ingest.offerProfile(record.wire)
                Record.BatchDone -> {
                    final = false
                    break
                }
                Record.PhaseDone -> {
                    final = true
                    break
                }
                is Record.Abort -> throw Abort(record.reason)
                else -> throw Abort(AbortReason.OUT_OF_PHASE)
            }
        }

        val outcome = ingest.drainBatch()
        if (!outcome.isEmpty) {
            store.apply(outcome, clock.nowMillis())
            val summary = progress.summary
            progress.summary = summary.copy(
                messagesAccepted = summary.messagesAccepted + outcome.accepted.size,
                profilesAccepted = summary.profilesAccepted + outcome.profiles.size,
            )
        }
        return final
    }

    // ---- plumbing --------------------------------------------------------------------

    /** One alternating exchange: whoever leads sends first. An `ABORT` here surfaces its real reason instead of a generic OUT_OF_PHASE. */
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

/** Decides what to do with each thing a peer sends — verify, drop if blocked, drop if stale, else accept — one instance per phase, not per batch. */
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
    val rejectionTotals: Map<RejectionReason, Int> get() = rejections.toMap()

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

        // effective_time is min(claimed, received), so a message can only be out of window by lying about its age.
        if (message.id !in wants && message.body.timestampMillis < windowCutoff) {
            staleDropped++
            return
        }

        // Checked last, so a gossip flood can't jump the budget by arriving before the checks that would drop it anyway.
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

    /** Snapshots and clears accepted messages/profiles since the last drain; rejection/drop counts stay cumulative for the phase. */
    fun drainBatch(): PhaseOutcome {
        val outcome = PhaseOutcome(accepted.toList(), profiles.toList(), emptyMap())
        accepted.clear()
        profiles.clear()
        return outcome
    }

    /** Malformed carries a detail string, which would make every failure its own map key. */
    private fun RejectionReason.normalised(): RejectionReason =
        if (this is RejectionReason.Malformed) RejectionReason.Malformed("malformed") else this
}
