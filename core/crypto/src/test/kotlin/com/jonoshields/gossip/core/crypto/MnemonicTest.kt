package com.jonoshields.gossip.core.crypto

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recovery phrase is the only way back from a lost device (the Keystore-wrapped copy of
 * the seed never migrates), so these tests carry unusual weight: a bug here is a permanently
 * lost identity, and it would not show up until someone actually needed to recover.
 */
class MnemonicTest {

    private data class Vector(val name: String, val seed: ByteArray, val words: List<String>)

    private val vectors: List<Vector> by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/mnemonic_vectors.tsv")) {
            "mnemonic vector file missing from test resources"
        }
        stream.bufferedReader().readLines()
            .filterNot { it.startsWith("#") || it.isBlank() }
            .map { line ->
                val f = line.split("\t")
                require(f.size == 3) { "malformed vector line: $line" }
                Vector(f[0], Hex.decode(f[1]), f[2].split(" "))
            }
    }

    // ---- the wordlist resource itself ------------------------------------------------

    @Test
    fun `wordlist has the BIP-39 structural properties`() {
        val words = Mnemonic.wordlist
        assertEquals(2048, words.size)
        assertEquals("all words unique", 2048, words.toSet().size)
        assertEquals("sorted", words.sorted(), words)
        assertTrue("all lowercase a-z", words.all { w -> w.all { it in 'a'..'z' } })
        // The property that lets a user type only the first four characters of each word.
        assertEquals("unique 4-char prefixes", 2048, words.map { it.take(4) }.toSet().size)
        assertEquals("abandon", words.first())
        assertEquals("zoo", words.last())
    }

    // ---- known answers, from an independent implementation ---------------------------

    @Test
    fun `vector file is present and well formed`() {
        assertEquals(8, vectors.size)
        vectors.forEach {
            assertEquals("${it.name} seed", 32, it.seed.size)
            assertEquals("${it.name} words", Mnemonic.WORD_COUNT, it.words.size)
        }
    }

    @Test
    fun `encoding matches the independent implementation`() {
        vectors.forEach {
            assertEquals(it.name, it.words, Mnemonic.encode(it.seed))
        }
    }

    @Test
    fun `decoding returns the original seed`() {
        vectors.forEach {
            val result = Mnemonic.decode(it.words)
            assertTrue("${it.name}: $result", result is MnemonicResult.Success)
            assertArrayEquals(it.name, it.seed, (result as MnemonicResult.Success).seed)
        }
    }

    @Test
    fun `round trips arbitrary seeds`() {
        val random = Random(20260821)
        repeat(2_000) {
            val seed = random.nextBytes(32)
            val decoded = Mnemonic.decode(Mnemonic.encode(seed))
            assertTrue(decoded is MnemonicResult.Success)
            assertArrayEquals(seed, (decoded as MnemonicResult.Success).seed)
        }
    }

    // ---- the checksum actually does something ----------------------------------------

    @Test
    fun `exactly eight of the 2048 possible last words are valid`() {
        // The last word carries 3 entropy bits plus all 8 checksum bits. For a fixed first
        // 23 words there are 8 possible entropy tails, each with exactly one correct
        // checksum — so precisely 8 of 2048 candidate last words can be valid. Anything
        // else means the checksum is not being computed over what we think it is.
        val base = vectors.first().words
        val valid = Mnemonic.wordlist.count { candidate ->
            Mnemonic.decode(base.dropLast(1) + candidate) is MnemonicResult.Success
        }
        assertEquals(8, valid)
    }

    @Test
    fun `a corrupted phrase never decodes to the original seed`() {
        // The guarantee that matters to someone restoring: a transcription error either
        // fails outright or produces a different identity. It must never silently hand back
        // a *different* seed while appearing to work, nor the original while being wrong.
        val random = Random(7)
        repeat(500) {
            val seed = random.nextBytes(32)
            val words = Mnemonic.encode(seed).toMutableList()

            val position = random.nextInt(words.size)
            val replacement = Mnemonic.wordlist[random.nextInt(2048)]
            if (replacement == words[position]) return@repeat
            words[position] = replacement

            when (val result = Mnemonic.decode(words)) {
                is MnemonicResult.Success ->
                    assertNotEquals(
                        "a corrupted phrase decoded back to the original seed",
                        Hex.encode(seed),
                        Hex.encode(result.seed),
                    )
                else -> Unit // rejected outright, which is the common and preferred case
            }
        }
    }

    @Test
    fun `swapping two different words is detected or changes the seed`() {
        val random = Random(11)
        repeat(500) {
            val seed = random.nextBytes(32)
            val words = Mnemonic.encode(seed).toMutableList()
            val i = random.nextInt(words.size)
            val j = random.nextInt(words.size)
            if (words[i] == words[j]) return@repeat
            words[i] = words[j].also { words[j] = words[i] }

            when (val result = Mnemonic.decode(words)) {
                is MnemonicResult.Success ->
                    assertNotEquals(Hex.encode(seed), Hex.encode(result.seed))
                else -> Unit
            }
        }
    }

    // ---- rejections carry enough detail to fix the mistake ---------------------------

    @Test
    fun `rejects the wrong number of words`() {
        val words = vectors.first().words
        listOf(words.dropLast(1), words + "zoo", emptyList()).forEach { candidate ->
            val result = Mnemonic.decode(candidate)
            assertTrue("$result", result is MnemonicResult.WrongWordCount)
            assertEquals(candidate.size, (result as MnemonicResult.WrongWordCount).actual)
        }
    }

    @Test
    fun `reports which word is not in the list`() {
        // Someone typing 24 words from paper needs to be told *which* one is wrong.
        val words = vectors.first().words.toMutableList()
        words[7] = "notaword"
        val result = Mnemonic.decode(words)
        assertTrue("$result", result is MnemonicResult.UnknownWord)
        assertEquals(7, (result as MnemonicResult.UnknownWord).position)
        assertEquals("notaword", result.word)
    }

    @Test
    fun `reports a checksum mismatch distinctly from an unknown word`() {
        val words = vectors.first().words.toMutableList()
        // A real word, wrong place: the list check passes and only the checksum catches it.
        words[3] = if (words[3] == "zoo") "abandon" else "zoo"
        assertEquals(MnemonicResult.ChecksumMismatch, Mnemonic.decode(words))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encoding rejects a wrong-length seed`() {
        Mnemonic.encode(ByteArray(31))
    }

    // ---- input tidying ---------------------------------------------------------------

    @Test
    fun `normalises realistic typed input`() {
        val expected = vectors.first().words
        val messy = "  " + expected.joinToString("  \n ") { it.uppercase() } + "\t"
        assertEquals(expected, Mnemonic.normalize(messy))
    }

    @Test
    fun `normalised input decodes`() {
        val v = vectors[4]
        val typed = v.words.joinToString("\n")
        val result = Mnemonic.decode(Mnemonic.normalize(typed))
        assertArrayEquals(v.seed, (result as MnemonicResult.Success).seed)
    }
}
