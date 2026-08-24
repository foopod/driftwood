package com.jonoshields.driftwood.core.model

import com.jonoshields.driftwood.core.crypto.Ed25519

/** Wire form: `id (32 bytes) || sig (64 bytes) || canonical preimage`, fixed-width so no length header is needed. */
object MessageCodec {

    const val ID_OFFSET: Int = 0
    const val SIGNATURE_OFFSET: Int = ID_LENGTH
    const val PREIMAGE_OFFSET: Int = ID_LENGTH + Ed25519.SIGNATURE_LENGTH

    fun encode(message: Message): ByteArray {
        val preimage = CanonicalCodec.encode(message.body)
        val out = ByteArray(PREIMAGE_OFFSET + preimage.size)
        message.id.unsafeBytes().copyInto(out, ID_OFFSET)
        message.unsafeSignature().copyInto(out, SIGNATURE_OFFSET)
        preimage.copyInto(out, PREIMAGE_OFFSET)
        return out
    }
}
