package com.jonoshields.gossip.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTextTest {

    // Explicit escapes on purpose: as literal glyphs these two lines would look
    // identical in the source and a reformatter could silently collapse them.
    private val decomposed = "cafe\u0301"  // e + U+0301 combining acute: 5 code points
    private val composed = "caf\u00E9"     // U+00E9 precomposed: 4 code points

    @Test
    fun `normalisation composes equivalent forms`() {
        assertNotEquals(decomposed, composed)
        assertEquals(composed, MessageText.normalize(decomposed))
        assertEquals(composed, MessageText.normalize(composed))
    }

    @Test
    fun `normalisation is idempotent`() {
        val once = MessageText.normalize(decomposed)
        assertEquals(once, MessageText.normalize(once))
    }

    @Test
    fun `visually identical text produces one id`() {
        // The reason NFC exists in the spec at all (plan.md §3.2).
        val a = MessageText.normalize(decomposed)
        val b = MessageText.normalize(composed)
        assertEquals(
            CanonicalCodec.encode(bodyWithText(a)).toList(),
            CanonicalCodec.encode(bodyWithText(b)).toList(),
        )
    }

    @Test
    fun `counts code points not UTF-16 units`() {
        assertEquals(1, MessageText.countCodePoints("🙂"))   // 2 UTF-16 units
        assertEquals(4, MessageText.countCodePoints("🜁🜂🜃🜄")) // 8 UTF-16 units
        assertEquals(3, MessageText.countCodePoints("abc"))
        assertEquals(0, MessageText.countCodePoints(""))
    }

    @Test
    fun `normalisation happens before counting`() {
        // The order is load-bearing: decomposed is 5 code points, composed is 4. An author
        // counting before normalising and a peer counting after would disagree about the
        // same bytes.
        assertEquals(5, MessageText.countCodePoints(decomposed))
        assertEquals(4, MessageText.countCodePoints(MessageText.normalize(decomposed)))

        // A string that only fits under the cap once normalised must be accepted.
        val atCapAfterNormalising = "e\u0301".repeat(MSG_MAX_CHARS)
        assertEquals(MSG_MAX_CHARS * 2, MessageText.countCodePoints(atCapAfterNormalising))
        assertEquals(MSG_MAX_CHARS, MessageText.countCodePoints(MessageText.normalize(atCapAfterNormalising)))
        bodyWithText(MessageText.normalize(atCapAfterNormalising)) // must not throw
    }

    @Test
    fun `accepts exactly the cap and rejects one over`() {
        bodyWithText("a".repeat(MSG_MAX_CHARS))
        bodyWithText("🙂".repeat(MSG_MAX_CHARS)) // 320 code points, 640 UTF-16 units

        assertTrue(rejects { bodyWithText("a".repeat(MSG_MAX_CHARS + 1)) })
        assertTrue(rejects { bodyWithText("🙂".repeat(MSG_MAX_CHARS + 1)) })
    }

    @Test
    fun `rejects unpaired surrogates`() {
        // Encoding these to UTF-8 silently substitutes '?', which would break the
        // encode/decode/encode identity and let two peers derive different ids.
        assertTrue(rejects { bodyWithText("\uD83D") })          // lone high surrogate
        assertTrue(rejects { bodyWithText("\uDE00") })          // lone low surrogate
        assertTrue(rejects { bodyWithText("ok\uD83Dthen") })    // high surrogate mid-string
        assertTrue(rejects { bodyWithText("\uDE00\uD83D") })    // reversed pair
    }

    @Test
    fun `accepts properly paired surrogates`() {
        bodyWithText("😀") // 😀
    }

    @Test
    fun `rejects text that is not already normalised`() {
        // MessageBody holds canonical text by construction. Callers normalise first;
        // ingest rejects rather than silently repairing (which would change the bytes the
        // id was computed over).
        assertTrue(rejects { bodyWithText(decomposed) })
    }

    private fun rejects(block: () -> Unit): Boolean = try {
        block()
        false
    } catch (e: IllegalArgumentException) {
        true
    }
}
