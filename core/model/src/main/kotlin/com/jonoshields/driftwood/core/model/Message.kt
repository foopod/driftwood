package com.jonoshields.driftwood.core.model

/** A signed, self-verifying message; `root`/`parent` links carry context only, never validity or trust. */
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
        /** Assembles without checking [id]/[signature] match [body] — for fixtures and [MessageVerifier] only. */
        fun unverified(id: MessageId, signature: ByteArray, body: MessageBody): Message =
            Message(id, signature, body)
    }
}
