package com.jonoshields.driftwood.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HashingTest {

    // Verified against coreutils sha256sum rather than transcribed from memory.
    private val knownAnswers = listOf(
        "" to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "abc" to "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        "The quick brown fox jumps over the lazy dog"
            to "d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592",
    )

    @Test
    fun `matches known answers`() {
        knownAnswers.forEach { (input, expected) ->
            assertEquals(input, expected, Hex.encode(sha256(input.toByteArray(Charsets.UTF_8))))
        }
    }

    @Test
    fun `digest is always 32 bytes`() {
        listOf(0, 1, 31, 32, 33, 1000).forEach {
            assertEquals(HASH_LENGTH, sha256(ByteArray(it)).size)
        }
    }

    @Test
    fun `is deterministic across calls`() {
        val input = "gossip".toByteArray()
        assertArrayEquals(sha256(input), sha256(input))
    }

    @Test
    fun `a single flipped bit changes the digest`() {
        val a = ByteArray(64)
        val b = ByteArray(64).also { it[37] = 1 }
        assertNotEquals(Hex.encode(sha256(a)), Hex.encode(sha256(b)))
    }
}
