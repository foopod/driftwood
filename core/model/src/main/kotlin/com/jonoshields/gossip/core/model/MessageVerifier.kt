package com.jonoshields.gossip.core.model

import com.jonoshields.gossip.core.crypto.Ed25519
import com.jonoshields.gossip.core.crypto.sha256

/** Why a message from a peer was refused. Counted per peer and per session (plan.md §5). */
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

/**
 * Verifies a message received from a peer (plan.md §3.2).
 *
 * Checks run cheapest-first — decode, structure, hash, signature — so a peer cannot make
 * us burn CPU verifying signatures over obvious garbage.
 *
 * Crucially the hash is taken over **the preimage bytes exactly as received**, never over
 * a re-encoding of the decoded fields. Re-encoding would silently repair a hostile or
 * buggy encoding, normalising away the very tampering the hash exists to catch.
 *
 * Anything that fails is rejected outright: not stored, not counted against any budget,
 * not forwarded. An unverifiable message's `author` field is by definition
 * unauthenticated, so it cannot be attributed to anyone's fair share — which would make
 * garbage carrying random author keys a cheap way to consume a peer's storage.
 */
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
