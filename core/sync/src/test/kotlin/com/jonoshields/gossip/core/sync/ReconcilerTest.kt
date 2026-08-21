package com.jonoshields.gossip.core.sync

import com.jonoshields.gossip.core.model.MessageId
import com.jonoshields.gossip.core.store.Blocklist
import com.jonoshields.gossip.core.store.HeldMessage
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * plan.md §5 step 3. The claim under test is not "we converge" — that only shows nothing was
 * lost. It is that nothing is sent that the peer already has.
 */
class ReconcilerTest {

    private val theirAuthor = author(1)
    private val otherTheirs = author(2)
    private val stranger = author(9)
    private val threadA = msgId(100)
    private val threadB = msgId(200)

    // ---- hash-list -------------------------------------------------------------------

    @Test
    fun `the hash-list covers our own scope and nothing else`() {
        // Not the union of both scopes: a peer can only ever send us content in *our*
        // scope, so listing anything else costs bytes and discloses holdings for nothing.
        val mine = held(theirAuthor, 10)
        val notListened = held(stranger, 10)

        val list = Reconciler.hashList(listOf(mine, notListened), scope(listen = setOf(theirAuthor)))

        assertEquals(setOf(mine.id), list)
    }

    @Test
    fun `the hash-list is not window-filtered`() {
        // A starred thread is exempt from pruning (§4), so we can hold content older than
        // our own cutoff. Naming it is what stops a peer sending it to us again.
        val ancient = held(theirAuthor, effectiveTime = 1)
        val list = Reconciler.hashList(listOf(ancient), scope(setOf(theirAuthor), windowCutoff = 1_000))
        assertEquals(setOf(ancient.id), list)
    }

    @Test
    fun `an empty scope yields an empty hash-list`() {
        assertTrue(Reconciler.hashList(listOf(held(theirAuthor, 5)), scope()).isEmpty())
    }

    // ---- in-scope --------------------------------------------------------------------

    @Test
    fun `nothing is planned when the peer already holds it all`() {
        val a = held(theirAuthor, 10)
        val b = held(theirAuthor, 20)

        val delivery = Reconciler.plan(
            held = listOf(a, b),
            peer = scope(listen = setOf(theirAuthor)),
            peerHolds = setOf(a.id, b.id),
            blocklist = noBlocks(),
        )

        assertTrue("$delivery", delivery.isEmpty)
    }

    @Test
    fun `only the gap is planned, newest first`() {
        val old = held(theirAuthor, 10)
        val mid = held(theirAuthor, 20)
        val new = held(theirAuthor, 30)

        val delivery = Reconciler.plan(
            held = listOf(old, mid, new),
            peer = scope(listen = setOf(theirAuthor)),
            peerHolds = setOf(mid.id),
            blocklist = noBlocks(),
        )

        assertEquals(listOf(new.id, old.id), delivery.inScope)
    }

    @Test
    fun `content from authors they do not listen to is never in scope`() {
        val theirs = held(theirAuthor, 10, threadB)
        val unrelated = held(stranger, 10, threadB)

        val delivery = Reconciler.plan(
            held = listOf(theirs, unrelated),
            peer = scope(listen = setOf(theirAuthor)),
            peerHolds = emptySet(),
            blocklist = noBlocks(),
        )

        assertEquals(listOf(theirs.id), delivery.inScope)
    }

    @Test
    fun `content older than the peer's window is never sent`() {
        // Their cutoff, not ours: §4 refuses out-of-window messages on ingest, so sending
        // them is bandwidth spent on something they will drop.
        val tooOld = held(theirAuthor, 99)
        val fresh = held(theirAuthor, 100)

        val delivery = Reconciler.plan(
            held = listOf(tooOld, fresh),
            peer = scope(listen = setOf(theirAuthor), windowCutoff = 100),
            peerHolds = emptySet(),
            blocklist = noBlocks(),
        )

        assertEquals(listOf(fresh.id), delivery.inScope)
    }

