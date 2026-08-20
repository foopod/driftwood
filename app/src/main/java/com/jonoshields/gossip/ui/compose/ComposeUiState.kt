package com.jonoshields.gossip.ui.compose

import com.jonoshields.gossip.core.model.MSG_MAX_CHARS
import com.jonoshields.gossip.core.model.MessageText

data class ComposeUiState(
    val text: String = "",
    val isReply: Boolean = false,
    val sending: Boolean = false,
    val error: String? = null,
) {
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
