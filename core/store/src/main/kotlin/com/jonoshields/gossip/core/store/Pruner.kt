package com.jonoshields.gossip.core.store

import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.model.MessageId
import com.jonoshields.gossip.core.model.OrderKey

enum class EvictionReason {
    /** Author or thread is on the local blocklist. Beats everything, including favourite. */
    BLOCKED,

    /** `effective_time` fell outside the window. */
    OUT_OF_WINDOW,

    /** The author's partition share ran out and this was among their oldest. */
    OVER_FAIR_SHARE,
}

/**
 * What a prune would do. Returned rather than applied so the caller can inspect it, test it,
 * and run the deletion inside one database transaction.
 */
data class PruningPlan(
    val evict: Set<MessageId>,
    val reasons: Map<MessageId, EvictionReason>,
    val tiers: Map<MessageId, Tier>,
)

/**
 * Fair-share pruning (plan.md §4).
 *
 * Storage is capped per tier, and within a tier the budget is divided **equally across the
 * authors present** rather than first-come-first-served — so one prolific account cannot
 * crowd out everyone else. That is the design's structural answer to spam: fairness comes
 * from how space is allocated, not from moderating what is said.
 *
 * The order of the rules is load-bearing, because each stage changes what the next one
 * sees:
 *
 *  1. **Blocked** — unconditional, ahead of everything, and favourite does not protect it.
 *  2. **Out of window** — anything older than the window, unless favourited.
 *  3. **Favourites** — removed from the arithmetic entirely: always kept, never counted.
 *  4. **Classify** what remains into tiers (favourites included, since a favourited message
 *     from a listened author still bumps its thread into context for everyone else).
 *  5. **Fair share** within each partition, evicting oldest-first.
 *
 * Pruning is local. Two devices are never required to agree on what they keep, so the
 * determinism below exists for testability, not for consensus.
 */
object Pruner {

    fun plan(
        held: List<HeldMessage>,
        listen: Set<AuthorId>,
        blocklist: Blocklist,
        budgets: PartitionBudgets,
        windowMillis: Long,
        nowMillis: Long,
    ): PruningPlan {
        val reasons = mutableMapOf<MessageId, EvictionReason>()

        // 1 & 2. Blocked, then stale. Favourite exempts from the window but never from a block.
        val surviving = held.filter { message ->
            when {
                message.author in blocklist.authors || message.threadRoot in blocklist.roots -> {
                    reasons[message.id] = EvictionReason.BLOCKED
                    false
                }
                !message.favourite && message.effectiveTime < nowMillis - windowMillis -> {
                    reasons[message.id] = EvictionReason.OUT_OF_WINDOW
                    false
                }
                else -> true
            }
        }

        // 4. Classify everything still held, favourites included.
        val tiers = TierClassifier.classify(surviving, listen)

        // 5. Fair share, per partition, over non-favourites only.
        surviving
            .filterNot { it.favourite }
            .groupBy { tiers.getValue(it.id) }
            .forEach { (tier, messages) ->
                evictOverQuota(messages, budgets[tier]).forEach { id ->
                    reasons[id] = EvictionReason.OVER_FAIR_SHARE
                }
            }

        return PruningPlan(evict = reasons.keys.toSet(), reasons = reasons, tiers = tiers)
    }

    /** Ids to evict from one partition so that every author lands within its share. */
    private fun evictOverQuota(messages: List<HeldMessage>, budget: Int): List<MessageId> {
        val byAuthor = messages.groupBy { it.author }
        if (byAuthor.isEmpty()) return emptyList()

        val shares = allocate(byAuthor, budget)

        return byAuthor.flatMap { (author, owned) ->
            val share = shares.getValue(author)
            if (owned.size <= share) {
                emptyList()
            } else {
                // Oldest-first by effective_time, tie-broken by id — reusing the ordering
                // the whole system already agrees on (plan.md §3.2).
                owned.sortedBy { OrderKey(it.effectiveTime, it.id) }
                    .take(owned.size - share)
                    .map { it.id }
            }
        }
    }

    /**
     * Divides [budget] across the authors present, then makes one redistribution pass
     * handing the space that under-users don't want to the authors that are over.
     *
     * plan.md §4 specifies exactly one pass, so a partition can finish under budget when a
     * very quiet author frees more than the over-quota authors can absorb. That is accepted
     * for MVP rather than looping to a fixpoint.
     */
    private fun allocate(
        byAuthor: Map<AuthorId, List<HeldMessage>>,
        budget: Int,
    ): Map<AuthorId, Int> {
        // Ascending author id: only so the leftovers land somewhere predictable and tests
        // can assert an exact answer.
        val authors = byAuthor.keys.sorted()

        // Integer division alone would give every author zero once the author count exceeds
        // the budget, which would wipe the partition instead of filling it. Handing out the
        // remainder is what prevents that.
        val base = budget / authors.size
        val remainder = budget % authors.size
        val shares = authors.withIndex().associate { (rank, author) ->
            author to base + if (rank < remainder) 1 else 0
        }.toMutableMap()

        val surplus = authors.sumOf { author ->
            (shares.getValue(author) - byAuthor.getValue(author).size).coerceAtLeast(0)
        }
        val needy = authors.filter { byAuthor.getValue(it).size > shares.getValue(it) }
        if (surplus == 0 || needy.isEmpty()) return shares

        val bonus = surplus / needy.size
        val bonusRemainder = surplus % needy.size
        needy.forEachIndexed { rank, author ->
            shares[author] = shares.getValue(author) + bonus + if (rank < bonusRemainder) 1 else 0
        }
        return shares
    }
}
