package com.jonoshields.gossip.core.store

import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.model.MessageId

/**
 * Sorts held messages into the three tiers (plan.md §4).
 *
 * The rule worth understanding is **context**, the thread-bump: a stranger's message earns
 * its place not because of who wrote it but because it sits in a thread one of your people
 * is in. That is what keeps your conversations whole without quietly turning every stranger
 * who replies to them into a subscription.
 *
 * Precedence is listen > context > gossip.
 */
object TierClassifier {

    fun classify(
        held: List<HeldMessage>,
        listen: Set<AuthorId>,
    ): Map<MessageId, Tier> {
        if (held.isEmpty()) return emptyMap()

        // Threads containing at least one message from someone you listen to. Computed once
        // up front rather than re-scanned per message.
        val bumpedThreads: Set<MessageId> = held
            .filter { it.author in listen }
            .mapTo(mutableSetOf()) { it.threadRoot }

        return held.associate { message ->
            message.id to when {
                message.author in listen -> Tier.LISTEN
                message.threadRoot in bumpedThreads -> Tier.CONTEXT
                else -> Tier.GOSSIP
            }
        }
    }
}
