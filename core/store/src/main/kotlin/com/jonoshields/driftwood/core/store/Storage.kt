package com.jonoshields.driftwood.core.store

import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.MessageId

/** Storage tunables, named rather than inlined so tuning later is a one-line change. */
object StorageDefaults {
    /** `WINDOW_DEFAULT` — 90 days, user-configurable. */
    const val WINDOW_MILLIS: Long = 90L * 24 * 60 * 60 * 1000

    /** `NOMINAL_MSG_SIZE` — only used to turn a byte cap into a message-count budget. */
    const val NOMINAL_MSG_SIZE: Int = 512

    /** `PARTITION_SPLIT` — follow 0.50 / context 0.20 / gossip 0.30. */
    val SPLIT: PartitionSplit = PartitionSplit(follow = 0.50, context = 0.20, gossip = 0.30)

    /** A total store budget the user never has to think about. */
    const val TOTAL_BUDGET_BYTES: Long = 64L * 1024 * 1024
}

/** The three storage tiers; every held message is in exactly one, each with its own hard cap. */
enum class Tier { FOLLOW, CONTEXT, GOSSIP }

/** Just enough about a stored message to prune by, as pure metadata so pruning is testable without a database. */
data class HeldMessage(
    val id: MessageId,
    val author: AuthorId,
    val threadRoot: MessageId,
    val effectiveTime: Long,
)

/** Pinned thread roots (keyed by id, not the message, since you may not hold the root itself), exempt from pruning caps. */
@JvmInline
value class PinnedRoots(val roots: Set<MessageId>) {
    operator fun contains(threadRoot: MessageId): Boolean = threadRoot in roots

    companion object {
        val NONE = PinnedRoots(emptySet())
    }
}

/** Local and private, never declared to a peer; [roots] keeps blocked-author threads blocked after the root is pruned. */
data class Blocklist(val authors: Set<AuthorId>, val roots: Set<MessageId>)

data class PartitionSplit(val follow: Double, val context: Double, val gossip: Double) {
    init {
        require(follow >= 0 && context >= 0 && gossip >= 0) { "split fractions must not be negative" }
        require(kotlin.math.abs(follow + context + gossip - 1.0) < 1e-9) {
            "split must sum to 1.0, was ${follow + context + gossip}"
        }
    }
}

/** Per-partition budgets, in message counts. */
data class PartitionBudgets(val follow: Int, val context: Int, val gossip: Int) {
    init {
        require(follow >= 0 && context >= 0 && gossip >= 0) { "budgets must not be negative" }
    }

    operator fun get(tier: Tier): Int = when (tier) {
        Tier.FOLLOW -> follow
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
            follow = (total * split.follow).toInt(),
            context = (total * split.context).toInt(),
            gossip = (total * split.gossip).toInt(),
        )
    }
}