    // ---- wants -----------------------------------------------------------------------

    @Test
    fun `a want is delivered even though it is out of scope`() {
        // The whole point of the want-list: it names ids, not authors, so it reaches
        // content no scope would have covered.
        val orphanParent = held(stranger, 10, threadB)

        val delivery = Reconciler.plan(
            held = listOf(orphanParent),
            peer = scope(listen = setOf(theirAuthor), wants = setOf(orphanParent.id)),
            peerHolds = emptySet(),
            blocklist = noBlocks(),
        )

        assertEquals(listOf(orphanParent.id), delivery.wanted)
    }

    @Test
    fun `a want we do not hold is simply not delivered`() {
        val delivery = Reconciler.plan(
            held = emptyList(),
            peer = scope(wants = setOf(msgId(777))),
            peerHolds = emptySet(),
            blocklist = noBlocks(),
        )
        assertTrue(delivery.wanted.isEmpty())
    }

    @Test
    fun `a want is never also counted as in-scope`() {
        val both = held(theirAuthor, 10)

        val delivery = Reconciler.plan(
            held = listOf(both),
            peer = scope(listen = setOf(theirAuthor), wants = setOf(both.id)),
            peerHolds = emptySet(),
            blocklist = noBlocks(),
        )

        assertEquals(listOf(both.id), delivery.wanted)
        assertTrue("must not be planned twice", delivery.inScope.isEmpty())
    }

    // ---- context -----------------------------------------------------------------

    @Test
    fun `stranger replies in their people's threads are sent as context`() {
        val theirs = held(theirAuthor, 10, threadA)
        val strangerReply = held(stranger, 20, threadA)

        val delivery = Reconciler.plan(
            held = listOf(theirs, strangerReply),
            peer = scope(listen = setOf(theirAuthor)),
            peerHolds = emptySet(),
            blocklist = noBlocks(),
        )

        assertEquals("what they follow", listOf(theirs.id), delivery.inScope)
        assertEquals("and the stranger's reply that completes the thread", listOf(strangerReply.id), delivery.context)
        assertTrue("both go in this phase", strangerReply.id in delivery.sendNow)
    }

    @Test
    fun `context the peer already holds is still not re-sent`() {
        // Their hash-list cannot describe most context, but where it does, spending bytes
        // on a duplicate would be pointless.
        val theirs = held(theirAuthor, 10, threadA)
        val strangerReply = held(stranger, 20, threadA)

        val delivery = Reconciler.plan(
            held = listOf(theirs, strangerReply),
            peer = scope(listen = setOf(theirAuthor, stranger)),
            peerHolds = setOf(strangerReply.id),
            blocklist = noBlocks(),
        )

        assertTrue(strangerReply.id !in delivery.sendNow)
    }

    @Test
    fun `the context does not reach across threads`() {
        val theirs = held(theirAuthor, 10, threadA)
        val elsewhere = held(stranger, 20, threadB)

        val delivery = Reconciler.plan(
            held = listOf(theirs, elsewhere),
            peer = scope(listen = setOf(theirAuthor)),
            peerHolds = emptySet(),
            blocklist = noBlocks(),
        )

        assertTrue("$delivery", delivery.context.isEmpty())
    }

    @Test
    fun `request narrows an offer to what is actually lacked, keeping order`() {
        val offered = listOf(msgId(1), msgId(2), msgId(3), msgId(4))
        assertEquals(
            listOf(msgId(1), msgId(3)),
            Reconciler.request(offered, held = setOf(msgId(2), msgId(4))),
        )
    }

    @Test
    fun `requesting from an empty store asks for everything offered`() {
        val offered = listOf(msgId(1), msgId(2))
        assertEquals(offered, Reconciler.request(offered, held = emptySet()))
    }

    // ---- blocking --------------------------------------------------------------------

