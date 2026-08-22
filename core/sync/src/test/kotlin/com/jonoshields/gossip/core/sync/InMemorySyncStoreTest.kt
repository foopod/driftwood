package com.jonoshields.gossip.core.sync

import com.jonoshields.gossip.core.model.Ed25519Signer
import com.jonoshields.gossip.core.model.MessageFactory
import com.jonoshields.gossip.core.model.MessageId
import com.jonoshields.gossip.core.model.ProfileCodec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fake has to behave, or every convergence test built on it proves nothing. These check
 * the behaviours the session will lean on — not that it stores things, but that it forgets,
 * deduplicates and tracks wants the way the real store must.
 */
class InMemorySyncStoreTest {

    private val aliceSigner = Ed25519Signer(ByteArray(32) { it.toByte() })
    private val alice = aliceSigner.publicKey
    private val carolSigner = Ed25519Signer(ByteArray(32) { (it + 50).toByte() })
    private val carol = carolSigner.publicKey

    private var clock = 1_700_000_000_000L

    private fun aliceRoot(text: String) =
        MessageFactory.createRoot(alice, text, clock++, aliceSigner)

    private fun aliceReply(root: MessageId, parent: MessageId?, text: String) =
        MessageFactory.createReply(alice, root, parent, text, clock++, aliceSigner)

    @Test
    fun `finds what is held by author, thread and id`() = runTest {
        val root = aliceRoot("hello")
        val reply = MessageFactory.createReply(carol, root.id, root.id, "hi", clock++, carolSigner)
        val store = InMemorySyncStore().seed(root).seed(reply)

        assertEquals(setOf(root.id), store.heldBy(setOf(alice)).map { it.id }.toSet())
        assertEquals(setOf(root.id, reply.id), store.heldInThreads(setOf(root.id), 0).map { it.id }.toSet())
        assertEquals(listOf(reply.id), store.heldWithIds(setOf(reply.id)).map { it.id })
        assertTrue("ids we do not hold are simply absent", store.heldWithIds(setOf(msgId(999))).isEmpty())
    }

    @Test
    fun `the window cutoff excludes older content from a scoped read`() = runTest {
        val old = aliceRoot("old")
        val recent = aliceRoot("recent")
        val store = InMemorySyncStore().seed(old).seed(recent)

        val cutoff = recent.body.timestampMillis
        assertEquals(listOf(recent.id), store.heldBy(setOf(alice), since = cutoff).map { it.id })
    }

    @Test
    fun `newest held skips what the caller already accounted for`() = runTest {
        val first = aliceRoot("first")
        val second = aliceRoot("second")
        val third = aliceRoot("third")
        val store = InMemorySyncStore().seed(first).seed(second).seed(third)

        val newest = store.newestHeld(limit = 2, excluding = setOf(third.id))
        assertEquals("newest first, minus the exclusion", listOf(second.id, first.id), newest.map { it.id })
    }

    @Test
    fun `content is only read for the ids asked for, in that order`() = runTest {
        val a = aliceRoot("a")
        val b = aliceRoot("b")
        val store = InMemorySyncStore().seed(a).seed(b)

        val wire = store.readMessages(listOf(b.id, a.id))
        assertEquals(2, wire.size)
        assertTrue("order preserved", wire[0].contentEquals(
            com.jonoshields.gossip.core.model.MessageCodec.encode(b)))
    }

    @Test
    fun `applying is idempotent, because ids are content`() = runTest {
        val root = aliceRoot("once")
        val store = InMemorySyncStore()

        store.apply(PhaseOutcome(listOf(root), emptyList(), emptyMap()), receivedAtMillis = clock)
        store.apply(PhaseOutcome(listOf(root), emptyList(), emptyMap()), receivedAtMillis = clock + 5000)

        assertEquals(1, store.ids.size)
    }

    @Test
    fun `an arriving message satisfies the want it fills`() = runTest {
        val parent = aliceRoot("the missing parent")
        val store = InMemorySyncStore().want(parent.id)

        assertEquals(setOf(parent.id), store.outstandingWants)
        store.apply(PhaseOutcome(listOf(parent), emptyList(), emptyMap()), clock)
        assertTrue(store.outstandingWants.isEmpty())
    }

    @Test
    fun `an arriving reply whose parent is missing creates a want`() = runTest {
        val root = aliceRoot("root we will not hold")
        val orphan = aliceReply(root.id, root.id, "answers something absent")
        val store = InMemorySyncStore()

        store.apply(PhaseOutcome(listOf(orphan), emptyList(), emptyMap()), clock)

        assertEquals("we now know an id we lack", setOf(root.id), store.outstandingWants)
    }

    @Test
    fun `a want nobody can fill is dropped after WANT_TTL fruitless syncs`() = runTest {
        // The network is never interrogated: a message that has aged out everywhere is gone,
        // and chasing it forever would be the wrong shape for a medium that forgets.
        val store = InMemorySyncStore().want(msgId(4242))

        repeat(WANT_TTL - 1) { store.ageWants() }
        assertEquals("still hoping", setOf(msgId(4242)), store.outstandingWants)

        store.ageWants()
        assertTrue("given up", store.outstandingWants.isEmpty())
    }

    @Test
    fun `profiles arrive with content and can be read back`() = runTest {
        val profile = ProfileCodec.create(carol, "carol", clock, carolSigner)
        val store = InMemorySyncStore()

        store.apply(PhaseOutcome(emptyList(), listOf(profile), emptyMap()), clock)

        assertEquals("carol", store.usernameFor(carol))
        assertEquals(1, store.readProfiles(setOf(carol)).size)
        assertTrue("nothing for an author we know nothing about", store.readProfiles(setOf(alice)).isEmpty())
    }

    @Test
    fun `pruning after a session really removes things`() = runTest {
        // The fake prunes with the real Pruner, so a convergence test asserting caps means
        // something.
        val blocked = MessageFactory.createRoot(carol, "blocked", clock++, carolSigner)
        val kept = aliceRoot("kept")
        val store = InMemorySyncStore().seed(blocked).seed(kept).block(authors = setOf(carol))

        store.pruneAfterSession(nowMillis = clock)

        assertFalse(store.holds(blocked.id))
        assertTrue(store.holds(kept.id))
    }

    @Test
    fun `the blocklist is reported for planning but never leaves as content`() = runTest {
        val store = InMemorySyncStore().block(authors = setOf(carol), roots = setOf(msgId(7)))
        val blocklist = store.blocklist()

        assertEquals(setOf(carol), blocklist.authors)
        assertEquals(setOf(msgId(7)), blocklist.roots)
    }
}
