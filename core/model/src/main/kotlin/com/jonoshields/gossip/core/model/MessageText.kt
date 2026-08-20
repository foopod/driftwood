package com.jonoshields.gossip.core.model

import java.text.Normalizer

/**
 * Text rules for a message body (plan.md §3.2).
 *
 * The sequence is fixed and load-bearing: **normalise, then count, then serialise.**
 * NFC changes the code-point count — "e" + U+0301 is two code points, precomposed "é" is
 * one — so an author who counted before normalising and a peer who counted after would
 * disagree about whether the very same bytes are valid.
 */
object MessageText {

    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

    fun isNormalized(text: String): Boolean = Normalizer.isNormalized(text, Normalizer.Form.NFC)

    /** Unicode scalar values, not UTF-16 units: one emoji is one character, not two. */
    fun countCodePoints(text: String): Int = text.codePointCount(0, text.length)

    /**
     * Rejects unpaired surrogates. A Kotlin [String] can hold one, and encoding it to
     * UTF-8 silently substitutes '?' (U+003F) — which would break the
     * encode/decode/encode identity and let two peers derive different ids from what
     * looks like the same text.
     */
    fun isWellFormed(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isHighSurrogate() -> {
                    if (i + 1 >= text.length || !text[i + 1].isLowSurrogate()) return false
                    i += 2
                }
                c.isLowSurrogate() -> return false
                else -> i++
            }
        }
        return true
    }
}
