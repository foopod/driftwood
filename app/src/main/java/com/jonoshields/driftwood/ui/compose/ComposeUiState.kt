package com.jonoshields.driftwood.ui.compose

import com.jonoshields.driftwood.core.model.MSG_MAX_CHARS
import com.jonoshields.driftwood.core.model.MessageText

/** What a reply is attached to, shown while writing so the relationship is never guesswork. */
sealed interface ReplyTarget {
    /** A new root. Belongs to no thread yet. */
    data object None : ReplyTarget

    /** Answering one specific message, whose text is quoted back. */
    data class Message(val text: String) : ReplyTarget

    /** Answering the thread rather than any message in it — deliberately, or because it's not held. */
    data object Thread : ReplyTarget
}

data class ComposeUiState(
    val text: String = "",
    val target: ReplyTarget = ReplyTarget.None,
    val sending: Boolean = false,
    val error: String? = null,
) {
    val isReply: Boolean get() = target != ReplyTarget.None

    /** Code points after NFC, not UTF-16 units — must agree with what the store will accept. */
    val characterCount: Int get() = MessageText.countCodePoints(MessageText.normalize(text))

    val remaining: Int get() = MSG_MAX_CHARS - characterCount

    val canSend: Boolean get() = !sending && text.isNotBlank() && remaining >= 0
}

sealed interface ComposeEffect {
    data object Posted : ComposeEffect
}
