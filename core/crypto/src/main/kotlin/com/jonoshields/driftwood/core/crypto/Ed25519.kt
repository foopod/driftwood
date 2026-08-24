package com.jonoshields.driftwood.core.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** Ed25519 signing via BouncyCastle's low-level API, so JVM tests and the device match byte-for-byte. */
object Ed25519 {

    const val SEED_LENGTH: Int = 32
    const val PUBLIC_KEY_LENGTH: Int = 32
    const val SIGNATURE_LENGTH: Int = 64

    fun publicKeyFromSeed(seed: ByteArray): ByteArray {
        requireSeed(seed)
        return Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
    }

    fun sign(message: ByteArray, seed: ByteArray): ByteArray {
        requireSeed(seed)
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    /** Returns false rather than throwing for malformed input from an untrusted peer. */
    fun verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        if (signature.size != SIGNATURE_LENGTH) return false
        if (publicKey.size != PUBLIC_KEY_LENGTH) return false
        return try {
            val signer = Ed25519Signer()
            signer.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            signer.update(message, 0, message.size)
            signer.verifySignature(signature)
        } catch (e: IllegalArgumentException) {
            // A public key that isn't a valid curve point.
            false
        }
    }

    private fun requireSeed(seed: ByteArray) =
        require(seed.size == SEED_LENGTH) {
            "Ed25519 seed must be $SEED_LENGTH bytes, was ${seed.size}"
        }
}
