package com.jonoshields.driftwood.core.store

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Human-scale "how long ago" for a message timestamp, shared by every screen instead of each reinventing it. */
object RelativeTime {

    private val absoluteFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

    /** The plain wall-clock reading behind [describe] — for a tap-to-reveal toggle, not a default display. */
    fun absolute(timestampMillis: Long): String =
        absoluteFormatter.format(Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()))

    fun describe(timestampMillis: Long, nowMillis: Long): String {
        // Clock skew or a future-dated fixture reads as "just now", not a negative duration.
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
