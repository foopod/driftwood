package com.jonoshields.driftwood.core.store

import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.MessageId
import com.jonoshields.driftwood.core.model.OrderKey

enum class EvictionReason {
    /** Author or thread is on the local blocklist. Beats everything, including favourite. */
    BLOCKED,

    /** `effective_time` fell outside the window. */
    OUT_OF_WINDOW,

    /** The author's partition share ran out and this was among their oldest. */
    OVER_FAIR_SHARE,
}

/** What a prune would do, returned rather than applied so the caller can inspect and test it before deleting. */
data class PruningPlan(
    val evict: Set<MessageId>,
    val reasons: Map<MessageId, EvictionReason>,
    val tiers: Map<MessageId, Tier>,
)

/** Fair-share pruning: budget split equally across authors present, applied in order (blocked, stale, favourited-exempt, classify, fair-share) since each stage changes what the next sees. */
object Pruner {

    fun plan(
        held: List<HeldMessage>,
        listen: Set<AuthorId>,
        blocklist: Blocklist,
        favourites: Favourites,
        budgets: PartitionBudgets,
        windowMillis: Long,
        nowMillis: Long,
    ): PruningPlan {
        val reasons = mutableMapOf<MessageId, EvictionReason>()

        // Blocked, then stale — a star survives the window but never a block.
        val surviving = held.filter { message ->
            val starred = message.threadRoot in favourites
            when {
                message.author in blocklist.authors || message.threadRoot in blocklist.roots -> {
                    reasons[message.id] = EvictionReason.BLOCKED
                    false
                }
                !starred && message.effectiveTime < nowMillis - windowMillis -> {
                    reasons[message.id] = EvictionReason.OUT_OF_WINDOW
                    false
                }
                else -> true
            }
        }

        // 4. Classify everything still held, favourited threads included.
        val tiers = TierClassifier.classify(surviving, listen)

        // 5. Fair share, per partition, ignoring favourited threads entirely.
        surviving
            .filterNot { it.threadRoot in favourites }
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
                // Oldest-first by effective_time, tie-broken by id, same as everywhere else.
                owned.sortedBy { OrderKey(it.effectiveTime, it.id) }
                    .take(owned.size - share)
                    .map { it.id }
            }
        }
    }

    /** Divides [budget] across authors, then one redistribution pass gives spare space to those over quota. */
    private fun allocate(
        byAuthor: Map<AuthorId, List<HeldMessage>>,
        budget: Int,
    ): Map<AuthorId, Int> {
        // Ascending author id, so leftovers land somewhere predictable and tests can assert it.
        val authors = byAuthor.keys.sorted()

        // Plain integer division would zero every author once the count exceeds the budget.
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
