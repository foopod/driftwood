package com.jonoshields.driftwood.core.sync

import com.jonoshields.driftwood.core.model.MessageCodec
import com.jonoshields.driftwood.core.model.MessageId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gossip phase — best-effort by design, since losing it costs only discovery. */
class GossipPhaseTest {

    @Test
    fun `two strangers who follow nobody in common still exchange something`() = runTest {
        // sync-spec.md §6.2. Alice and Dave get talking at a bus stop. Their hash-lists are
        // useless to each other and both priority deltas compute empty; if gossip did nothing
        // the sync would be pointless, and the network would never carry anything past the
        // people who already know each other.
        val hers = alice.root("alice's news", NOW - 1000)
        val his = dave.root("dave's news", NOW - 1000)
        val aliceStore = InMemorySyncStore().seed(hers).listenTo(carol.key)
        val daveStore = InMemorySyncStore().seed(his).listenTo(bob.key)

        val run = sync(aliceStore, daveStore)

        assertTrue("Dave picked up Alice's", daveStore.holds(hers.id))
        assertTrue("Alice picked up Dave's", aliceStore.holds(his.id))
        assertTrue("${run.initiator}", run.initiator is SessionResult.Completed)
        assertTrue(run.initiator.summary().gossipPhaseCompleted)
    }

    @Test
    fun `gossip never re-offers what the priority phase already sent`() = runTest {
        // The claim the offer exists to make, checked against the wire rather than the end
        // state: converged stores would look identical either way.
        val followed = alice.root("followed content", NOW - 1000)
        val aliceStore = InMemorySyncStore().seed(followed)
        val bobStore = InMemorySyncStore().listenTo(alice.key)

        val run = sync(aliceStore, bobStore)

        assertTrue(bobStore.holds(followed.id))
        assertEquals(
            "sent exactly once, in the priority phase",
            1,
            run.initiatorWire.messageIdsSent().count { it == followed.id },
        )
        assertTrue(
            "and never offered again as gossip",
            run.initiatorWire.gossipOffers().none { followed.id in it },
        )
    }

    @Test
    fun `gossip is not offered for content the peer's hash-list already claimed`() = runTest {
        val shared = alice.root("both have this", NOW - 1000)
        val aliceStore = InMemorySyncStore().seed(shared).listenTo(alice.key)
        val bobStore = InMemorySyncStore().seed(shared).listenTo(alice.key)

        val run = sync(aliceStore, bobStore)

        assertTrue(run.initiatorWire.messageIdsSent().isEmpty())
        assertTrue(run.initiatorWire.gossipOffers().none { shared.id in it })
    }

    @Test
    fun `a request for something never offered is answered with nothing`() = runTest {
        // A peer may ask for any id it likes. Answering an unoffered one would turn the
        // request into a probe — name a hash, learn from the reply whether we hold it — so
        // the answer is bounded by what we already disclosed.
        //
        // Here the peer keeps the id out of Alice's offer by claiming in its hash-list to
        // hold it already, and then asks for it anyway. That combination is only ever a lie,
        // and it is the cheapest way to turn the phase into an oracle.
        val secret = alice.root("not on offer", NOW - 1000)
        val aliceStore = InMemorySyncStore().seed(secret)

        val result = againstScriptedPeer(aliceStore) { peer ->
            peer.send(FrameCodec.encode(Record.Hello(PROTOCOL_VERSION, scriptedPeerDevice)))
            peer.receive()
            peer.send(FrameCodec.encode(Record.Scope(ScopeDeclaration(setOf(alice.key), 0, emptySet()))))
            peer.receive()
            peer.send(FrameCodec.encode(Record.HashList(setOf(secret.id))))
            peer.receive()
            peer.send(FrameCodec.encode(Record.PhaseDone))
            peer.drainUntilPhaseDone()

            peer.send(FrameCodec.encode(Record.GossipOffer(emptyList())))
            peer.receive()
            peer.send(FrameCodec.encode(Record.PhaseDone))
            val offer = peer.expectGossipOffer()
            peer.send(FrameCodec.encode(Record.GossipRequest(listOf(secret.id))))
            val answered = peer.collectMessagesUntilPhaseDone()

            peer.send(FrameCodec.encode(Record.SessionDone))
            runCatching { while (peer.receive() != null) Unit }

            probeResult = ProbeResult(offer, answered)
        }

        assertTrue("$result", result is SessionResult.Completed)
        assertFalse("kept out of the offer by their own hash-list", secret.id in probeResult.offered)
        assertTrue("and never handed over on request", probeResult.answered.isEmpty())
    }

