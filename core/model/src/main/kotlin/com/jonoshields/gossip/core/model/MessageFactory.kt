package com.jonoshields.gossip.core.model

import com.jonoshields.gossip.core.crypto.sha256

/**
 * Creates signed messages. Construction is a single pass — there is no two-pass fill and
 * no self-referential `root` field (plan.md §3.2):
 *
 *   1. assemble the body (a root leaves `root` empty)
 *   2. id = sha256(canonical preimage)
 *   3. sign the same preimage
 *
 * `root = id` cannot be made true by hashing, because changing `root` changes `id`. So a
 * root carries an empty `root` and its own id becomes the thread's id — which keeps every
 * field honest and the preimage single-pass.
 */
object MessageFactory {

    fun createRoot(
        author: AuthorId,
        text: String,
        timestampMillis: Long,
        signer: Signer,
    ): Message = create(author, root = null, parent = null, text, timestampMillis, signer)

    fun createReply(
        author: AuthorId,
        root: MessageId,
        parent: MessageId?,
        text: String,
        timestampMillis: Long,
        signer: Signer,
    ): Message = create(author, root, parent, text, timestampMillis, signer)

    private fun create(
        author: AuthorId,
        root: MessageId?,
        parent: MessageId?,
        text: String,
        timestampMillis: Long,
        signer: Signer,
    ): Message {
        // Normalise on the author's behalf, then let MessageBody enforce the rest. The
        // order matters: normalising after counting would let an author create a message a
        // peer then judges over-length.
        val body = MessageBody(
            author = author,
            root = root,
            parent = parent,
            timestampMillis = timestampMillis,
            text = MessageText.normalize(text),
        )
        val preimage = CanonicalCodec.encode(body)
        return Message(
            id = MessageId.of(sha256(preimage)),
            signature = signer.sign(preimage),
            body = body,
        )
    }
}
