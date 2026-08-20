package com.jonoshields.gossip.core.model

import com.jonoshields.gossip.core.crypto.Ed25519

/**
 * One message on the wire:
 *
 *     id (32 bytes) || sig (64 bytes) || canonical preimage (variable)
 *
 * The preimage excludes `id` and `sig`, so it is not transmittable on its own. Fixed-width
 * prefixes mean no extra length header and a trivial split.
 */
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