    @Test
    fun `intake is bounded by our own budget, not by how much is offered`() = runTest {
        // sync-spec.md §6.7 and §7: GOSSIP_INTAKE_CAP is the *receiver's* budget, and the
        // peer here respects neither half of the bargain — it offers more than the protocol
        // ever offers, then ships everything regardless of what was asked for.
        //
        // Testing this needs a peer that misbehaves. Against an honest one the sender's own
        // offer cap hides the receiver's entirely, so the assertion would hold whether or not
        // the receiving cap existed at all.
        val flood = (1..GOSSIP_INTAKE_CAP + 50).map { n -> carol.root("gossip $n", NOW - 1000 - n) }
        val wire = flood.associate { it.id to MessageCodec.encode(it) }
        val bobStore = InMemorySyncStore()

        val result = againstScriptedPeer(bobStore) { peer ->
            peer.send(FrameCodec.encode(Record.Hello(PROTOCOL_VERSION, scriptedPeerDevice)))
            peer.receive()
            peer.send(FrameCodec.encode(Record.Scope(ScopeDeclaration(emptySet(), 0, emptySet()))))
            peer.receive()
            peer.send(FrameCodec.encode(Record.HashList(emptySet())))
            peer.receive()
            peer.send(FrameCodec.encode(Record.PhaseDone))
            peer.drainUntilPhaseDone()

            peer.send(FrameCodec.encode(Record.GossipOffer(flood.map { it.id })))
            requestedByUs = peer.expectGossipRequest()
            // Ignores the request entirely and sends the lot.
            flood.forEach { peer.send(FrameCodec.encode(Record.Message(wire.getValue(it.id)))) }
            peer.send(FrameCodec.encode(Record.PhaseDone))

            peer.expectGossipOffer()
            peer.send(FrameCodec.encode(Record.GossipRequest(emptyList())))
            peer.drainUntilPhaseDone()

            peer.send(FrameCodec.encode(Record.SessionDone))
            runCatching { while (peer.receive() != null) Unit }
        }

        assertEquals("asked for exactly our budget", GOSSIP_INTAKE_CAP, requestedByUs.size)
        assertEquals("kept exactly our budget", GOSSIP_INTAKE_CAP, result.summary().messagesAccepted)
        assertEquals("the excess dropped", 50, result.summary().overBudgetDropped)
        assertEquals("flooding is rudeness, not forgery", 0, result.summary().rejectionCount)
    }

    @Test
    fun `we never gossip content from someone we blocked`() = runTest {
        val hers = carol.root("carol says", NOW - 1000)
        val aliceStore = InMemorySyncStore().seed(hers).block(authors = setOf(carol.key))
        val bobStore = InMemorySyncStore()

        val run = sync(aliceStore, bobStore)

        assertTrue("not even offered", run.initiatorWire.gossipOffers().none { hers.id in it })
        assertFalse(bobStore.holds(hers.id))
    }

    // ---- helpers ---------------------------------------------------------------------

    private data class ProbeResult(val offered: List<MessageId>, val answered: List<Record.Message>)

    private lateinit var probeResult: ProbeResult
    private var requestedByUs: List<MessageId> = emptyList()

    private fun Recording.gossipOffers(): List<List<MessageId>> =
        sent.filterIsInstance<Record.GossipOffer>().map { it.ids }

    private fun Recording.gossipRequests(): List<List<MessageId>> =
        sent.filterIsInstance<Record.GossipRequest>().map { it.ids }

    private suspend fun Connection.nextRecord(): Record? =
        receive()?.let { (FrameCodec.decode(it) as? FrameResult.Ok)?.record }

    private suspend fun Connection.drainUntilPhaseDone() {
        while (true) if (nextRecord().let { it == null || it == Record.PhaseDone }) return
    }

    private suspend fun Connection.expectGossipRequest(): List<MessageId> {
        while (true) {
            when (val record = nextRecord()) {
                is Record.GossipRequest -> return record.ids
                null -> return emptyList()
                else -> Unit
            }
        }
    }

    private suspend fun Connection.expectGossipOffer(): List<MessageId> {
        while (true) {
            when (val record = nextRecord()) {
                is Record.GossipOffer -> return record.ids
                null -> return emptyList()
                else -> Unit
            }
        }
    }

    private suspend fun Connection.collectMessagesUntilPhaseDone(): List<Record.Message> {
        val out = mutableListOf<Record.Message>()
        while (true) {
            when (val record = nextRecord()) {
                is Record.Message -> out += record
                null, Record.PhaseDone -> return out
                else -> Unit
            }
        }
    }
}
