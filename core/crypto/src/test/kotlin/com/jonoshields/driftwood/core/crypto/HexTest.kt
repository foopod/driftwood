package com.jonoshields.driftwood.core.crypto

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HexTest {

    @Test
    fun `encodes lowercase with leading zeros preserved`() {
        assertEquals("00", Hex.encode(byteArrayOf(0)))
        assertEquals("0f", Hex.encode(byteArrayOf(0x0F)))
        assertEquals("ff", Hex.encode(byteArrayOf(0xFF.toByte())))
        assertEquals("deadbeef", Hex.encode(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())))
        assertEquals("", Hex.encode(ByteArray(0)))
    }

    @Test
    fun `round trips arbitrary bytes`() {
        val random = Random(20260820)
        repeat(500) {
            val bytes = random.nextBytes(random.nextInt(0, 100))
            assertArrayEquals(bytes, Hex.decode(Hex.encode(bytes)))
        }
    }

    @Test
    fun `decodes uppercase as well as lowercase`() {
        assertArrayEquals(Hex.decode("deadbeef"), Hex.decode("DEADBEEF"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects odd length`() {
        Hex.decode("abc")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non-hex characters`() {
        Hex.decode("zz")
    }
}
