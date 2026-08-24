package com.jonoshields.driftwood.core.crypto

/** Outcome of turning a typed recovery phrase back into a seed. */
sealed interface MnemonicResult {
    class Success(val seed: ByteArray) : MnemonicResult

    data class WrongWordCount(val actual: Int) : MnemonicResult

    /** [position] is zero-based, so the UI can point at the word the user should re-check. */
    data class UnknownWord(val position: Int, val word: String) : MnemonicResult

    data object ChecksumMismatch : MnemonicResult
}

/** Encodes a 32-byte seed as 24 BIP-39 wordlist words and back — an encoding, not BIP-39 key derivation, so it is not wallet-compatible. */
object Mnemonic {

    const val WORD_COUNT: Int = 24
    const val SEED_LENGTH: Int = 32

    private const val BITS_PER_WORD = 11
    private const val CHECKSUM_BITS = 8

    init {
        // 256 + 8 = 264 = 24 × 11 — fail loudly at load time if that ever stops lining up.
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
