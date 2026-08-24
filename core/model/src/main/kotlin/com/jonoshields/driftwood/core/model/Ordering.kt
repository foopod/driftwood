package com.jonoshields.driftwood.core.model

import kotlin.math.min

/** `min(claimed_timestamp, first_received_time)` — defends against forward-dating a message's feed position. */
object EffectiveTime {
    fun of(claimedMillis: Long, firstReceivedMillis: Long): Long =
        min(claimedMillis, firstReceivedMillis)
}

/** Deterministic sort key: effective time ascending, then id ascending as a total tiebreak. */
data class OrderKey(val effectiveTime: Long, val id: MessageId) : Comparable<OrderKey> {
    override fun compareTo(other: OrderKey): Int {
        val byTime = effectiveTime.compareTo(other.effectiveTime)
        return if (byTime != 0) byTime else id.compareTo(other.id)
    }
}
