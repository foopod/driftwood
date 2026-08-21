package com.jonoshields.gossip.core.sync

import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.model.MessageId
import com.jonoshields.gossip.core.model.OrderKey
import com.jonoshields.gossip.core.store.Blocklist
import com.jonoshields.gossip.core.store.HeldMessage

/**
 * Cap on the **context** a single session will offer (plan.md §3.4).
 *
 * Wants and in-scope content are deliberately *uncapped*: every message must verify against
 * the key that signed it, so a peer cannot manufacture content from people you follow. The
 * volume is bounded by what those people actually wrote, and that is exactly what you asked
 * for. A peer sending garbage instead is caught by `VERIFY_FAIL_CUTOFF`, not by a cap.
 *
 * Context is the exception, and the reason is social rather than cryptographic: it is written
 * by strangers, and a single thread has no size limit (§5). Someone can flood replies into a
 * thread one of your people is in, and every one of them verifies. So the one part of the
 * priority phase whose volume you did not choose is the one part that stays bounded.
 */
const val CONTEXT_OFFER_CAP: Int = 1000

/** Cap on the gossip phase, which is entirely unchosen content (plan.md §3.4). */
const val GOSSIP_INTAKE_CAP: Int = 1000

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
 * [contextOffer] is *offered*, not sent: context carries messages from authors in neither
 * side's scope, so no hash-list can describe them and the peer has to say which it lacks.
 */
data class Delivery(
    val wanted: List<MessageId>,
    val inScope: List<MessageId>,
    val contextOffer: List<MessageId>,
) {
    /** Everything actually streamed in the priority phase; the offer is not content. */
    val sendNow: List<MessageId> get() = wanted + inScope

    val isEmpty: Boolean get() = wanted.isEmpty() && inScope.isEmpty() && contextOffer.isEmpty()
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
        contextCap: Int = CONTEXT_OFFER_CAP,
    ): Delivery {
        require(contextCap >= 0) { "context cap must not be negative" }

        val sendable = held.filterNot {
            it.author in blocklist.authors || it.threadRoot in blocklist.roots
        }

        // Wants are explicit: they told us exactly which ids they lack, so there is nothing
        // to reconcile. Deliberately not window-filtered either — a want is only ever an id,
        // with no timestamp attached, so neither side can know how old it is until it
        // arrives. Filtering here would refuse content nobody could have known to exclude.
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

        // Context: the stranger-replies that keep their people's conversations whole. A
        // thread qualifies when we hold a message in it from someone they listen to —
        // computed from *our* holdings, since we cannot see theirs.
        val contextThreads = sendable.asSequence()
            .filter { it.author in peer.listen }
            .mapTo(mutableSetOf()) { it.threadRoot }

        val contextOffer = sendable
            .filter { it.threadRoot in contextThreads }
            .filter { it.effectiveTime >= peer.windowCutoff }
            // The offer exists to cover ids their hash-list *cannot* describe — bump
            // authors are in nobody's scope. Where it does describe one, that is knowledge
            // we already have, and offering it anyway would be asking a question we can
            // answer ourselves.
            .filter { it.id !in peerHolds && it.id !in alreadyPlanned }
            .sortedNewestFirst()

        // Only context is trimmed. Trimming is courtesy rather than defence in any case —
        // a hostile sender ignores it, so the receiver enforces its own limit (§5).
        return Delivery(
            wanted = wanted.map { it.id },
            inScope = inScope.map { it.id },
            contextOffer = contextOffer.take(contextCap).map { it.id },
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
}
