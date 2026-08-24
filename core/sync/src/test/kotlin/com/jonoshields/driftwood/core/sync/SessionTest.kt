package com.jonoshields.driftwood.core.sync

import com.jonoshields.driftwood.core.model.MessageCodec
import com.jonoshields.driftwood.core.model.RejectionReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The session end to end, both sides running concurrently over a bounded pipe — leans on the ingest rules, where policy order matters. */
class SessionTest {

    @Test
    fun `a message from someone you follow arrives`() = runTest {
        val hers = alice.root("hello from alice", NOW - 1000)
        val aliceStore = InMemorySyncStore().seed(hers)
        val bobStore = InMemorySyncStore().listenTo(alice.key)

        val run = sync(aliceStore, bobStore)

        assertTrue("${run.initiator}", run.initiator is SessionResult.Completed)
        assertTrue("${run.responder}", run.responder is SessionResult.Completed)
        assertTrue(bobStore.holds(hers.id))
        assertEquals(1, run.responder.summary().messagesAccepted)
    }

    @Test
    fun `nothing crosses when the peer already has it`() = runTest {
        val hers = alice.root("already known", NOW - 1000)
        val aliceStore = InMemorySyncStore().seed(hers)
        val bobStore = InMemorySyncStore().listenTo(alice.key).seed(hers)

        val run = sync(aliceStore, bobStore)

        assertTrue("nothing on the wire", run.initiatorWire.messageIdsSent().isEmpty())
        assertEquals(0, run.responder.summary().messagesAccepted)
    }

    @Test
    fun `a name rides along with its author's content`() = runTest {
        val hers = alice.root("hello", NOW - 1000)
        val aliceStore = InMemorySyncStore().seed(hers).seedProfile(alice.profile(NOW - 2000))
        val bobStore = InMemorySyncStore().listenTo(alice.key)

        sync(aliceStore, bobStore)

        assertEquals("alice", bobStore.usernameFor(alice.key))
    }

    @Test
    fun `context arrives so the thread is whole`() = runTest {
        val root = alice.root("what do you think?", NOW - 3000)
        val strangerReply = carol.reply(root.id, root.id, "I think this", NOW - 2000)
        val aliceStore = InMemorySyncStore().seed(root).seed(strangerReply)
        val bobStore = InMemorySyncStore().listenTo(alice.key)

        sync(aliceStore, bobStore)

        assertTrue("Alice's message", bobStore.holds(root.id))
        assertTrue("and Carol's reply, though Bob has never heard of her", bobStore.holds(strangerReply.id))
    }

    @Test
    fun `a want is filled even though it is out of scope`() = runTest {
        val orphanParent = carol.root("the message Bob is missing", NOW - 5000)
        val aliceStore = InMemorySyncStore().seed(orphanParent)
        val bobStore = InMemorySyncStore().want(orphanParent.id)

        sync(aliceStore, bobStore)

        assertTrue(bobStore.holds(orphanParent.id))
        assertTrue("the want is satisfied", bobStore.outstandingWants.isEmpty())
    }

    @Test
    fun `a want is accepted even though it is older than the window`() = runTest {
        // A want is an id with no timestamp attached, so neither side can know its age until
        // it arrives. Refusing it at ingest would turn down exactly what was asked for.
        //
        // Surviving is a separate question, answered below: ingest bypasses the window, the
        // pruner does not.
        val ancient = carol.root("from long ago", 1_000)
        val aliceStore = InMemorySyncStore().seed(ancient)
        val bobStore = InMemorySyncStore().want(ancient.id)

        val run = sync(aliceStore, bobStore)

        assertEquals(1, run.responder.summary().messagesAccepted)
        assertFalse("accepted, then pruned as out of window", bobStore.holds(ancient.id))
        assertFalse("but Bob stops asking: it was answered", ancient.id in bobStore.wants())
    }

    @Test
    fun `an ancient want survives when the thread it belongs to is starred`() = runTest {
        // The case the want-list actually exists to serve. Bob starred this thread, so the
        // window does not apply to it (§4) and the missing parent stays once it arrives.
        //
        // Without a star the previous test holds instead, and that is the honest limit of the
        // mechanism: content older than the window has aged out of the network by design, and
        // fetching it back would defeat the bound that keeps storage finite. A star is how a
        // person says this thread is the exception.
        val ancient = carol.root("from long ago", 1_000)
        val aliceStore = InMemorySyncStore().seed(ancient)
        val bobStore = InMemorySyncStore().want(ancient.id).star(ancient.id)

        sync(aliceStore, bobStore)

        assertTrue(bobStore.holds(ancient.id))
    }

    @Test
    fun `content older than the window is dropped when nobody asked for it`() = runTest {
        val ancient = alice.root("from long ago", 1_000)
        val aliceStore = InMemorySyncStore().seed(ancient)
        val bobStore = InMemorySyncStore().listenTo(alice.key)

        val run = sync(aliceStore, bobStore)

        assertFalse(bobStore.holds(ancient.id))
        assertEquals(0, run.responder.summary().messagesAccepted)
    }

    // ---- blocking ---------------------------------------------------------------------

    @Test
    fun `we never relay for someone we blocked`() = runTest {
        val hers = carol.root("carol says", NOW - 1000)
        val aliceStore = InMemorySyncStore().seed(hers).block(authors = setOf(carol.key))
        val bobStore = InMemorySyncStore().listenTo(carol.key)

        val run = sync(aliceStore, bobStore)

        assertTrue("not even offered", run.initiatorWire.messageIdsSent().isEmpty())
        assertFalse(bobStore.holds(hers.id))
    }

