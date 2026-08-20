package com.jonoshields.gossip.core.model

/** plan.md §3.4: `MSG_FORMAT_VERSION`. First field of every message. */
const val MESSAGE_FORMAT_VERSION: Int = 1

/** plan.md §3.4: `MSG_MAX_CHARS`, counted in Unicode scalar values after NFC. */
const val MSG_MAX_CHARS: Int = 320

/**
 * The signed part of a message — exactly the fields that go into the canonical preimage,
 * in the order they are serialised.
 *
 * `id` and `sig` deliberately live on [Message] instead of here. That makes "the id and
 * signature are excluded from the preimage" a structural fact — [CanonicalCodec] takes a
 * body and physically cannot see them — rather than a convention a later edit could
 * quietly break.
 *
 * The invariants are enforced at construction, so a body that exists is a body that is
 * encodable and within spec. Text must arrive already normalised: repairing it here would
 * change the bytes an id was computed over. Use [MessageFactory] to create messages, which
 * normalises on the author's behalf.
 */
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
