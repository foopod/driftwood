package com.jonoshields.gossip.ui.home

import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.model.Message
import com.jonoshields.gossip.core.model.MessageId

/**
 * Splits held messages into the two tabs (plan.md §6): a thread belongs in **Listening** the
 * moment any message in it is from someone you listen to — which necessarily keeps a
 * stranger's reply in that same thread out of Gossip too, so a followed conversation doesn't
 * fragment across tabs. Everything else is Gossip.
 */
object HomeThreadClassifier {

    fun classify(messages: List<Message>, listenScope: Set<AuthorId>): HomeUiState.Threads {
        val (listening, gossip) = messages
            .groupBy { it.threadRoot }
            .entries
            .partition { (_, inThread) -> inThread.any { it.body.author in listenScope } }

        return HomeUiState.Threads(
            listening = summarise(listening),
            gossip = summarise(gossip),
        )
    }

    private fun summarise(entries: List<Map.Entry<MessageId, List<Message>>>): List<ThreadSummary> =
        entries.map { (rootId, inThread) ->
            val root = inThread.firstOrNull { it.isRoot && it.id == rootId }
            ThreadSummary(
                rootId = rootId,
                // With the root pruned away the opening line is genuinely unknown, so
                // fall back to the oldest reply still held rather than inventing one.
                opening = root?.body?.text
                    ?: inThread.minByOrNull { it.body.timestampMillis }?.body?.text.orEmpty(),
                messageCount = inThread.size,
                newestTimestamp = inThread.maxOf { it.body.timestampMillis },
                rootHeld = root != null,
            )
        }.sortedByDescending { it.newestTimestamp }
}
