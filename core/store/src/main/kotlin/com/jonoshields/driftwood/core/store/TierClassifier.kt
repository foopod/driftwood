package com.jonoshields.driftwood.core.store

import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.MessageId

/** Sorts held messages into follow > context > gossip; context is earned by thread, not by author. */
object TierClassifier {

    fun classify(
        held: List<HeldMessage>,
        follow: Set<AuthorId>,
    ): Map<MessageId, Tier> {
        if (held.isEmpty()) return emptyMap()

        // Threads with at least one followed author; everything else in them is context.
        val threadsWithContext: Set<MessageId> = held
            .filter { it.author in follow }
            .mapTo(mutableSetOf()) { it.threadRoot }

        return held.associate { message ->
            message.id to when {
                message.author in follow -> Tier.FOLLOW
                message.threadRoot in threadsWithContext -> Tier.CONTEXT
                else -> Tier.GOSSIP
            }
        }
    }
}
