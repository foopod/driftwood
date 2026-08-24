package com.jonoshields.driftwood.core.model

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IdsTest {

    @Test
    fun `equal content means equal ids`() {
        // The trap this type exists to close: ByteArray.equals is identity, so a data
        // class holding a raw ByteArray would give every id a wrong equals - fatal in a
        // content-addressed system where ids are map keys and set members.
        val a = MessageId.of(ByteArray(32) { it.toByte() })
        val b = MessageId.of(ByteArray(32) { it.toByte() })
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `ids work as set members and map keys`() {
        val bytes = ByteArray(32) { 3 }
        val set = hashSetOf(MessageId.of(bytes), MessageId.of(bytes.copyOf()))
        assertEquals(1, set.size)
        assertTrue(MessageId.of(bytes.copyOf()) in set)

        val map = hashMapOf(MessageId.of(bytes) to "value")
        assertEquals("value", map[MessageId.of(bytes.copyOf())])
    }

    @Test
    fun `differing content means differing ids`() {
        assertNotEquals(MessageId.of(ByteArray(32)), MessageId.of(ByteArray(32) { if (it == 31) 1 else 0 }))
    }

    @Test
    fun `an id and an author id are never equal even with identical bytes`() {
        // They are both 32 bytes and both come out of crypto, so nothing but the type
        // stops one being passed where the other belongs.
        val bytes = ByteArray(32) { 9 }
        val messageId: Any = MessageId.of(bytes)
        val authorId: Any = AuthorId.of(bytes)
        assertNotEquals(messageId, authorId)
    }

    @Test
    fun `ids are defensively copied on the way in and out`() {
        val mutable = ByteArray(32) { 1 }
        val id = MessageId.of(mutable)
        mutable[0] = 99
        assertEquals(1, id.toByteArray()[0].toInt())

        val exported = id.toByteArray()
        exported[0] = 42
        assertEquals(1, id.toByteArray()[0].toInt())
        assertNotSame(id.toByteArray(), id.toByteArray())
    }

    @Test
    fun `rejects wrong lengths`() {
        for (badLength in intArrayOf(0, 1, 31, 33, 64)) {
            try {
                MessageId.of(ByteArray(badLength))
                throw AssertionError("accepted a $badLength-byte id")
            } catch (expected: IllegalArgumentException) {
                // as intended
            }
        }
    }

    @Test
    fun `hex round trips`() {
        val random = Random(11)
        repeat(100) {
            val id = MessageId.of(random.nextBytes(32))
            assertEquals(id, MessageId.fromHex(id.toHex()))
            assertEquals(64, id.toHex().length)
        }
    }

    @Test
    fun `toString is short and does not pretend to be the full id`() {
        val id = MessageId.of(ByteArray(32) { 0xAB.toByte() })
        assertTrue(id.toString().length < id.toHex().length)
    }
}