    @Test
    fun `a blocked author is never relayed, even into their scope`() {
        // §4: we never relay for someone we blocked. Their blocklist is private, so they
        // filter again on ingest — both ends filter precisely because neither list travels.
        val blocked = held(theirAuthor, 10)

        val delivery = Reconciler.plan(
            held = listOf(blocked),
            peer = scope(listen = setOf(theirAuthor), wants = setOf(blocked.id)),
            peerHolds = emptySet(),
            blocklist = Blocklist(setOf(theirAuthor), emptySet()),
        )

        assertTrue("$delivery", delivery.isEmpty)
    }

    @Test
    fun `a blocked thread is excluded from both the delta and the offer`() {
        val theirs = held(theirAuthor, 10, threadA)
        val reply = held(stranger, 20, threadA)

        val delivery = Reconciler.plan(
            held = listOf(theirs, reply),
            peer = scope(listen = setOf(theirAuthor)),
            peerHolds = emptySet(),
            blocklist = Blocklist(emptySet(), setOf(threadA)),
        )

        assertTrue("$delivery", delivery.isEmpty)
    }

    // ---- caps and determinism --------------------------------------------------------

    @Test
    fun `content from people they follow is never capped`() {
        // Every message must verify against the key that signed it, so a peer cannot
        // manufacture content from someone you follow — the volume is bounded by what those
        // people actually wrote, which is exactly what was asked for. Garbage instead is
        // caught by the verification cutoff, not by a cap.
        val want = held(stranger, 1, threadB)
        val messages = (1..5_000).map { held(theirAuthor, it.toLong()) }

        val delivery = Reconciler.plan(
            held = messages + want,
            peer = scope(listen = setOf(theirAuthor), wants = setOf(want.id)),
            peerHolds = emptySet(),
            blocklist = noBlocks(),
            contextCap = 10,
        )

        assertEquals(listOf(want.id), delivery.wanted)
        assertEquals(5_000, delivery.inScope.size)
        assertEquals("newest first", messages.last().id, delivery.inScope.first())
    }

    @Test
    fun `context is capped, because a thread has no size limit`() {
        // The one part of the priority phase whose volume you did not choose: written by
        // strangers, into a thread that can grow without bound.
        val anchor = held(theirAuthor, 1, threadA)
        val strangerReplies = (1..50).map { held(stranger, it.toLong() + 1, threadA) }

        val delivery = Reconciler.plan(
            held = listOf(anchor) + strangerReplies,
            peer = scope(listen = setOf(theirAuthor)),
            peerHolds = emptySet(),
            blocklist = noBlocks(),
            contextCap = 10,
        )

        assertEquals(listOf(anchor.id), delivery.inScope)
        assertEquals(10, delivery.context.size)
        assertEquals("newest first", strangerReplies.last().id, delivery.context.first())
    }

    @Test
    fun `a zero context cap still delivers everything they follow`() {
        val theirs = held(theirAuthor, 10, threadA)
        val strangerReply = held(stranger, 20, threadA)

        val delivery = Reconciler.plan(
            held = listOf(theirs, strangerReply),
            peer = scope(listen = setOf(theirAuthor)),
            peerHolds = emptySet(),
            blocklist = noBlocks(),
            contextCap = 0,
        )

        assertEquals(listOf(theirs.id), delivery.inScope)
        assertTrue(delivery.context.isEmpty())
    }

    @Test
    fun `the plan does not depend on the order the store hands things over`() {
        val random = Random(7)
        val messages = (1..40).map {
            held(listOf(theirAuthor, otherTheirs, stranger).random(random), random.nextLong(0, 100),
                listOf(threadA, threadB).random(random))
        }
        val peer = scope(listen = setOf(theirAuthor, otherTheirs), windowCutoff = 10)

        val first = Reconciler.plan(messages, peer, emptySet(), noBlocks())
        val shuffled = Reconciler.plan(messages.shuffled(Random(3)), peer, emptySet(), noBlocks())

        assertEquals(first, shuffled)
    }

    // ---- the properties that matter --------------------------------------------------

