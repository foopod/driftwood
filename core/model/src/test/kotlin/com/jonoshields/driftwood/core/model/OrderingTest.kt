package com.jonoshields.driftwood.core.model

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderingTest {

    // ---- effective time -------------------------------------------------------------

    @Test
    fun `a forward-dated message is clamped to when it arrived`() {
        // The defence that actually matters: nothing can claim to be newer than its
        // arrival, so it cannot buy feed position or extra time in the window.
        val arrived = 1_000L
        assertEquals(arrived, EffectiveTime.of(claimedMillis = 9_999L, firstReceivedMillis = arrived))
    }

    @Test
    fun `a backdated message keeps its claimed time`() {
        // Backdating is self-defeating rather than dangerous: the only thing it buys the
        // liar is their own content ageing out of the window sooner.
        assertEquals(500L, EffectiveTime.of(claimedMillis = 500L, firstReceivedMillis = 1_000L))
    }

    @Test
    fun `an honest message is unaffected`() {
        assertEquals(1_000L, EffectiveTime.of(claimedMillis = 1_000L, firstReceivedMillis = 1_000L))
    }

    @Test
    fun `an authored message uses its creation time`() {
        assertEquals(42L, EffectiveTime.of(claimedMillis = 42L, firstReceivedMillis = 42L))
    }

    // ---- order key ------------------------------------------------------------------

    private fun id(vararg bytes: Int): MessageId =
        MessageId.of(ByteArray(32).also { arr -> bytes.forEachIndexed { i, b -> arr[i] = b.toByte() } })

    @Test
    fun `orders by effective time ascending`() {
        val older = OrderKey(1_000L, id(0xFF))
        val newer = OrderKey(2_000L, id(0x00))
        assertTrue(older < newer)
    }

    @Test
    fun `breaks ties on equal timestamps by id`() {
        val a = OrderKey(1_000L, id(0x01))
        val b = OrderKey(1_000L, id(0x02))
        assertTrue(a < b)
    }

    @Test
    fun `compares id bytes as unsigned`() {
        // Kotlin's Byte is signed, so a naive comparison would sort 0x80..0xFF below 0x00.
        val low = OrderKey(0L, id(0x01))
        val high = OrderKey(0L, id(0xFF))
        assertTrue("0xFF must sort above 0x01", low < high)

        val justUnderSignBoundary = OrderKey(0L, id(0x7F))
        val justOver = OrderKey(0L, id(0x80))
        assertTrue("0x80 must sort above 0x7F", justUnderSignBoundary < justOver)
    }

    @Test
    fun `compares later bytes only when earlier bytes tie`() {
        assertTrue(OrderKey(0L, id(0x01, 0x00)) < OrderKey(0L, id(0x01, 0xFF)))
        assertTrue(OrderKey(0L, id(0x02, 0x00)) > OrderKey(0L, id(0x01, 0xFF)))
    }

    @Test
    fun `is a total order over a random sample`() {
        val random = Random(20260820)
        val keys = List(300) {
            OrderKey(random.nextLong(0, 5), MessageId.of(random.nextBytes(32)))
        }

        // Antisymmetry and consistency with equals.
        for (a in keys) {
            for (b in keys) {
                val ab = a.compareTo(b)
                val ba = b.compareTo(a)
                assertEquals("antisymmetry for $a vs $b", ab, -ba)
                if (ab == 0) assertEquals("compare==0 must imply equals", a, b)
            }
        }

        // Transitivity, spot-checked across the sorted sequence.
        val sorted = keys.sorted()
        for (i in 0 until sorted.size - 2) {
            assertTrue(sorted[i] <= sorted[i + 1])
            assertTrue(sorted[i] <= sorted[i + 2])
        }
    }

    @Test
    fun `sorting is stable regardless of input order`() {
        val random = Random(5)
        val keys = List(200) { OrderKey(random.nextLong(0, 3), MessageId.of(random.nextBytes(32))) }
        assertEquals(keys.sorted(), keys.shuffled(Random(1)).sorted())
        assertEquals(keys.sorted(), keys.reversed().sorted())
    }
}
