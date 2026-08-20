package com.jonoshields.gossip.ui.compose

import com.jonoshields.gossip.core.model.MSG_MAX_CHARS
import com.jonoshields.gossip.core.model.MessageText

/** What a reply is attached to, shown while writing so the relationship is never guesswork. */
sealed interface ReplyTarget {
    /** A new root. Belongs to no thread yet. */
    data object None : ReplyTarget

    /** Answering one specific message, whose text is quoted back. */
    data class Message(val text: String) : ReplyTarget

    /**
     * Answering the thread rather than any message in it — either deliberately, or because
     * the message being answered is not held. Both are ordinary (plan.md §3.2).
     */
    data object Thread : ReplyTarget
}

data class ComposeUiState(
    val text: String = "",
    val target: ReplyTarget = ReplyTarget.None,
    val sending: Boolean = false,
    val error: String? = null,
) {
    val isReply: Boolean get() = target != ReplyTarget.None

    /**
     * Counted the way the format counts: code points after NFC, not UTF-16 units. An emoji
     * is one character here, and so is a composed accent — the counter must agree with what
     * the store will accept or the user gets refused for something they can't see.
     */
    val characterCount: Int get() = MessageText.countCodePoints(MessageText.normalize(text))

    val remaining: Int get() = MSG_MAX_CHARS - characterCount

    val canSend: Boolean get() = !sending && text.isNotBlank() && remaining >= 0
}

sealed interface ComposeEffect {
    data object Posted : ComposeEffect
}
