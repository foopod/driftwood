package com.jonoshields.gossip.core.sync

import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.model.MessageId
import com.jonoshields.gossip.core.model.OrderKey
import com.jonoshields.gossip.core.store.Blocklist
import com.jonoshields.gossip.core.store.HeldMessage

/** plan.md §3.4: max messages accepted per phase per session. */
const val SESSION_INTAKE_CAP: Int = 1000

/**
 * What a peer tells us up front (plan.md §5 step 2).
 *
 * [listen] is public in MVP — declaring it *is* the mechanism by which a peer knows what to
 * send, and the cost is that syncing with a stranger publishes your interests to them (§9).
 */
data class ScopeDeclaration(
    val listen: Set<AuthorId>,
    /** Lower bound on `effective_time`: the peer will refuse anything older (§4). */
    val windowCutoff: Long,
    val wants: Set<MessageId>,
)

/**
 * What we owe a peer, in the order it should go.
 *
 * [bumpOffer] is *offered*, not sent: thread-bump carries messages from authors in neither
 * side's scope, so no hash-list can describe them and the peer has to say which it lacks.
 */
data class Delivery(
    val wanted: List<MessageId>,
    val inScope: List<MessageId>,
    val bumpOffer: List<MessageId>,
) {
    /** Everything actually streamed in the priority phase; the offer is not content. */
    val sendNow: List<MessageId> get() = wanted + inScope

    val isEmpty: Boolean get() = wanted.isEmpty() && inScope.isEmpty() && bumpOffer.isEmpty()
}

/**
 * Decides what one device owes another (plan.md §5 step 3).
 *
 * Pure, and deliberately so: this is the single piece of the protocol that can be subtly
 * wrong while everything still *converges*, because converging only proves nothing was lost
 * — never that nothing was sent twice. Kept away from bytes and sockets, it can be checked
 * by set equality instead of by reading frames.
 */
object Reconciler {

    /**
     * Our side of the exchange: what we already hold of what we asked for.
     *
     * Scoped to **our own** listen set rather than the union of both. A peer only ever sends
     * us content in our scope, so ids outside it could never be re-sent to us and listing
     * them would be pure cost — in bytes, and in disclosure to a peer we may not know.
     *
     * Not window-filtered: a starred thread is exempt from pruning (§4), so we can hold
     * content older than our own cutoff, and naming it here is what stops a peer sending it
     * again.
     */
    fun hashList(held: List<HeldMessage>, mine: ScopeDeclaration): Set<MessageId> =
        held.asSequence()
            .filter { it.author in mine.listen }
            .mapTo(mutableSetOf()) { it.id }

    /**
     * What we owe [peer], given their declaration and the hash-list they sent.
     *
     * [peerHolds] is their hash-list — everything they already have within their own scope.
     * [blocklist] is *ours*: we never relay for someone we blocked (§4). Theirs is private
     * and stays that way, which is why they filter again on ingest.
     */
    fun plan(
        held: List<HeldMessage>,
        peer: ScopeDeclaration,
        peerHolds: Set<MessageId>,
        blocklist: Blocklist,
        cap: Int = SESSION_INTAKE_CAP,
    ): Delivery {
        require(cap >= 0) { "cap must not be negative" }

        val sendable = held.filterNot {
            it.author in blocklist.authors || it.threadRoot in blocklist.roots
        }

        // Wants are explicit: they told us exactly which ids they lack, so there is nothing
        // to reconcile and no window to respect — they asked for it by id.
        val wanted = sendable
            .filter { it.id in peer.wants }
            .sortedNewestFirst()

        val alreadyPlanned = wanted.mapTo(mutableSetOf()) { it.id }

        // In-scope: their authors, their window, minus what they told us they hold. Filtered
        // to *their* cutoff rather than ours — §4 refuses out-of-window messages on ingest,
        // so anything older is bandwidth spent on something they will drop.
        val inScope = sendable
            .filter { it.author in peer.listen }
            .filter { it.effectiveTime >= peer.windowCutoff }
            .filter { it.id !in peerHolds && it.id !in alreadyPlanned }
            .sortedNewestFirst()

        alreadyPlanned += inScope.map { it.id }

        // Thread-bump: the stranger-replies that keep their people's conversations whole.
        // A thread qualifies when we hold a message in it from someone they listen to —
        // computed from *our* holdings, since we cannot see theirs.
        val bumpedThreads = sendable.asSequence()
            .filter { it.author in peer.listen }
            .mapTo(mutableSetOf()) { it.threadRoot }

        val bumpOffer = sendable
            .filter { it.threadRoot in bumpedThreads }
            .filter { it.effectiveTime >= peer.windowCutoff }
            // The offer exists to cover ids their hash-list *cannot* describe — bump
            // authors are in nobody's scope. Where it does describe one, that is knowledge
            // we already have, and offering it anyway would be asking a question we can
            // answer ourselves.
            .filter { it.id !in peerHolds && it.id !in alreadyPlanned }
            .sortedNewestFirst()

        // Trimmed in priority order so that truncation drops the least valuable first. This
        // is courtesy, not defence: a hostile sender ignores it, so the receiver caps
        // independently (§5).
        val budget = Budget(cap)
        return Delivery(
            wanted = budget.take(wanted),
            inScope = budget.take(inScope),
            bumpOffer = budget.take(bumpOffer),
        )
    }

    /**
     * The other half of offer/request: which of the offered ids we actually lack.
     * Order is preserved, so the sender's newest-first priority survives the round trip.
     */
    fun request(offered: List<MessageId>, held: Set<MessageId>): List<MessageId> =
        offered.filterNot { it in held }

    /** Newest first by `effective_time`, tie-broken by id — the ordering §3.2 already fixes. */
    private fun List<HeldMessage>.sortedNewestFirst(): List<HeldMessage> =
        sortedByDescending { OrderKey(it.effectiveTime, it.id) }

    private class Budget(private var remaining: Int) {
        fun take(messages: List<HeldMessage>): List<MessageId> {
            val taken = messages.take(remaining)
            remaining -= taken.size
            return taken.map { it.id }
        }
    }
}
