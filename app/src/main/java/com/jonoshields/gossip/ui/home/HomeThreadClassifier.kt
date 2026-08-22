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
            listening = summarise(listening, listenScope),
            gossip = summarise(gossip, listenScope),
        )
    }

    private fun summarise(
        entries: List<Map.Entry<MessageId, List<Message>>>,
        listenScope: Set<AuthorId>,
    ): List<ThreadSummary> =
        entries.map { (rootId, inThread) ->
            val root = inThread.firstOrNull { it.isRoot && it.id == rootId }
            // Newest reply (never the root) from someone you listen to, if any — absent on
            // every Gossip-tab thread by construction, since no listened author appears
            // there at all.
            val latestListened = inThread
                .filter { it.id != rootId && it.body.author in listenScope }
                .maxByOrNull { it.body.timestampMillis }
            ThreadSummary(
                rootId = rootId,
                rootAuthor = root?.body?.author,
                rootText = root?.body?.text,
                latestListenedAuthor = latestListened?.body?.author,
                latestListenedText = latestListened?.body?.text,
                messageCount = inThread.size,
                newestTimestamp = inThread.maxOf { it.body.timestampMillis },
            )
        }.sortedByDescending { it.newestTimestamp }
}
