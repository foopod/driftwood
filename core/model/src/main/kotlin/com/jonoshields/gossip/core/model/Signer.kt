package com.jonoshields.gossip.core.model

import com.jonoshields.gossip.core.crypto.Ed25519

/**
 * Produces a signature over a canonical preimage.
 *
 * An interface rather than a concrete key holder because how the private key is *stored*
 * is still open. plan.md §3.1 asks for it to live in the Android Keystore "where possible"
 * and also to be exportable for backup and multi-device import — and those cannot both
 * hold, since Keystore keys are non-exportable by design. The likely resolution is a
 * software key encrypted at rest under a Keystore-held AES key, but that is an M1
 * decision; this seam keeps M0 from baking in an assumption either way.
 */
fun interface Signer {
    fun sign(preimage: ByteArray): ByteArray
}

/** Signs with a raw 32-byte Ed25519 seed held in memory. */
class Ed25519Signer(seed: ByteArray) : Signer {

    private val seed: ByteArray = seed.copyOf()

    val publicKey: AuthorId = AuthorId.of(Ed25519.publicKeyFromSeed(seed))

    override fun sign(preimage: ByteArray): ByteArray = Ed25519.sign(preimage, seed)
}
