package com.jonoshields.gossip.core.model

/**
 * A signed, self-verifying message — the single data type in the system (plan.md §3.2).
 *
 * Every message is independently meaningful and independently verifiable. The [body]'s
 * `root` and `parent` links carry context and structure only, never validity or trust: a
 * missing parent costs context, never integrity.
 */
class Message internal constructor(
    val id: MessageId,
    signature: ByteArray,
    val body: MessageBody,
) {
    private val rawSignature: ByteArray = signature.copyOf()

    val signature: ByteArray get() = rawSignature.copyOf()

    internal fun unsafeSignature(): ByteArray = rawSignature

    /** A root is its own thread; a reply names the thread it belongs to. */
    val threadRoot: MessageId get() = body.root ?: id

    val isRoot: Boolean get() = body.isRoot

    override fun equals(other: Any?): Boolean =
        this === other || (other is Message && id == other.id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "Message(${id.toHex().take(12)}…, author=${body.author.toHex().take(8)}…, ${body.text.length} chars)"

    companion object {
        /**
         * Assembles a message without checking that [id] and [signature] actually match
         * [body]. Only for constructing test fixtures and for the verifier, which does the
         * checking itself. Anything else should go through [MessageFactory] or
         * [MessageVerifier].
         */
        fun unverified(id: MessageId, signature: ByteArray, body: MessageBody): Message =
            Message(id, signature, body)
    }
}
