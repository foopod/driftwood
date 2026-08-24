package com.jonoshields.driftwood.core.model

/** Message format version. First field of every message. */
const val MESSAGE_FORMAT_VERSION: Int = 1

/** Max message length in Unicode scalar values after NFC normalization. */
const val MSG_MAX_CHARS: Int = 320

/** The signed, preimage-serialised fields; `id`/`sig` live on [Message] so [CanonicalCodec] physically can't see them. */
data class MessageBody(
    val version: Int = MESSAGE_FORMAT_VERSION,
    val author: AuthorId,
    val root: MessageId?,
    val parent: MessageId?,
    val timestampMillis: Long,
    val text: String,
) {
    init {
        require(version == MESSAGE_FORMAT_VERSION) {
            "unsupported message format version: $version"
        }
        require(timestampMillis >= 0) {
            "timestamp must not be negative, was $timestampMillis"
        }
        require(MessageText.isWellFormed(text)) {
            "text contains an unpaired surrogate"
        }
        require(MessageText.isNormalized(text)) {
            "text must already be NFC-normalised"
        }
        require(MessageText.countCodePoints(text) <= MSG_MAX_CHARS) {
            "text is ${MessageText.countCodePoints(text)} characters, limit is $MSG_MAX_CHARS"
        }
    }

    /** A root is defined by an empty `root` field; its own id becomes the thread's id. */
    val isRoot: Boolean get() = root == null
}