    @Test
    fun `nothing is ever planned that the peer already holds`() {
        forEachRandomCase { held, peer, peerHolds, blocklist ->
            val delivery = Reconciler.plan(held, peer, peerHolds, blocklist)
            (delivery.inScope + delivery.context).forEach { id ->
                assertTrue("planned $id which the peer already holds", id !in peerHolds)
            }
        }
    }

    @Test
    fun `nothing in their scope and window is left behind`() {
        forEachRandomCase { held, peer, peerHolds, blocklist ->
            val delivery = Reconciler.plan(held, peer, peerHolds, blocklist, contextCap = Int.MAX_VALUE)
            val planned = (delivery.wanted + delivery.inScope + delivery.context).toSet()

            held.filter { it.author in peer.listen }
                .filter { it.effectiveTime >= peer.windowCutoff }
                .filterNot { it.id in peerHolds }
                .filterNot { it.author in blocklist.authors || it.threadRoot in blocklist.roots }
                .forEach { assertTrue("left ${it.id} behind", it.id in planned) }
        }
    }

    @Test
    fun `no message is planned twice in one direction`() {
        forEachRandomCase { held, peer, peerHolds, blocklist ->
            val delivery = Reconciler.plan(held, peer, peerHolds, blocklist, contextCap = Int.MAX_VALUE)
            val all = delivery.wanted + delivery.inScope + delivery.context
            assertEquals("planned the same id more than once", all.size, all.toSet().size)
        }
    }

    @Test
    fun `two sides reconciling the same pair of stores each cover only their own gaps`() {
        val random = Random(20260821)
        repeat(200) {
            val authors = (1..4).map { author(it) }
            val threads = listOf(threadA, threadB)
            fun store() = (1..random.nextInt(0, 25)).map {
                held(authors.random(random), random.nextLong(0, 100), threads.random(random))
            }
            val mine = store()
            val theirs = store()
            val myScope = scope(authors.filter { random.nextBoolean() }.toSet(), 0)
            val theirScope = scope(authors.filter { random.nextBoolean() }.toSet(), 0)

            val iSend = Reconciler.plan(
                mine, theirScope, Reconciler.hashList(theirs, theirScope), noBlocks(), Int.MAX_VALUE,
            )
            val theySend = Reconciler.plan(
                theirs, myScope, Reconciler.hashList(mine, myScope), noBlocks(), Int.MAX_VALUE,
            )

            // Neither side offers the other something it already had — which is the whole
            // reason the hash-lists are exchanged before anything is planned.
            val theirIds = theirs.mapTo(mutableSetOf()) { it.id }
            val myIds = mine.mapTo(mutableSetOf()) { it.id }
            (iSend.inScope + iSend.context).forEach {
                assertTrue("sent them something they had", it !in theirIds)
            }
            (theySend.inScope + theySend.context).forEach {
                assertTrue("they sent us something we had", it !in myIds)
            }
        }
    }

    private fun forEachRandomCase(
        check: (List<HeldMessage>, ScopeDeclaration, Set<MessageId>, Blocklist) -> Unit,
    ) {
        val random = Random(1234)
        repeat(300) {
            val authors = (1..5).map { author(it) }
            val threads = listOf(threadA, threadB, msgId(300))
            val messages = (1..random.nextInt(0, 40)).map {
                held(authors.random(random), random.nextLong(0, 100), threads.random(random))
            }
            val peer = ScopeDeclaration(
                listen = authors.filter { random.nextBoolean() }.toSet(),
                windowCutoff = random.nextLong(0, 60),
                wants = messages.filter { random.nextInt(6) == 0 }.mapTo(mutableSetOf()) { it.id },
            )
            val peerHolds = messages.filter { random.nextBoolean() }.mapTo(mutableSetOf()) { it.id }
            val blocklist = Blocklist(
                authors = authors.filter { random.nextInt(8) == 0 }.toSet(),
                roots = threads.filter { random.nextInt(8) == 0 }.toSet(),
            )
            check(messages, peer, peerHolds, blocklist)
        }
    }
}
