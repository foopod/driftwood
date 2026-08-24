package com.jonoshields.driftwood.core.model

import com.jonoshields.driftwood.core.crypto.Hex
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalCodecTest {

    @Test
    fun `encodes a known body to the exact expected bytes`() {
        val body = MessageBody(
            author = AuthorId.of(ByteArray(32) { 0x11 }),
            root = null,
            parent = null,
            timestampMillis = 1L,
            text = "hi",
        )

        // Hand-computed from the spec, field by field:
        //   v:         0001 01
        //   author:    0020 11 * 32
        //   root:      0000
        //   parent:    0000
        //   timestamp: 0008 0000000000000001
        //   text:      0002 6869        ("hi")
        val expected = "000101" +
            "0020" + "11".repeat(32) +
            "0000" +
            "0000" +
            "0008" + "0000000000000001" +
            "0002" + "6869"

        assertEquals(expected, Hex.encode(CanonicalCodec.encode(body)))
    }

    @Test
    fun `agrees with the independent reference implementation`() {
        // The milestone's stated done-when: two separate code paths agree byte-for-byte.
        val random = Random(20260820)
        repeat(10_000) { i ->
            val body = randomBody(random)
            assertArrayEquals(
                "body #$i disagreed: $body",
                ReferenceCanonicalCodec.encode(body),
                CanonicalCodec.encode(body),
            )
        }
    }

    @Test
    fun `encode then decode round trips`() {
        val random = Random(1234)
        repeat(2_000) {
            val body = randomBody(random)
            val decoded = CanonicalCodec.decode(CanonicalCodec.encode(body))
            assertTrue("expected success, got $decoded", decoded is BodyDecodeResult.Success)
            assertEquals(body, (decoded as BodyDecodeResult.Success).body)
        }
    }

    @Test
    fun `decoding then re-encoding reproduces the original bytes exactly`() {
        // The invariant relaying depends on. A relayed message is re-encoded from stored
        // fields, so if the decoder ever accepted something the encoder cannot reproduce,
        // the relayed copy would hash to a different id — and the next hop would reject it
        // with no way to tell why. Strict decoding is what makes this hold: every accepted
        // encoding is the canonical one.
        val random = Random(20260821)
        repeat(5_000) {
            val original = CanonicalCodec.encode(randomBody(random))
            val decoded = CanonicalCodec.decode(original)
            assertTrue("$decoded", decoded is BodyDecodeResult.Success)
            assertArrayEquals(
                original,
                CanonicalCodec.encode((decoded as BodyDecodeResult.Success).body),
            )
        }
    }

    @Test
    fun `field order is v author root parent timestamp text`() {
        // Swapping any two field values must change the bytes, which it only does if the
        // fields occupy fixed distinct positions.
        val a = AuthorId.of(ByteArray(32) { 0xAA.toByte() })
        val idA = MessageId.of(ByteArray(32) { 0x01 })
        val idB = MessageId.of(ByteArray(32) { 0x02 })

        val rootThenParent = MessageBody(author = a, root = idA, parent = idB, timestampMillis = 5, text = "x")
        val parentThenRoot = MessageBody(author = a, root = idB, parent = idA, timestampMillis = 5, text = "x")

        assertNotEquals(
            Hex.encode(CanonicalCodec.encode(rootThenParent)),
            Hex.encode(CanonicalCodec.encode(parentThenRoot)),
        )
    }

    @Test
    fun `absent root and parent encode as length zero not as 32 zero bytes`() {
        val body = MessageBody(
            author = AuthorId.of(ByteArray(32)),
            root = null,
            parent = null,
            timestampMillis = 0,
            text = "",
        )
        val explicitZeros = MessageBody(
            author = AuthorId.of(ByteArray(32)),
            root = MessageId.of(ByteArray(32)),
            parent = MessageId.of(ByteArray(32)),
            timestampMillis = 0,
            text = "",
        )
        // An all-zero id is a real (if absurd) id and must not collide with "absent".
        assertNotEquals(
            Hex.encode(CanonicalCodec.encode(body)),
            Hex.encode(CanonicalCodec.encode(explicitZeros)),
        )
        assertTrue(CanonicalCodec.encode(body).size < CanonicalCodec.encode(explicitZeros).size)
    }

    // ---- strict decoding: reject, never repair -------------------------------------

    private fun validEncoding(): ByteArray = CanonicalCodec.encode(
        MessageBody(
            author = AuthorId.of(ByteArray(32) { 0x07 }),
            root = MessageId.of(ByteArray(32) { 0x08 }),
            parent = null,
            timestampMillis = 99,
            text = "hello",
        )
    )

    private fun assertRejected(bytes: ByteArray, why: String) {
        val result = CanonicalCodec.decode(bytes)
        assertTrue("$why -> expected rejection, got $result", result is BodyDecodeResult.Malformed)
    }

    @Test
    fun `rejects trailing bytes`() {
        assertRejected(validEncoding() + byteArrayOf(0), "one extra byte after text")
        assertRejected(validEncoding() + ByteArray(50), "fifty extra bytes")
    }

    @Test
    fun `rejects truncation at every prefix`() {
        val valid = validEncoding()
        for (length in 0 until valid.size) {
            assertRejected(valid.copyOf(length), "truncated to $length of ${valid.size}")
        }
    }

    @Test
    fun `rejects a length prefix that overruns the buffer`() {
        val valid = validEncoding()
        val overrun = valid.copyOf()
        // Inflate the leading v-field length so it claims more than the buffer holds.
        overrun[0] = 0xFF.toByte()
        overrun[1] = 0xFF.toByte()
        assertRejected(overrun, "v length claims 65535 bytes")
    }

    @Test
    fun `rejects a wrong-width version field`() {
        val body = MessageBody(author = AuthorId.of(ByteArray(32)), root = null, parent = null, timestampMillis = 0, text = "")
        val valid = CanonicalCodec.encode(body)
        // v declared as 2 bytes instead of 1, with the rest shifted along.
        val broken = byteArrayOf(0x00, 0x02, 0x00, 0x01) + valid.copyOfRange(3, valid.size)
        assertRejected(broken, "v field is two bytes wide")
    }

    @Test
    fun `rejects a wrong-width timestamp field`() {
        val body = MessageBody(author = AuthorId.of(ByteArray(32)), root = null, parent = null, timestampMillis = 0, text = "")
        val valid = CanonicalCodec.encode(body)
        val timestampLengthOffset = 3 + 2 + 32 + 2 + 2
        val broken = valid.copyOf()
        broken[timestampLengthOffset] = 0x00
        broken[timestampLengthOffset + 1] = 0x04
        assertRejected(broken, "timestamp field declared as 4 bytes")
    }

    @Test
    fun `rejects root or parent lengths that are neither 0 nor 32`() {
        val body = MessageBody(author = AuthorId.of(ByteArray(32)), root = null, parent = null, timestampMillis = 0, text = "")
        val valid = CanonicalCodec.encode(body)
        val rootLengthOffset = 3 + 2 + 32
        for (badLength in intArrayOf(1, 16, 31, 33, 64)) {
            val broken = valid.copyOf(valid.size + badLength)
            System.arraycopy(valid, rootLengthOffset + 2, broken, rootLengthOffset + 2 + badLength, valid.size - rootLengthOffset - 2)
            broken[rootLengthOffset] = ((badLength shr 8) and 0xFF).toByte()
            broken[rootLengthOffset + 1] = (badLength and 0xFF).toByte()
            assertRejected(broken, "root length $badLength")
        }
    }

    @Test
    fun `rejects an unknown version`() {
        val body = MessageBody(author = AuthorId.of(ByteArray(32)), root = null, parent = null, timestampMillis = 0, text = "")
        val broken = CanonicalCodec.encode(body)
        broken[2] = 99
        assertRejected(broken, "v = 99")
    }

    @Test
    fun `rejects a negative timestamp`() {
        val body = MessageBody(author = AuthorId.of(ByteArray(32)), root = null, parent = null, timestampMillis = 0, text = "")
        val broken = CanonicalCodec.encode(body)
        val timestampValueOffset = 3 + 2 + 32 + 2 + 2 + 2
        broken[timestampValueOffset] = 0xFF.toByte()
        assertRejected(broken, "timestamp with the sign bit set")
    }

    @Test
    fun `rejects over-length text on decode`() {
        val body = MessageBody(
            author = AuthorId.of(ByteArray(32)),
            root = null,
            parent = null,
            timestampMillis = 0,
            text = "a".repeat(MSG_MAX_CHARS),
        )
        val valid = CanonicalCodec.encode(body)
        // Append one more character and grow the declared text length to match: structurally
        // well-formed, but one code point over the cap.
        val textLengthOffset = valid.size - MSG_MAX_CHARS - 2
        val broken = valid.copyOf(valid.size + 1)
        broken[broken.size - 1] = 'a'.code.toByte()
        val newLength = MSG_MAX_CHARS + 1
        broken[textLengthOffset] = ((newLength shr 8) and 0xFF).toByte()
        broken[textLengthOffset + 1] = (newLength and 0xFF).toByte()
        assertRejected(broken, "321 code points")
    }
}
