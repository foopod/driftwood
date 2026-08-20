package com.jonoshields.gossip.core.crypto

import java.security.SecureRandom
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Ed25519 signing, per plan.md §3.2.
 *
 * Deliberately uses BouncyCastle's low-level `org.bouncycastle.crypto` API rather than
 * registering a JCE provider: Android bundles its own stripped-down BouncyCastle, and
 * adding a second provider named "BC" is a well-known way to break at runtime on some
 * devices while working fine in tests. Going straight to the primitives also means JVM
 * unit tests and the device execute byte-for-byte the same implementation, which is the
 * divergence M0 exists to rule out.
 */
object Ed25519 {

    const val SEED_LENGTH: Int = 32
    const val PUBLIC_KEY_LENGTH: Int = 32
    const val SIGNATURE_LENGTH: Int = 64

    /**
     * An identity. The [seed] is the 32-byte private key as RFC 8032 defines it — the
     * thing that must be backed up, and the only thing that cannot be recovered.
     */
    class KeyPair(val seed: ByteArray, val publicKey: ByteArray)

    fun generateKeyPair(seed: ByteArray): KeyPair {
        requireSeed(seed)
        return KeyPair(seed.copyOf(), publicKeyFromSeed(seed))
    }

    fun generateKeyPair(random: SecureRandom = SecureRandom()): KeyPair {
        val seed = ByteArray(SEED_LENGTH).also(random::nextBytes)
        return KeyPair(seed, publicKeyFromSeed(seed))
    }

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

    /**
     * Returns false rather than throwing for malformed input. Verification runs against
     * bytes from an untrusted peer, so "this doesn't verify" is the expected outcome for
     * garbage — not an exceptional condition the caller has to guard every call site for.
     */
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
