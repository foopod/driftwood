package com.jonoshields.driftwood.core.model

import com.jonoshields.driftwood.core.crypto.sha256

/** Creates signed messages; a root leaves `root` empty since `root = id` can't be made true by hashing. */
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
        // Normalise before MessageBody counts length, or a peer could judge it over-length.
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
