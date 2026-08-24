package com.jonoshields.driftwood.core.model

import com.jonoshields.driftwood.core.crypto.Ed25519

/** Produces a signature over a canonical preimage; the real key storage is `KeystoreSeedCipher`. */
fun interface Signer {
    fun sign(preimage: ByteArray): ByteArray
}

/** Signs with a raw 32-byte Ed25519 seed held in memory. */
class Ed25519Signer(seed: ByteArray) : Signer {

    private val seed: ByteArray = seed.copyOf()

    val publicKey: AuthorId = AuthorId.of(Ed25519.publicKeyFromSeed(seed))

    override fun sign(preimage: ByteArray): ByteArray = Ed25519.sign(preimage, seed)
}
