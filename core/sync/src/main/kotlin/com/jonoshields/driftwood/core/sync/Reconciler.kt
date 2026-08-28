package com.jonoshields.driftwood.core.sync

import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.MessageId
import com.jonoshields.driftwood.core.model.OrderKey
import com.jonoshields.driftwood.core.store.Blocklist
import com.jonoshields.driftwood.core.store.HeldMessage

/** Cap on the **context** a single session will send; wants and in-scope content stay uncapped since verification already bounds them. */
const val CONTEXT_SEND_CAP: Int = 1000

/** Cap on the gossip phase, which is entirely unchosen content. */
const val GOSSIP_INTAKE_CAP: Int = 1000

/** Fruitless syncs before a want is given up on — wants are opportunistic, never chased. */
const val WANT_TTL: Int = 10

/** What a peer tells us up front; [follow] is public in MVP since declaring it is how a peer knows what to send. */
data class ScopeDeclaration(
    val follow: Set<AuthorId>,
    /** Lower bound on `effective_time`: the peer will refuse anything older. */
    val windowCutoff: Long,
    val wants: Set<MessageId>,
)

/** What we owe a peer, in the order it should go; [context] is sent outright since no hash-list can describe it. */
data class Delivery(
    val wanted: List<MessageId>,
    val inScope: List<MessageId>,
    val context: List<MessageId>,
) {
    /** Everything streamed in the priority phase. */
    val sendNow: List<MessageId> get() = wanted + inScope + context

    val isEmpty: Boolean get() = sendNow.isEmpty()
}

/** Decides what one device owes another — pure, so it can be checked by set equality instead of reading frames. */
object Reconciler {

    /** What we already hold within our own follow set — scoped to our own scope, since a peer never re-sends outside it. */
    fun hashList(held: List<HeldMessage>, mine: ScopeDeclaration): Set<MessageId> =
        held.asSequence()
            .filter { it.author in mine.follow }
            .mapTo(mutableSetOf()) { it.id }

    /** What we owe [peer], given their declaration and [peerHolds] (their hash-list); [blocklist] is ours alone. */
    fun plan(
        held: List<HeldMessage>,
        peer: ScopeDeclaration,
        peerHolds: Set<MessageId>,
        blocklist: Blocklist,
        contextCap: Int = CONTEXT_SEND_CAP,
    ): Delivery {
        require(contextCap >= 0) { "context cap must not be negative" }

        val sendable = held.filterNot {
            it.author in blocklist.authors || it.threadRoot in blocklist.roots
        }

        // Wants are explicit and not window-filtered — a want has no timestamp to filter against until it arrives.
        val wanted = sendable
            .filter { it.id in peer.wants }
            .sortedNewestFirst()

        val alreadyPlanned = wanted.mapTo(mutableSetOf()) { it.id }

        // Filtered to their cutoff, not ours — anything older is bandwidth spent on something they'll drop.
        val inScope = sendable
            .filter { it.author in peer.follow }
            .filter { it.effectiveTime >= peer.windowCutoff }
            .filter { it.id !in peerHolds && it.id !in alreadyPlanned }
            .sortedNewestFirst()

        alreadyPlanned += inScope.map { it.id }

        // Context: threads with a message from someone the peer listens to, computed from our own holdings.
        val contextThreads = sendable.asSequence()
            .filter { it.author in peer.follow }
            .mapTo(mutableSetOf()) { it.threadRoot }

        val context = sendable
            .filter { it.threadRoot in contextThreads }
            .filter { it.effectiveTime >= peer.windowCutoff }
            .filter { it.id !in peerHolds && it.id !in alreadyPlanned }
            .sortedNewestFirst()

        // Only context is trimmed — a courtesy, since the receiver enforces its own limit either way.
        return Delivery(
            wanted = wanted.map { it.id },
            inScope = inScope.map { it.id },
            context = context.take(contextCap).map { it.id },
        )
    }

    /** Which of the offered ids we actually lack, order preserved so the sender's newest-first priority survives the round trip. */
    fun request(offered: List<MessageId>, held: Set<MessageId>): List<MessageId> =
        offered.filterNot { it in held }

    /** Newest first by `effective_time`, tie-broken by id. */
    private fun List<HeldMessage>.sortedNewestFirst(): List<HeldMessage> =
        sortedByDescending { OrderKey(it.effectiveTime, it.id) }
}