    @Test
    fun `blocked content that arrives anyway is dropped, and is not a rejection`() = runTest {
        // The distinction matters: blocking is local policy, not evidence of a bad actor.
        // Counting it toward the abort threshold would tear down sessions with honest peers
        // who happen to relay someone we blocked.
        val hers = carol.root("carol says", NOW - 1000)
        val aliceStore = InMemorySyncStore().seed(hers)
        val bobStore = InMemorySyncStore().listenTo(carol.key).block(authors = setOf(carol.key))

        val run = sync(aliceStore, bobStore)
        val summary = run.responder.summary()

        assertFalse(bobStore.holds(hers.id))
        assertEquals(1, summary.blockedDropped)
        assertEquals("a drop, not a rejection", 0, summary.rejectionCount)
        assertTrue("${run.responder}", run.responder is SessionResult.Completed)
    }

    // ---- hostility --------------------------------------------------------------------

    @Test
    fun `a tampered message is rejected, counted, and never stored`() = runTest {
        val hers = alice.root("honest", NOW - 1000)
        val tampered = MessageCodec.encode(hers).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val bobStore = InMemorySyncStore().listenTo(alice.key)

        val result = againstScriptedPeer(bobStore) { peerDeliveringMessages(it, listOf(tampered)) }

        assertFalse(bobStore.holds(hers.id))
        assertEquals(1, result.summary().rejectionCount)
    }

    @Test
    fun `the priority phase is uncapped, unlike gossip`() = runTest {
        // The asymmetry is deliberate (sync-spec.md §6.7). Content in the priority phase was
        // asked for — by following its author, or by naming its id in a want — so refusing it
        // part-way through would be refusing our own request. Gossip was not asked for, so it
        // gets a budget. A cap here would silently truncate a genuine backlog.
        val backlog = (1..GOSSIP_INTAKE_CAP + 50).map { n ->
            MessageCodec.encode(alice.root("m$n", NOW - 1000 - n))
        }
        val bobStore = InMemorySyncStore().listenTo(alice.key)

        val result = againstScriptedPeer(bobStore) { peerDeliveringMessages(it, backlog) }

        assertEquals(backlog.size, result.summary().messagesAccepted)
        assertEquals("nothing refused for budget", 0, result.summary().overBudgetDropped)
    }

    @Test
    fun `too many rejections tears the session down`() = runTest {
        val bobStore = InMemorySyncStore().listenTo(alice.key)
        val garbage = (1..VERIFY_FAIL_CUTOFF + 5).map { n ->
            MessageCodec.encode(alice.root("m$n", NOW - 1000)).also { it[it.size - 1] = 0xFF.toByte() }
        }

        val result = againstScriptedPeer(bobStore) { peerDeliveringMessages(it, garbage) }

        assertTrue("$result", result is SessionResult.Aborted)
        assertEquals(AbortReason.TOO_MANY_REJECTIONS, (result as SessionResult.Aborted).reason)
    }

    @Test
    fun `a protocol version mismatch refuses rather than negotiates`() = runTest {
        val result = againstScriptedPeer(InMemorySyncStore()) {
            it.send(FrameCodec.encode(Record.Hello(99, scriptedPeerDevice)))
            runCatching { while (it.receive() != null) Unit }
        }

        assertEquals(AbortReason.VERSION_MISMATCH, (result as SessionResult.Aborted).reason)
    }

    @Test
    fun `each side is asked to confirm the identity the peer actually declared`() = runTest {
        // Distinct booleans per identity, not a shared counter, so a bug that echoes the wrong author back is caught.
        var confirmedResponder = false
        var confirmedInitiator = false

        sync(
            InMemorySyncStore(),
            InMemorySyncStore(),
            confirm = { peer ->
                when (peer) {
                    responderDevice -> confirmedResponder = true
                    initiatorDevice -> confirmedInitiator = true
                }
                true
            },
        )

        assertTrue("initiator's side should have been asked about the responder", confirmedResponder)
        assertTrue("responder's side should have been asked about the initiator", confirmedInitiator)
    }

    @Test
    fun `declining the peer's identity aborts before anything is shared`() = runTest {
        val aliceStore = InMemorySyncStore().listenTo(carol.key)
        val bobStore = InMemorySyncStore().seed(carol.root("hi", NOW - 1000))

        // Bob's human looks at Alice's identity and says no.
        val run = sync(aliceStore, bobStore, confirm = { peer -> peer != initiatorDevice })

        val declined = run.responder as? SessionResult.Aborted
            ?: throw AssertionError("expected the declining side to abort, got ${run.responder}")
        assertEquals(AbortReason.PEER_DECLINED, declined.reason)

        // The decline happens inside the handshake, before the priority phase — bob's scope
        // (what he listens to) never went out at all.
        assertTrue(
            "bob's scope should never have been sent",
            run.responderWire.sent.none { it is Record.Scope },
        )
        assertEquals(0, declined.summary.messagesAccepted)
    }

    @Test
    fun `a record arriving out of phase aborts`() = runTest {
        // A message where a HELLO belongs. There is no state in which that is harmless.
        val result = againstScriptedPeer(InMemorySyncStore()) {
            it.send(FrameCodec.encode(Record.Message(byteArrayOf(1, 2, 3))))
            runCatching { while (it.receive() != null) Unit }
        }

        assertEquals(AbortReason.OUT_OF_PHASE, (result as SessionResult.Aborted).reason)
    }

    @Test
    fun `a malformed frame aborts rather than being repaired`() = runTest {
        val result = againstScriptedPeer(InMemorySyncStore()) {
            it.send(byteArrayOf(0x7F) + java.nio.ByteBuffer.allocate(4).putInt(0).array())
            runCatching { while (it.receive() != null) Unit }
        }

        assertEquals(AbortReason.MALFORMED_FRAME, (result as SessionResult.Aborted).reason)
    }
}
