package com.jonoshields.driftwood.ui.home

import com.jonoshields.driftwood.core.data.ThreadSummary
import com.jonoshields.driftwood.core.model.AuthorId

/** One reply shown verbatim in a snippet card. */
data class ReplySnippet(val author: AuthorId, val text: String, val timestamp: Long)

/** What the reply-preview card(s) under a thread's root show, if anything. */
sealed interface ReplyPreview {
    data object None : ReplyPreview
    data class Snippets(val replies: List<ReplySnippet>) : ReplyPreview
    data class Summary(val count: Int, val names: List<AuthorId>, val moreCount: Int) : ReplyPreview
}

/** The resolved presentation of a [ThreadSummary]: what the root's dot/count say, and what the reply-preview card(s) below it show. */
data class ThreadPreviewUi(
    val rootUnread: Boolean,
    val replyCount: Int?,
    val preview: ReplyPreview,
    val previewUnread: Boolean,
)

internal const val SNIPPET_THRESHOLD = 2
private const val MAX_NAMED_AUTHORS = 2

/**
 * Resolves a [ThreadSummary] into what the feed card should show.
 *
 * Focus is "replies since you last opened this" (unread only) when the root is already read
 * and something new landed since; otherwise it's "all replies" — which also covers a thread
 * whose root was never held (missing root -> [ThreadSummary.rootUnread] is already false, so
 * this same condition naturally falls through to the unread-replies focus when there's
 * anything unread, or the all-replies focus otherwise).
 *
 * A thread with 1-2 replies in focus shows each known reply as its own quoted snippet card;
 * busier threads (3+) collapse to a single names-and-count summary card instead.
 */
internal fun computeThreadPreview(thread: ThreadSummary): ThreadPreviewUi {
    val focusUnread = !thread.rootUnread && thread.unreadReplyCount > 0

    val totalInFocus = if (focusUnread) thread.unreadReplyCount else thread.replyCount
    val knownInFocus = if (focusUnread) thread.knownUnreadReplyCount else thread.knownReplyCount
    val latestKnownAuthor = if (focusUnread) thread.latestKnownUnreadReplyAuthor else thread.latestKnownReplyAuthor
    val latestKnownText = if (focusUnread) thread.latestKnownUnreadReplyText else thread.latestKnownReplyText
    val latestKnownTimestamp = if (focusUnread) thread.latestKnownUnreadReplyTimestamp else thread.latestKnownReplyTimestamp
    val secondKnownAuthor = if (focusUnread) thread.secondKnownUnreadReplyAuthor else thread.secondKnownReplyAuthor
    val secondKnownText = if (focusUnread) thread.secondKnownUnreadReplyText else thread.secondKnownReplyText
    val secondKnownTimestamp = if (focusUnread) thread.secondKnownUnreadReplyTimestamp else thread.secondKnownReplyTimestamp

    // Fetched newest-first (that's what the naming/summary path needs); shown oldest-first so
    // stacked snippet cards read top-to-bottom like the conversation actually happened.
    val snippets = listOfNotNull(
        latestKnownAuthor?.let { author ->
            latestKnownText?.let { text -> latestKnownTimestamp?.let { ts -> ReplySnippet(author, text, ts) } }
        },
        secondKnownAuthor?.let { author ->
            secondKnownText?.let { text -> secondKnownTimestamp?.let { ts -> ReplySnippet(author, text, ts) } }
        },
    ).sortedBy { it.timestamp }

    val preview: ReplyPreview = when {
        totalInFocus == 0 -> ReplyPreview.None
        knownInFocus == 0 -> ReplyPreview.Summary(count = totalInFocus, names = emptyList(), moreCount = 0)
        totalInFocus <= SNIPPET_THRESHOLD && snippets.isNotEmpty() -> ReplyPreview.Snippets(snippets)
        else -> {
            val names = listOfNotNull(latestKnownAuthor, secondKnownAuthor).take(MAX_NAMED_AUTHORS)
            ReplyPreview.Summary(count = totalInFocus, names = names, moreCount = (knownInFocus - names.size).coerceAtLeast(0))
        }
    }

    return ThreadPreviewUi(
        rootUnread = thread.rootUnread,
        replyCount = if (thread.replyCount > 0) thread.replyCount else null,
        preview = preview,
        previewUnread = focusUnread,
    )
}
