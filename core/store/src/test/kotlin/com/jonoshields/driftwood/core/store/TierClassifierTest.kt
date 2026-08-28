package com.jonoshields.driftwood.core.store

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tier classification — context tier earns a stranger's message a place because it sits in a thread one of your people is in, not because of who wrote it. */
class TierClassifierTest {

    private val listened = author(1)
    private val stranger = author(2)
    private val otherStranger = author(3)
    private val threadA = msgId(100)
    private val threadB = msgId(200)

    private fun classify(messages: List<HeldMessage>, follow: Set<com.jonoshields.driftwood.core.model.AuthorId>) =
        TierClassifier.classify(messages, follow)

    @Test
    fun `a listened author's message is follow tier`() {
        val message = held(listened, 1, threadA)
        assertEquals(Tier.FOLLOW, classify(listOf(message), setOf(listened))[message.id])
    }

    @Test
    fun `an unrelated stranger's message is gossip tier`() {
        val message = held(stranger, 1, threadB)
        assertEquals(Tier.GOSSIP, classify(listOf(message), setOf(listened))[message.id])
    }

    @Test
    fun `a stranger in a listened author's thread is context tier`() {
        val mine = held(listened, 1, threadA)
        val theirs = held(stranger, 2, threadA)
        val tiers = classify(listOf(mine, theirs), setOf(listened))
        assertEquals(Tier.FOLLOW, tiers[mine.id])
        assertEquals(Tier.CONTEXT, tiers[theirs.id])
    }

    @Test
    fun `context does not reach across threads`() {
        val mine = held(listened, 1, threadA)
        val elsewhere = held(stranger, 2, threadB)
        val tiers = classify(listOf(mine, elsewhere), setOf(listened))
        assertEquals(Tier.GOSSIP, tiers[elsewhere.id])
    }

    @Test
    fun `follow beats context when both could apply`() {
        // Precedence is follow > context > gossip: an author you follow replying in a
        // thread you follow is a subscription, not context.
        val a = held(listened, 1, threadA)
        val b = held(listened, 2, threadA)
        val tiers = classify(listOf(a, b), setOf(listened))
        assertEquals(Tier.FOLLOW, tiers[a.id])
        assertEquals(Tier.FOLLOW, tiers[b.id])
    }

    @Test
    fun `several strangers in one context thread all become context`() {
        val mine = held(listened, 1, threadA)
        val s1 = held(stranger, 2, threadA)
        val s2 = held(otherStranger, 3, threadA)
        val tiers = classify(listOf(mine, s1, s2), setOf(listened))
        assertEquals(Tier.CONTEXT, tiers[s1.id])
        assertEquals(Tier.CONTEXT, tiers[s2.id])
    }

    @Test
    fun `adding a listened author reclassifies a whole thread`() {
        // One change to the follow set reclassifies both the thread (to context) and the author's own messages (to follow).
        val theirs = held(stranger, 1, threadA)
        val alsoTheirs = held(otherStranger, 2, threadA)
        val messages = listOf(theirs, alsoTheirs)

        val before = classify(messages, emptySet())
        assertEquals(Tier.GOSSIP, before[theirs.id])
        assertEquals(Tier.GOSSIP, before[alsoTheirs.id])

        val after = classify(messages, setOf(stranger))
        assertEquals(Tier.FOLLOW, after[theirs.id])
        assertEquals(Tier.CONTEXT, after[alsoTheirs.id])
    }

    @Test
    fun `removing a listened author demotes context back to gossip`() {
        val theirs = held(stranger, 1, threadA)
        val mine = held(listened, 2, threadA)
        val messages = listOf(theirs, mine)

        assertEquals(Tier.CONTEXT, classify(messages, setOf(listened))[theirs.id])
        assertEquals(Tier.GOSSIP, classify(messages, emptySet())[theirs.id])
    }

    @Test
    fun `classification does not know or care about starring`() {
        // Starring exempts a thread from the budget; it does not change what tier its
        // messages are in, and the classifier is not told about it at all.
        val message = held(stranger, 1, threadB)
        assertEquals(Tier.GOSSIP, classify(listOf(message), setOf(listened))[message.id])
    }

    @Test
    fun `every message gets exactly one tier`() {
        val messages = listOf(
            held(listened, 1, threadA),
            held(stranger, 2, threadA),
            held(otherStranger, 3, threadB),
        )
        val tiers = classify(messages, setOf(listened))
        assertEquals(messages.size, tiers.size)
        assertEquals(messages.map { it.id }.toSet(), tiers.keys)
    }

    @Test
    fun `an empty store classifies to nothing`() {
        assertEquals(emptyMap<Any, Any>(), classify(emptyList(), setOf(listened)))
    }
}
