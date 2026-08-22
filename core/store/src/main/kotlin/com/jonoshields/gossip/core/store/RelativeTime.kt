package com.jonoshields.gossip.core.store

import java.util.concurrent.TimeUnit

/**
 * Human-scale "how long ago" for a message timestamp — the read that matters far more than
 * an exact instant in a list of messages, and the same bucketing every screen that shows a
 * timestamp wants, rather than each reinventing it.
 *
 * [nowMillis] is a parameter rather than read from the system clock so this stays as fast
 * and deterministic to test as the rest of this module.
 */
object RelativeTime {

    fun describe(timestampMillis: Long, nowMillis: Long): String {
        // A message from the future (clock skew, a synthetic fixture) reads as "just now"
        // rather than a nonsensical negative duration.
        val deltaMillis = (nowMillis - timestampMillis).coerceAtLeast(0)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(deltaMillis)
        val hours = TimeUnit.MILLISECONDS.toHours(deltaMillis)
        val days = TimeUnit.MILLISECONDS.toDays(deltaMillis)

        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> plural(minutes, "minute")
            hours < 24 -> plural(hours, "hour")
            days < 7 -> plural(days, "day")
            days < 30 -> plural(days / 7, "week")
            days < 365 -> plural(days / 30, "month")
            else -> plural(days / 365, "year")
        }
    }

    private fun plural(count: Long, unit: String): String =
        "$count $unit${if (count == 1L) "" else "s"} ago"
}
