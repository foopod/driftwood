package com.jonoshields.gossip.core.sync

import com.jonoshields.gossip.core.store.Blocklist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The worked scenarios from `sync-spec.md` §6, run against the real reconciler.
 *
 * A specification whose examples were never executed drifts from the code silently, and a
 * reader has no way to tell. These assert the documented outcomes exactly, so if the
 * behaviour changes the document is what fails.
 */
class SpecScenariosTest {

    private val alice = author(1)
    private val bob = author(2)
    private val carol = author(3)
    private val dave = author(4)
    private val t1 = msgId(101)
    private val t2 = msgId(102)
    private val t3 = msgId(103)

    @Test
    fun `6_1 two friends who follow some of the same people`() {
        val m1 = held(carol, 1200, t1)
        val m2 = held(carol, 1100, t2)
        val m3 = held(alice, 1300, t1)
        val m4 = held(alice, 1500, t3)

        val aliceHolds = listOf(m1, m2, m3)
        val bobHolds = listOf(m1, m4)
        val aliceScope = scope(listen = setOf(carol), windowCutoff = 1000)
        val bobScope = scope(listen = setOf(alice, carol), windowCutoff = 900)

        assertEquals(setOf(m1.id, m2.id), Reconciler.hashList(aliceHolds, aliceScope))
        assertEquals(setOf(m1.id, m4.id), Reconciler.hashList(bobHolds, bobScope))

        val aliceToBob = Reconciler.plan(
            aliceHolds, bobScope, Reconciler.hashList(bobHolds, bobScope), noBlocks(),
        )
        assertEquals("newest first", listOf(m3.id, m2.id), aliceToBob.inScope)
        assertTrue("nothing left to offer", aliceToBob.context.isEmpty())

        val bobToAlice = Reconciler.plan(
            bobHolds, aliceScope, Reconciler.hashList(aliceHolds, aliceScope), noBlocks(),
        )
        assertTrue("Alice already has Bob's only Carol content", bobToAlice.isEmpty)
    }

    @Test
    fun `6_3 context keeps a conversation whole`() {
        val m1 = held(alice, 100, t1)
        val m2 = held(carol, 110, t1)
        val m3 = held(dave, 120, t1)

        val aliceHolds = listOf(m1, m2, m3)
        val bobHolds = listOf(m2)
        val bobScope = scope(listen = setOf(alice), windowCutoff = 0)

        val bobsList = Reconciler.hashList(bobHolds, bobScope)
        assertTrue("Bob holds nothing by Alice, the only person he follows", bobsList.isEmpty())

        val plan = Reconciler.plan(aliceHolds, bobScope, bobsList, noBlocks())
        assertEquals(listOf(m1.id), plan.inScope)

        // Both stranger replies are sent, including m2 which Bob already has. No hash-list
        // could have told Alice otherwise — Carol and Dave are in nobody's scope — and at
        // this scale the duplicate costs less than a round trip would.
        assertEquals("newest first", listOf(m3.id, m2.id), plan.context)
        // Of the two, only m3 is new to Bob; m2 is a duplicate he discards on arrival.
        val newToBob = Reconciler.request(plan.context, bobHolds.mapTo(mutableSetOf()) { it.id })
        assertEquals(listOf(m3.id), newToBob)
    }

    @Test
    fun `6_5 blocking is enforced by the sender, not announced`() {
        val carolsMessage = held(carol, 100, t1)
        val davesMessage = held(dave, 110, t2)
        val bobScope = scope(listen = setOf(carol, dave), windowCutoff = 0)

        val plan = Reconciler.plan(
            held = listOf(carolsMessage, davesMessage),
            peer = bobScope,
            peerHolds = emptySet(),
            blocklist = Blocklist(setOf(carol), emptySet()),   // Alice blocked Carol
        )

        assertTrue("never relay for someone you blocked", carolsMessage.id !in plan.inScope)
        assertEquals("Dave goes; Alice cannot know Bob blocked him", listOf(davesMessage.id), plan.inScope)
    }

    @Test
    fun `6_6 delivery respects the receiver's window, not the sender's`() {
        val old = held(carol, 600, t1)
        val recent = held(carol, 1100, t1)
        val bobKeepsOneMonth = scope(listen = setOf(carol), windowCutoff = 500)
        val aliceKeepsThree = scope(listen = setOf(carol), windowCutoff = 1000)

        val aliceToBob = Reconciler.plan(listOf(old, recent), bobKeepsOneMonth, emptySet(), noBlocks())
        assertEquals("Bob's wider window takes both", setOf(old.id, recent.id), aliceToBob.inScope.toSet())

        val bobToAlice = Reconciler.plan(listOf(old, recent), aliceKeepsThree, emptySet(), noBlocks())
        assertEquals("Alice's cutoff excludes the old one", listOf(recent.id), bobToAlice.inScope)
    }

    @Test
    fun `6_7 the priority phase is uncapped, and only context is bounded`() {
        val wants = (1..3).map { held(dave, it.toLong(), t3) }
        val inScope = (1..1500).map { held(carol, 1000L + it, t1) }
        val context = (1..1500).map { held(dave, 1000L + it, t1) }

        val plan = Reconciler.plan(
            held = inScope + wants + context,
            peer = scope(listen = setOf(carol), windowCutoff = 0, wants = wants.mapTo(mutableSetOf()) { it.id }),
            peerHolds = emptySet(),
            blocklist = noBlocks(),
            contextCap = 1000,
        )

        assertEquals("wants are never withheld", 3, plan.wanted.size)
        assertEquals("everything Bob follows goes", 1500, plan.inScope.size)
        assertEquals("context is bounded", 1000, plan.context.size)
        assertEquals("newest first", context.last().id, plan.context.first())
    }
}
