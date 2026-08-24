package com.jonoshields.driftwood.core.model

import java.text.Normalizer

/** Text rules for a message body: normalise, then count, then serialise — in that order, since NFC changes the code-point count. */
object MessageText {

    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

    fun isNormalized(text: String): Boolean = Normalizer.isNormalized(text, Normalizer.Form.NFC)

    /** Unicode scalar values, not UTF-16 units: one emoji is one character, not two. */
    fun countCodePoints(text: String): Int = text.codePointCount(0, text.length)

    /** Rejects unpaired surrogates, which UTF-8 encoding would silently mangle into '?'. */
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
