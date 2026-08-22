package com.jonoshields.gossip.ui.home

import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.model.Message
import com.jonoshields.gossip.core.model.MessageId

/**
 * Splits held messages into the two tabs (plan.md §6): a thread belongs in **Listening** the
 * moment any message in it is from someone you listen to, or from you — a thread you started
 * or joined is yours to keep track of regardless of who else is in it, and it necessarily
 * keeps a stranger's reply in that same thread out of Gossip too, so a followed conversation
 * doesn't fragment across tabs. Everything else is Gossip.
 */
object HomeThreadClassifier {

    fun classify(messages: List<Message>, listenScope: Set<AuthorId>, myAuthor: AuthorId?): HomeUiState.Threads {
        val scope = if (myAuthor != null) listenScope + myAuthor else listenScope
        val (listening, gossip) = messages
            .groupBy { it.threadRoot }
            .entries
            .partition { (_, inThread) -> inThread.any { it.body.author in scope } }

        return HomeUiState.Threads(
            listening = summarise(listening, scope),
            gossip = summarise(gossip, scope),
        )
    }

    private fun summarise(
        entries: List<Map.Entry<MessageId, List<Message>>>,
        scope: Set<AuthorId>,
    ): List<ThreadSummary> =
        entries.map { (rootId, inThread) ->
            val root = inThread.firstOrNull { it.isRoot && it.id == rootId }
            // Newest reply (never the root) from someone you listen to or from you, if any —
            // absent on every Gossip-tab thread by construction, since no one in scope
            // appears there at all.
            val latestListened = inThread
                .filter { it.id != rootId && it.body.author in scope }
                .maxByOrNull { it.body.timestampMillis }
            ThreadSummary(
                rootId = rootId,
                rootAuthor = root?.body?.author,
                rootText = root?.body?.text,
                rootTimestamp = root?.body?.timestampMillis,
                latestListenedAuthor = latestListened?.body?.author,
                latestListenedText = latestListened?.body?.text,
                latestListenedTimestamp = latestListened?.body?.timestampMillis,
                messageCount = inThread.size,
                newestTimestamp = inThread.maxOf { it.body.timestampMillis },
            )
        }.sortedByDescending { it.newestTimestamp }
}
