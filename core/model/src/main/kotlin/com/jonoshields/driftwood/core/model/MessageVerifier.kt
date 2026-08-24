package com.jonoshields.driftwood.core.model

import com.jonoshields.driftwood.core.crypto.Ed25519
import com.jonoshields.driftwood.core.crypto.sha256

/** Why a message from a peer was refused. Counted per peer and per session. */
sealed interface RejectionReason {
    /** Structurally unreadable: bad framing, bad lengths, bad UTF-8, out-of-spec field. */
    data class Malformed(val detail: String) : RejectionReason

    /** The bytes do not hash to the id they arrived with. */
    data object IdMismatch : RejectionReason

    /** The signature does not verify under the claimed author's key. */
    data object BadSignature : RejectionReason
}

sealed interface VerifyResult {
    data class Valid(val message: Message) : VerifyResult
    data class Rejected(val reason: RejectionReason) : VerifyResult
}

/** Verifies a peer's message cheapest-check-first, hashing the preimage bytes exactly as received, never a re-encoding. */
object MessageVerifier {

    fun verify(wire: ByteArray): VerifyResult {
        if (wire.size < MessageCodec.PREIMAGE_OFFSET) {
            return rejected(
                RejectionReason.Malformed(
                    "wire form is ${wire.size} bytes, shorter than the " +
                        "${MessageCodec.PREIMAGE_OFFSET}-byte id and signature prefix"
                )
            )
        }

        val id = wire.copyOfRange(MessageCodec.ID_OFFSET, MessageCodec.SIGNATURE_OFFSET)
        val signature = wire.copyOfRange(MessageCodec.SIGNATURE_OFFSET, MessageCodec.PREIMAGE_OFFSET)
        val preimage = wire.copyOfRange(MessageCodec.PREIMAGE_OFFSET, wire.size)

        val body = when (val decoded = CanonicalCodec.decode(preimage)) {
            is BodyDecodeResult.Malformed -> return rejected(RejectionReason.Malformed(decoded.reason))
            is BodyDecodeResult.Success -> decoded.body
        }

        if (!sha256(preimage).contentEquals(id)) {
            return rejected(RejectionReason.IdMismatch)
        }

        if (!Ed25519.verify(preimage, signature, body.author.unsafeBytes())) {
            return rejected(RejectionReason.BadSignature)
        }

        return VerifyResult.Valid(Message.unverified(MessageId.of(id), signature, body))
    }

    private fun rejected(reason: RejectionReason) = VerifyResult.Rejected(reason)
}
