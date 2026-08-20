package com.jonoshields.gossip.core.model

import kotlin.math.min

/**
 * `effective_time = min(claimed_timestamp, first_received_time)` (plan.md §4).
 *
 * What this defends against is **forward-dating**: nothing can claim to be newer than the
 * moment it arrived, so a message cannot buy itself feed position or extra time inside the
 * window. Backdating is left self-defeating rather than blocked — the only thing a liar
 * buys is their own content ageing out sooner.
 *
 * `first_received_time` is local, never transmitted, and for a message this device
 * authored it is simply the creation time.
 */
object EffectiveTime {
    fun of(claimedMillis: Long, firstReceivedMillis: Long): Long =
        min(claimedMillis, firstReceivedMillis)
}

/**
 * The deterministic sort key: effective time ascending, then id ascending as a total
 * tiebreak, so every client converges on the same order even when timestamps collide or
 * lie (plan.md §3.2).
 */
data class OrderKey(val effectiveTime: Long, val id: MessageId) : Comparable<OrderKey> {
    override fun compareTo(other: OrderKey): Int {
        val byTime = effectiveTime.compareTo(other.effectiveTime)
        return if (byTime != 0) byTime else id.compareTo(other.id)
    }
}
