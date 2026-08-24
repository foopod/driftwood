package com.jonoshields.driftwood.sync

import com.jonoshields.driftwood.core.sync.SessionResult
import com.jonoshields.driftwood.core.sync.SyncSummary

/** One human-readable line for a finished session, used by SyncViewModel and SyncScreen. */
object SyncSummaryText {

    fun describe(result: SessionResult): String = when (result) {
        is SessionResult.Completed -> describe(result.summary)
        is SessionResult.Aborted ->
            "Stopped (${result.reason.name.lowercase().replace('_', ' ')}): " + describe(result.summary)
    }

    private fun describe(summary: SyncSummary): String {
        if (summary.messagesAccepted == 0 && summary.profilesAccepted == 0) {
            return "Nothing new — already up to date."
        }
        val parts = mutableListOf<String>()
        if (summary.messagesAccepted > 0) parts += "${summary.messagesAccepted} messages"
        if (summary.profilesAccepted > 0) parts += "${summary.profilesAccepted} names"
        val suffix = if (summary.priorityPhaseTruncated) " More to come next sync." else ""
        return "Fetched " + parts.joinToString(", ") + "." + suffix
    }
}
