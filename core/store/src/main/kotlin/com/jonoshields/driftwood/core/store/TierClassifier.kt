package com.jonoshields.driftwood.core.store

import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.MessageId

/** Sorts held messages into listen > context > gossip; context is earned by thread, not by author. */
object TierClassifier {

    fun classify(
        held: List<HeldMessage>,
        listen: Set<AuthorId>,
    ): Map<MessageId, Tier> {
        if (held.isEmpty()) return emptyMap()

        // Threads with at least one listened-to author; everything else in them is context.
        val threadsWithContext: Set<MessageId> = held
            .filter { it.author in listen }
            .mapTo(mutableSetOf()) { it.threadRoot }

        return held.associate { message ->
            message.id to when {
                message.author in listen -> Tier.LISTEN
                message.threadRoot in threadsWithContext -> Tier.CONTEXT
                else -> Tier.GOSSIP
            }
        }
    }
}
