package com.jonoshields.driftwood.core.sync

import com.jonoshields.driftwood.core.model.MessageCodec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Batched, incrementally-persisted delivery within a phase — every completed batch is durable as it lands. */
class SessionBatchingTest {

    @Test
    fun `a delivery spanning many batches converges and is chunked on the wire`() = runTest {
        val backlog = (1..7).map { n -> alice.root("m$n", NOW - 1000 - n) }
        val aliceStore = InMemorySyncStore()
        backlog.forEach { aliceStore.seed(it) }
        val bobStore = InMemorySyncStore().listenTo(alice.key)

        val run = sync(aliceStore, bobStore, batchSize = 2)

        assertTrue("${run.initiator}", run.initiator is SessionResult.Completed)
        assertTrue("${run.responder}", run.responder is SessionResult.Completed)
        backlog.forEach { assertTrue("bob holds ${it.body.text}", bobStore.holds(it.id)) }
        assertEquals(backlog.size, run.responder.summary().messagesAccepted)

        // 7 messages at batchSize 2 is 4 batches: 3 BatchDone boundaries then a final PhaseDone.
        val markers = run.initiatorWire.sent.filter { it == Record.BatchDone || it == Record.PhaseDone }
        assertEquals(listOf(Record.BatchDone, Record.BatchDone, Record.BatchDone, Record.PhaseDone), markers.take(4))
    }

    @Test
    fun `a link that dies mid delivery leaves every completed batch persisted`() = runTest {
        // Four batches land and persist one at a time; the connection then dies before a fifth — what already landed must stay.
        val backlog = (1..8).map { n -> alice.root("m$n", NOW - 1000 - n) }
        val wire = backlog.map { MessageCodec.encode(it) }
        val bobStore = InMemorySyncStore().listenTo(alice.key)

        val result = againstScriptedPeer(bobStore, batchSize = 2) { peer ->
            suspend fun send(record: Record) = peer.send(FrameCodec.encode(record))
            send(Record.Hello(PROTOCOL_VERSION, scriptedPeerDevice))
            peer.receive()
            send(Record.Scope(ScopeDeclaration(emptySet(), 0, emptySet())))
            peer.receive()
            send(Record.HashList(emptySet()))
            peer.receive()

            // Two full batches of 2, properly bounded by BatchDone, then the link dies before
            // batch three's boundary marker ever arrives.
            wire.take(4).forEach { send(Record.Message(it)) }
            send(Record.BatchDone)
            wire.subList(4, 6).forEach { send(Record.Message(it)) }
            send(Record.BatchDone)
            wire.subList(6, 8).forEach { send(Record.Message(it)) }
            peer.close()
        }

        assertTrue("$result", result is SessionResult.Aborted)
        assertEquals(AbortReason.PEER_CLOSED, (result as SessionResult.Aborted).reason)
        // Two boundary markers arrived (after messages 1-4, and after 5-6), so both are
        // persisted; messages 7-8 never got one before the link died.
        backlog.take(6).forEach { assertTrue("batch already landed: ${it.body.text}", bobStore.holds(it.id)) }
        backlog.subList(6, 8).forEach { assertFalse("never got a boundary marker", bobStore.holds(it.id)) }
    }

    @Test
    fun `the rejection cutoff is phase scoped, not batch scoped`() = runTest {
        // Regression guard: an Ingest that reset its rejection count at every BatchDone would
        // let a hostile peer buy free rejections just by fragmenting garbage into small enough
        // batches. Three batches of 10 bad signatures each (30 total) must abort once the
        // running total passes VERIFY_FAIL_CUTOFF (20) — partway through the third batch, not
        // never.
        val garbageBatch = { n: Int ->
            (1..10).map { i ->
                MessageCodec.encode(alice.root("g$n-$i", NOW - 1000))
                    .also { it[it.size - 1] = 0xFF.toByte() }
            }
        }
        val bobStore = InMemorySyncStore().listenTo(alice.key)

        val result = againstScriptedPeer(bobStore, batchSize = 10) { peer ->
            suspend fun send(record: Record) = peer.send(FrameCodec.encode(record))
            send(Record.Hello(PROTOCOL_VERSION, scriptedPeerDevice))
            peer.receive()
            send(Record.Scope(ScopeDeclaration(emptySet(), 0, emptySet())))
            peer.receive()
            send(Record.HashList(emptySet()))
            peer.receive()

            repeat(3) { batch ->
                garbageBatch(batch).forEach { send(Record.Message(it)) }
                send(Record.BatchDone)
            }
            runCatching { while (peer.receive() != null) Unit }
        }

        assertTrue("$result", result is SessionResult.Aborted)
        assertEquals(AbortReason.TOO_MANY_REJECTIONS, (result as SessionResult.Aborted).reason)
    }

    @Test
    fun `want ageing runs once per phase, not once per batch`() = runTest {
        // A phase spanning several batches must still count as exactly one fruitless attempt
        // per phase, not one per batch — otherwise a single multi-batch sync could age a want
        // through most of its WANT_TTL by itself. Two phases run per session (priority, then
        // gossip — the same as before batching), so one session ages a want by 2 regardless of
        // how many batches either phase took.
        val unrelated = (1..6).map { n -> alice.root("m$n", NOW - 1000 - n) }
        val neverArrives = msgId(999)
        val aliceStore = InMemorySyncStore()
        unrelated.forEach { aliceStore.seed(it) }
        val bobStore = InMemorySyncStore().listenTo(alice.key).want(neverArrives)

        sync(aliceStore, bobStore, batchSize = 2)

        assertEquals(
            "one aging per phase (priority + gossip), independent of batch count",
            2,
            bobStore.unsatisfiedSyncs(neverArrives),
        )
    }

    @Test
    fun `a backlog past the batch ceiling stops cleanly instead of running forever`() = runTest {
        // MAX_BATCHES_PER_PHASE=50 is no longer a data-loss risk (every batch up to it is
        // already persisted), so hitting it ends the session normally rather than aborting —
        // the truncated flag says there is more to come, and a later sync picks it up.
        val backlog = (1..(MAX_BATCHES_PER_PHASE + 1)).map { n -> alice.root("m$n", NOW - 100_000 - n) }
        val aliceStore = InMemorySyncStore()
        backlog.forEach { aliceStore.seed(it) }
        val bobStore = InMemorySyncStore().listenTo(alice.key)

        val run = sync(aliceStore, bobStore, batchSize = 1)

        assertTrue("${run.initiator}", run.initiator is SessionResult.Completed)
        assertTrue("truncated, not an error", run.initiator.summary().priorityPhaseTruncated)
        assertEquals(
            "exactly MAX_BATCHES_PER_PHASE messages made it this session",
            MAX_BATCHES_PER_PHASE,
            bobStore.ids.size,
        )
    }
}
