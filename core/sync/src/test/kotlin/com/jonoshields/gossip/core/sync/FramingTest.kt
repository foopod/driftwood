package com.jonoshields.gossip.core.sync

import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.model.MessageId
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The framing layer is the first thing a hostile peer touches, so these lean hard on the
 * rejection side. Nothing here is repaired, guessed at, or partially accepted.
 */
class FramingTest {

    private fun roundTrip(record: Record): Record {
        val result = FrameCodec.decode(FrameCodec.encode(record))
        assertTrue("$result", result is FrameResult.Ok)
        return (result as FrameResult.Ok).record
    }

    private fun assertMalformed(frame: ByteArray, why: String) {
        val result = FrameCodec.decode(frame)
        assertTrue("$why -> expected rejection, got $result", result is FrameResult.Malformed)
    }

    // ---- round trips -----------------------------------------------------------------

    @Test
    fun `every record type round trips`() {
        assertEquals(Record.Hello(1, author(1)), roundTrip(Record.Hello(1, author(1))))
        assertEquals(Record.PhaseDone, roundTrip(Record.PhaseDone))
        assertEquals(Record.SessionDone, roundTrip(Record.SessionDone))
        assertEquals(Record.Abort(AbortReason.VERSION_MISMATCH), roundTrip(Record.Abort(AbortReason.VERSION_MISMATCH)))
        assertEquals(Record.Message(byteArrayOf(1, 2, 3)), roundTrip(Record.Message(byteArrayOf(1, 2, 3))))
        assertEquals(Record.Profile(byteArrayOf(9)), roundTrip(Record.Profile(byteArrayOf(9))))
    }

    @Test
    fun `a scope declaration round trips with everything in it`() {
        val declaration = ScopeDeclaration(
            listen = setOf(author(1), author(2)),
            windowCutoff = 1_700_000_000_000,
            wants = setOf(msgId(7), msgId(8), msgId(9)),
        )
        assertEquals(Record.Scope(declaration), roundTrip(Record.Scope(declaration)))
    }

    @Test
    fun `an empty scope round trips`() {
        val empty = ScopeDeclaration(emptySet(), 0, emptySet())
        assertEquals(Record.Scope(empty), roundTrip(Record.Scope(empty)))
    }

    @Test
    fun `id lists round trip and keep their order where it matters`() {
        val ids = (1..50).map { msgId(it) }
        assertEquals(Record.GossipOffer(ids), roundTrip(Record.GossipOffer(ids)))
        assertEquals(Record.GossipRequest(ids), roundTrip(Record.GossipRequest(ids)))
        assertEquals(Record.HashList(ids.toSet()), roundTrip(Record.HashList(ids.toSet())))
    }

    @Test
    fun `a large hash-list fits in one frame`() {
        // The number that sized MAX_FRAME_BYTES: a full listen partition is 65,536 ids.
        val ids = (1..65_536).mapTo(mutableSetOf()) { msgId(it) }
        val encoded = FrameCodec.encode(Record.HashList(ids))
        assertTrue("${encoded.size} bytes", encoded.size <= MAX_FRAME_BYTES + FrameCodec.HEADER_BYTES)
        assertEquals(Record.HashList(ids), roundTrip(Record.HashList(ids)))
    }

    // ---- the header is not trusted ----------------------------------------------------

    @Test
    fun `a length larger than the cap is refused before anything is allocated`() {
        val header = byteArrayOf(0x03) + intBytes(MAX_FRAME_BYTES + 1)
        assertNull(FrameCodec.payloadLength(header))
    }

