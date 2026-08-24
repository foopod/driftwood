package com.jonoshields.driftwood.core.crypto

/** Lowercase hex, used only at the edges — display, QR payloads, test vectors. */
object Hex {

    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val v = byte.toInt() and 0xFF
            out.append(DIGITS[v ushr 4])
            out.append(DIGITS[v and 0x0F])
        }
        return out.toString()
    }

    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex string must have an even length, was ${hex.length}" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = digit(hex[i * 2])
            val lo = digit(hex[i * 2 + 1])
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun digit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("not a hex digit: '$c'")
    }
}
