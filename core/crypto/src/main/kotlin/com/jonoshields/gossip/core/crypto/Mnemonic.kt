package com.jonoshields.gossip.core.crypto

/** Outcome of turning a typed recovery phrase back into a seed. */
sealed interface MnemonicResult {
    class Success(val seed: ByteArray) : MnemonicResult

    data class WrongWordCount(val actual: Int) : MnemonicResult

    /** [position] is zero-based, so the UI can point at the word the user should re-check. */
    data class UnknownWord(val position: Int, val word: String) : MnemonicResult

    data object ChecksumMismatch : MnemonicResult
}

/**
 * Encodes a 32-byte Ed25519 seed as 24 words, and back (plan.md §3.1).
 *
 * The scheme is BIP-39's: 256 entropy bits followed by the first 8 bits of the entropy's
 * SHA-256 digest, giving 264 bits split into 24 groups of 11, each indexing a 2048-word
 * list. The standard English wordlist is used because it is deliberately built for being
 * written down and read back — no two words share their first four characters, and no two
 * are easily confused by ear.
 *
 * **This is an encoding, not BIP-39 key derivation.** There is no PBKDF2 and no passphrase;
 * the words *are* the seed, reversibly. Nothing here is compatible with a BIP-39 wallet and
 * no such compatibility is intended — a phrase from this app is not a wallet phrase, and a
 * wallet phrase is not an identity here.
 *
 * This matters more than most code in the project: the Keystore-wrapped copy of the seed
 * never leaves the device it was made on, so for a lost or replaced phone the phrase is the
 * only way back (plan.md §9).
 */
object Mnemonic {

    const val WORD_COUNT: Int = 24
    const val SEED_LENGTH: Int = 32

    private const val BITS_PER_WORD = 11
    private const val CHECKSUM_BITS = 8

    init {
        // The three constants are not independent — 256 + 8 = 264 = 24 × 11. Tying them
        // together here means changing one without the others fails loudly at load time
        // rather than silently truncating somebody's seed.
        check(SEED_LENGTH * 8 + CHECKSUM_BITS == WORD_COUNT * BITS_PER_WORD) {
            "mnemonic parameters do not line up: ${SEED_LENGTH * 8} + $CHECKSUM_BITS != " +
                "${WORD_COUNT * BITS_PER_WORD}"
        }
    }

    val wordlist: List<String> by lazy {
        val stream = checkNotNull(Mnemonic::class.java.getResourceAsStream("/bip39_english.txt")) {
            "bip39_english.txt is missing from core:crypto resources"
        }
        val words = stream.bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        check(words.size == 1 shl BITS_PER_WORD) {
            "wordlist must hold exactly ${1 shl BITS_PER_WORD} words, found ${words.size}"
        }
        words
    }

    private val indexByWord: Map<String, Int> by lazy {
        wordlist.withIndex().associate { (index, word) -> word to index }
    }

    fun encode(seed: ByteArray): List<String> {
        require(seed.size == SEED_LENGTH) {
            "seed must be $SEED_LENGTH bytes, was ${seed.size}"
        }
        val payload = seed + checksumByte(seed)
        return (0 until WORD_COUNT).map { word ->
            wordlist[readBits(payload, word * BITS_PER_WORD)]
        }
    }

    fun decode(words: List<String>): MnemonicResult {
        if (words.size != WORD_COUNT) return MnemonicResult.WrongWordCount(words.size)

        val payload = ByteArray(SEED_LENGTH + 1)
        words.forEachIndexed { position, word ->
            val index = indexByWord[word] ?: return MnemonicResult.UnknownWord(position, word)
            writeBits(payload, position * BITS_PER_WORD, index)
        }

        val seed = payload.copyOf(SEED_LENGTH)
        if (payload[SEED_LENGTH] != checksumByte(seed)) return MnemonicResult.ChecksumMismatch
        return MnemonicResult.Success(seed)
    }

    /** Tidies realistically typed input: any whitespace separates, case is irrelevant. */
    fun normalize(input: String): List<String> =
        input.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun checksumByte(seed: ByteArray): Byte = sha256(seed)[0]

    /** Reads [BITS_PER_WORD] bits starting at bit [offset], most significant bit first. */
    private fun readBits(payload: ByteArray, offset: Int): Int {
        var value = 0
        for (i in 0 until BITS_PER_WORD) {
            val bit = offset + i
            val byte = payload[bit ushr 3].toInt() and 0xFF
            value = (value shl 1) or ((byte ushr (7 - (bit and 7))) and 1)
        }
        return value
    }

    private fun writeBits(payload: ByteArray, offset: Int, value: Int) {
        for (i in 0 until BITS_PER_WORD) {
            val bit = offset + i
            if ((value ushr (BITS_PER_WORD - 1 - i)) and 1 == 1) {
                val index = bit ushr 3
                payload[index] = (payload[index].toInt() or (1 shl (7 - (bit and 7)))).toByte()
            }
        }
    }
}