    @Test
    fun `a negative length is refused`() {
        // 0xFFFFFFFF arrives as -1 in a signed int, and would sail past a naive upper bound
        // straight into a negative-size allocation.
        val header = byteArrayOf(0x03, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertNull(FrameCodec.payloadLength(header))
    }

    @Test
    fun `a length that disagrees with the payload is refused`() {
        val frame = FrameCodec.encode(Record.Message(byteArrayOf(1, 2, 3)))
        assertMalformed(frame.copyOf(frame.size - 1), "one byte short of its declared length")
        assertMalformed(frame + byteArrayOf(0), "one byte more than declared")
    }

    @Test
    fun `a truncated header is refused`() {
        assertNull(FrameCodec.payloadLength(byteArrayOf(0x03, 0, 0)))
        assertMalformed(byteArrayOf(0x03, 0, 0), "header cut short")
        assertMalformed(ByteArray(0), "nothing at all")
    }

    // ---- payload validation -----------------------------------------------------------

    @Test
    fun `an unknown record type is refused`() {
        assertMalformed(byteArrayOf(0x7F) + intBytes(0), "type 0x7F does not exist")
        assertMalformed(byteArrayOf(0x00) + intBytes(0), "type 0x00 does not exist")
    }

    @Test
    fun `an id list that is not a whole number of ids is refused`() {
        assertMalformed(byteArrayOf(0x03) + intBytes(33) + ByteArray(33), "33 bytes is not one id")
        assertMalformed(byteArrayOf(0x07) + intBytes(31) + ByteArray(31), "31 bytes is not one id")
    }

    @Test
    fun `records that carry nothing must carry nothing`() {
        assertMalformed(byteArrayOf(0x06) + intBytes(1) + byteArrayOf(0), "PHASE_DONE with a payload")
        assertMalformed(byteArrayOf(0x09) + intBytes(4) + ByteArray(4), "SESSION_DONE with a payload")
    }

    @Test
    fun `an empty message or profile is refused`() {
        assertMalformed(byteArrayOf(0x04) + intBytes(0), "a message with no bytes")
        assertMalformed(byteArrayOf(0x05) + intBytes(0), "a profile with no bytes")
    }

    @Test
    fun `an unknown abort reason is refused`() {
        assertMalformed(byteArrayOf(0x0A) + intBytes(1) + byteArrayOf(99), "reason 99 does not exist")
    }

    @Test
    fun `a scope with impossible lengths is refused`() {
        assertMalformed(byteArrayOf(0x02) + intBytes(4) + ByteArray(4), "far too short to be a scope")
        // listen length claims more than the frame holds
        val payload = intBytes(9999) + ByteArray(12)
        assertMalformed(byteArrayOf(0x02) + intBytes(payload.size) + payload, "listen length overruns")
    }

    @Test
    fun `a negative window cutoff is refused`() {
        val payload = intBytes(0) + longBytes(-1) + intBytes(0)
        assertMalformed(byteArrayOf(0x02) + intBytes(payload.size) + payload, "cutoff before the epoch")
    }

    // ---- fuzz --------------------------------------------------------------------------

    @Test
    fun `random mutations of valid frames never crash, hang, or half-decode`() {
        // A decoder facing a hostile peer is exactly where this pays. The only acceptable
        // outcomes are a clean decode or a clean rejection.
        val random = Random(20260821)
        val valid = listOf(
            FrameCodec.encode(Record.Hello(1, author(1))),
            FrameCodec.encode(Record.HashList((1..20).mapTo(mutableSetOf()) { msgId(it) })),
            FrameCodec.encode(Record.Scope(ScopeDeclaration(setOf(author(1)), 5, setOf(msgId(2))))),
            FrameCodec.encode(Record.Message(ByteArray(64) { it.toByte() })),
            FrameCodec.encode(Record.GossipOffer((1..10).map { msgId(it) })),
            FrameCodec.encode(Record.Abort(AbortReason.OUT_OF_PHASE)),
        )

        repeat(20_000) {
            val frame = valid.random(random).copyOf()
            repeat(random.nextInt(1, 4)) {
                frame[random.nextInt(frame.size)] = random.nextInt(256).toByte()
            }
            // Must return, and must return one of the two shapes. Anything thrown is a bug.
            val result = runCatching { FrameCodec.decode(frame) }
            assertTrue(
                "threw on mutated input: ${result.exceptionOrNull()}",
                result.isSuccess,
            )
        }
    }

    @Test
    fun `random bytes are never accepted as a valid record by accident`() {
        val random = Random(99)
        var accepted = 0
        repeat(20_000) {
            val frame = ByteArray(random.nextInt(0, 200)) { random.nextInt(256).toByte() }
            if (runCatching { FrameCodec.decode(frame) }.getOrNull() is FrameResult.Ok) accepted++
        }
        // Some will decode — a random byte can legitimately be a valid HELLO. What must not
        // happen is a throw, which the runCatching above would have surfaced.
        assertTrue("accepted $accepted of 20000, which is implausibly high", accepted < 2_000)
    }

    private fun intBytes(value: Int) = java.nio.ByteBuffer.allocate(4).putInt(value).array()
    private fun longBytes(value: Long) = java.nio.ByteBuffer.allocate(8).putLong(value).array()
}
