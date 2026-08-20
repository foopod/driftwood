package com.jonoshields.gossip.core.store

import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.model.MessageId

/** plan.md §3.4 tunables. Named, never inlined, so tuning later is a one-line change. */
object StorageDefaults {
    /** `WINDOW_DEFAULT` — 90 days, user-configurable. */
    const val WINDOW_MILLIS: Long = 90L * 24 * 60 * 60 * 1000

    /** `NOMINAL_MSG_SIZE` — only used to turn a byte cap into a message-count budget. */
    const val NOMINAL_MSG_SIZE: Int = 512

    /** `PARTITION_SPLIT` — listen 0.50 / context 0.20 / gossip 0.30. */
    val SPLIT: PartitionSplit = PartitionSplit(listen = 0.50, context = 0.20, gossip = 0.30)

    /** A total store budget the user never has to think about. */
    const val TOTAL_BUDGET_BYTES: Long = 64L * 1024 * 1024
}

/**
 * The three storage tiers (plan.md §4). Every held message is in exactly one, and each has
 * its own hard cap — which is what makes total storage predictable.
 */
enum class Tier { LISTEN, CONTEXT, GOSSIP }

/**
 * Just enough about a stored message to decide its fate. Deliberately *not* the message
 * itself: pruning is a pure function over metadata, so it can be tested exhaustively
 * without a database.
 */
data class HeldMessage(
    val id: MessageId,
    val author: AuthorId,
    val threadRoot: MessageId,
    val effectiveTime: Long,
    val favourite: Boolean,
)

/**
 * Local and private (plan.md §3.3) — never declared to a peer, never part of a hash-list
 * diff. [roots] holds threads whose root was written by a blocked author, remembered
 * separately so replies stay blocked after the root message itself is pruned.
 */
data class Blocklist(val authors: Set<AuthorId>, val roots: Set<MessageId>)

data class PartitionSplit(val listen: Double, val context: Double, val gossip: Double) {
    init {
        require(listen >= 0 && context >= 0 && gossip >= 0) { "split fractions must not be negative" }
        require(kotlin.math.abs(listen + context + gossip - 1.0) < 1e-9) {
            "split must sum to 1.0, was ${listen + context + gossip}"
        }
    }
}

/** Per-partition budgets, in message counts. */
data class PartitionBudgets(val listen: Int, val context: Int, val gossip: Int) {
    init {
        require(listen >= 0 && context >= 0 && gossip >= 0) { "budgets must not be negative" }
    }

    operator fun get(tier: Tier): Int = when (tier) {
        Tier.LISTEN -> listen
        Tier.CONTEXT -> context
        Tier.GOSSIP -> gossip
    }
}

/** The user-facing storage settings, and how they become message-count budgets. */
data class StorageConfig(
    val totalBudgetBytes: Long = StorageDefaults.TOTAL_BUDGET_BYTES,
    val split: PartitionSplit = StorageDefaults.SPLIT,
    val windowMillis: Long = StorageDefaults.WINDOW_MILLIS,
) {
    fun budgets(nominalMessageSize: Int = StorageDefaults.NOMINAL_MSG_SIZE): PartitionBudgets {
        val total = totalBudgetBytes / nominalMessageSize
        return PartitionBudgets(
            listen = (total * split.listen).toInt(),
            context = (total * split.context).toInt(),
            gossip = (total * split.gossip).toInt(),
        )
    }
}
