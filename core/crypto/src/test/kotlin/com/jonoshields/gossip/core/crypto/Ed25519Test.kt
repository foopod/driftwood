package com.jonoshields.gossip.core.crypto

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Known-answer tests against RFC 8032 §7.1.
 *
 * These prove *our wiring* — seed handling, key derivation, byte order — not that
 * BouncyCastle implements Ed25519. A library-only round-trip test would happily pass
 * while signing something other than what we think we're signing.
 */
class Ed25519Test {

    private data class Vector(
        val name: String,
        val seed: ByteArray,
        val publicKey: ByteArray,
        val message: ByteArray,
        val signature: ByteArray,
    )

    private val vectors: List<Vector> by lazy {
        val resource = checkNotNull(javaClass.getResourceAsStream("/rfc8032_ed25519_vectors.tsv")) {
            "RFC 8032 vector file missing from test resources"
        }
        resource.bufferedReader().readLines()
            .filterNot { it.startsWith("#") || it.isBlank() }
            .map { line ->
                val f = line.split("\t")
                require(f.size == 5) { "malformed vector line: $line" }
                Vector(f[0], Hex.decode(f[1]), Hex.decode(f[2]), Hex.decode(f[3]), Hex.decode(f[4]))
            }
    }

    @Test
    fun `vector file is present and well formed`() {
        assertEquals(5, vectors.size)
        vectors.forEach {
            assertEquals("${it.name} seed", 32, it.seed.size)
            assertEquals("${it.name} public key", 32, it.publicKey.size)
            assertEquals("${it.name} signature", 64, it.signature.size)
        }
    }

    @Test
    fun `public key derives from seed`() {
        vectors.forEach {
            assertArrayEquals(it.name, it.publicKey, Ed25519.publicKeyFromSeed(it.seed))
        }
    }

    @Test
    fun `signing reproduces the RFC signature exactly`() {
        // Ed25519 is deterministic: no nonce, so the signature is a pure function of
        // (seed, message). Any deviation here is a wiring bug, not randomness.
        vectors.forEach {
            assertArrayEquals(it.name, it.signature, Ed25519.sign(it.message, it.seed))
        }
    }

    @Test
    fun `RFC signatures verify`() {
        vectors.forEach {
            assertTrue(it.name, Ed25519.verify(it.message, it.signature, it.publicKey))
        }
    }

    @Test
    fun `a flipped bit anywhere in the signature fails verification`() {
        val v = vectors.first { it.message.isNotEmpty() }
        for (byteIndex in v.signature.indices) {
            val tampered = v.signature.copyOf()
            tampered[byteIndex] = (tampered[byteIndex].toInt() xor 0x01).toByte()
            assertFalse("byte $byteIndex", Ed25519.verify(v.message, tampered, v.publicKey))
        }
    }

    @Test
    fun `a flipped bit anywhere in the message fails verification`() {
        val v = vectors.first { it.message.size in 1..64 }
        for (byteIndex in v.message.indices) {
            val tampered = v.message.copyOf()
            tampered[byteIndex] = (tampered[byteIndex].toInt() xor 0x01).toByte()
            assertFalse("byte $byteIndex", Ed25519.verify(tampered, v.signature, v.publicKey))
        }
    }

    @Test
    fun `a signature does not verify under a different key`() {
        val a = vectors[1]
        val b = vectors[2]
        assertFalse(Ed25519.verify(a.message, a.signature, b.publicKey))
    }

    @Test
    fun `generated key pairs round trip`() {
        val random = Random(20260820)
        repeat(50) {
            val keyPair = Ed25519.generateKeyPair(random.nextBytes(32))
            val message = random.nextBytes(random.nextInt(0, 500))
            val signature = Ed25519.sign(message, keyPair.seed)

            assertEquals(32, keyPair.publicKey.size)
            assertEquals(64, signature.size)
            assertTrue(Ed25519.verify(message, signature, keyPair.publicKey))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a wrong-length seed is rejected`() {
        Ed25519.sign(ByteArray(0), ByteArray(31))
    }

    @Test
    fun `a wrong-length public key or signature fails rather than throwing`() {
        val v = vectors[1]
        assertFalse(Ed25519.verify(v.message, v.signature, ByteArray(31)))
        assertFalse(Ed25519.verify(v.message, ByteArray(63), v.publicKey))
    }
}
