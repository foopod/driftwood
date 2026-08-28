package com.jonoshields.driftwood.core.sync

import com.jonoshields.driftwood.core.model.MessageId
import com.jonoshields.driftwood.core.model.MessageVerifier
import com.jonoshields.driftwood.core.model.VerifyResult
import com.jonoshields.driftwood.core.store.PartitionBudgets
import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two divergent in-process stores converge after one full session — proving the individual rules compose, not just hold alone. */
class ConvergenceTest {

    // Alice and Bob follow overlapping but different people, which is the ordinary case —
    // two friends with one friend in common.
    //
    //   Alice listens to Carol and Dave.   Bob listens to Carol only.
    //
    // | id  | author | thread    | held by | why it should or should not move          |
    // |-----|--------|-----------|---------|-------------------------------------------|
    // | c1  | Carol  | own       | A       | Carol is in Bob's scope        -> A to B  |
    // | c2  | Carol  | own       | B       | Carol is in Alice's scope      -> B to A  |
    // | c3  | Carol  | own       | both    | already shared                 -> neither |
    // | d1  | Dave   | own       | B       | Dave is in Alice's scope       -> B to A  |
    // | d2  | Dave   | own       | A       | Bob wants it by id             -> A to B  |
    // | cr  | Carol  | d2        | B       | Carol is in Alice's scope      -> B to A  |
    // | ar  | Alice  | c1        | A       | stranger in a followed thread  -> A to B  |
    // | g1  | Alice  | own       | A       | unfollowed, unrelated          -> gossip  |
    private val c1 = carol.root("carol one", NOW - 5_000)
    private val c2 = carol.root("carol two", NOW - 4_000)
    private val c3 = carol.root("carol three", NOW - 3_000)
    private val d1 = dave.root("dave one", NOW - 2_500)
    private val d2 = dave.root("dave two", NOW - 2_000)
    private val cr = carol.reply(d2.id, d2.id, "carol replies to dave", NOW - 1_800)
    private val ar = alice.reply(c1.id, c1.id, "a stranger joins in", NOW - 1_500)
    private val g1 = alice.root("unrelated chatter", NOW - 1_000)

    private fun aliceStore() = InMemorySyncStore()
        .follow(carol.key, dave.key)
        .seed(c1).seed(c3).seed(d2).seed(ar).seed(g1)
        .seedProfile(carol.profile(NOW - 6_000))

    private fun bobStore() = InMemorySyncStore()
        .follow(carol.key)
        .seed(c2).seed(c3).seed(d1).seed(cr)
        // Bob holds Carol's reply but not the Dave post it hangs off, so he is missing a
        // parent and asks for it by id — the only way to get content whose author he does
        // not follow.
        .want(d2.id)

    @Test
    fun `two divergent stores converge in one session`() = runTest {
        val a = aliceStore()
        val b = bobStore()

        val run = sync(a, b)

        assertTrue("${run.initiator}", run.initiator is SessionResult.Completed)
        assertTrue("${run.responder}", run.responder is SessionResult.Completed)

        assertEquals(
            "Bob gains followed content, his want, the context around it, and a little gossip",
            setOf(c1, c2, c3, d1, d2, cr, ar, g1).ids(),
            b.ids,
        )
        assertEquals(
            "Alice gains what Bob had of her people, and the stranger reply in their thread",
            setOf(c1, c2, c3, d1, d2, cr, ar, g1).ids(),
            a.ids,
        )
    }

    @Test
    fun `each message travels by the route it is owed, not merely by some route`() = runTest {
        // Convergence alone is a weak claim: gossip would eventually carry almost anything,
        // so the stores can agree while the reasons are all wrong. The route is what is
        // actually promised. Priority content is guaranteed and uncapped; gossip is
        // best-effort, offered rather than sent, and bounded by the receiver's budget — so
        // "Bob got it in the end" is not the same as "Bob was owed it".
        val run = sync(aliceStore(), bobStore())
        val priority = run.initiatorWire.priorityMessageIds()

        assertTrue("followed content is owed", c1.id in priority)
        assertTrue("a want is owed", d2.id in priority)
        assertTrue("context is owed, not left to chance", ar.id in priority)
        assertFalse("unrelated chatter is not owed, only offered", g1.id in priority)
        assertTrue("but it does still travel", g1.id in run.initiatorWire.messageIdsSent())
    }

    @Test
    fun `the want is satisfied and stops being asked for`() = runTest {
        val b = bobStore()

        sync(aliceStore(), b)

        assertTrue(b.holds(d2.id))
        assertFalse("no longer outstanding", d2.id in b.wants())
    }

    @Test
    fun `a name arrives with its author's content, and only for authors who appear`() = runTest {
        val b = bobStore()

        sync(aliceStore(), b)

        assertEquals("carol", b.usernameFor(carol.key))
        assertEquals("Alice published no profile, so none can arrive", null, b.usernameFor(alice.key))
    }

    @Test
    fun `nothing crosses the wire toward a side that already held it`() = runTest {
        // The claim the whole union-scope design exists to make, and the one most likely to
        // be quietly false — converged stores look identical whether or not it holds, so it
        // has to be checked against the frames that actually moved.
        val a = aliceStore()
        val b = bobStore()
        val aliceHeldBefore = a.ids
        val bobHeldBefore = b.ids

        val run = sync(a, b)

        val fromAlice = run.initiatorWire.messageIdsSent()
        val fromBob = run.responderWire.messageIdsSent()

        assertTrue(
            "Alice sent Bob something he already had: ${fromAlice.filter { it in bobHeldBefore }}",
            fromAlice.none { it in bobHeldBefore },
        )
        assertTrue(
            "Bob sent Alice something she already had: ${fromBob.filter { it in aliceHeldBefore }}",
            fromBob.none { it in aliceHeldBefore },
        )
        assertEquals("and nothing was sent twice", fromAlice.distinct(), fromAlice)
        assertEquals("and nothing was sent twice", fromBob.distinct(), fromBob)
        assertTrue("c3 was shared already, so it never moves", c3.id !in fromAlice + fromBob)
    }

    @Test
    fun `caps still hold after the merge`() = runTest {
        // Convergence must not be allowed to overrun storage. Both sides run tiny partitions
        // here, so the incoming content genuinely exceeds what either is willing to keep.
        val budgets = PartitionBudgets(follow = 4, context = 2, gossip = 2)
        val a = InMemorySyncStore(budgets = budgets).follow(carol.key)
        val b = InMemorySyncStore(budgets = budgets).follow(carol.key)
        (1..20).forEach { n -> a.seed(carol.root("carol $n", NOW - 10_000 - n)) }
        (1..20).forEach { n -> b.seed(dave.root("dave $n", NOW - 10_000 - n)) }

        sync(a, b)

        // One author per partition, so the whole partition budget goes to them.
        assertTrue("Alice kept ${a.ids.size}", a.ids.size <= budgets.follow + budgets.gossip)
        assertTrue("Bob kept ${b.ids.size}", b.ids.size <= budgets.follow + budgets.gossip)
    }

    @Test
    fun `a link that dies during gossip still leaves the priority phase persisted`() = runTest {
        // sync-spec.md §6.8. Two people syncing on a train platform get interrupted, and a
        // sync that is ninety percent done should be worth ninety percent. Everything either
        // side asked for was applied when the priority phase completed; only discovery is lost.
        val b = bobStore()

        val result = againstScriptedPeer(b) { peer ->
            peer.send(FrameCodec.encode(Record.Hello(PROTOCOL_VERSION, scriptedPeerDevice)))
            peer.receive()
            peer.send(FrameCodec.encode(Record.Scope(ScopeDeclaration(setOf(carol.key), 0, emptySet()))))
            peer.receive()
            peer.send(FrameCodec.encode(Record.HashList(emptySet())))
            peer.receive()
            peer.send(FrameCodec.encode(Record.Message(com.jonoshields.driftwood.core.model.MessageCodec.encode(c1))))
            peer.send(FrameCodec.encode(Record.PhaseDone))
            // Walks away the moment the priority phase is done.
            peer.close()
        }

        assertTrue("$result", result is SessionResult.Aborted)
        assertTrue("the priority phase counted", result.summary().priorityPhaseCompleted)
        assertFalse("the gossip phase did not", result.summary().gossipPhaseCompleted)
        assertTrue("and what arrived is still there", b.holds(c1.id))
    }

    @Test
    fun `a corrupted session is always refused, never half-believed`() = runTest {
        // Session-level fuzz. A decoder facing a hostile peer is exactly where a "repair what
        // you can" instinct turns into stored content nobody signed, so the property asserted
        // is absolute: whatever the bytes say, every message left in the store verifies.
        val honest = bobStore().also { sync(aliceStore(), it) }.ids
        val recorded = sync(aliceStore(), bobStore()).initiatorWire.rawSent
        val random = Random(20260821)

        repeat(300) { trial ->
            val corrupted = recorded.map { it.copyOf() }
            val frame = corrupted[random.nextInt(corrupted.size)]
            frame[random.nextInt(frame.size)] = random.nextInt(256).toByte()

            val store = bobStore()
            againstScriptedPeer(store) { peer -> replay(peer, corrupted) }

            store.readMessages(store.ids.toList()).forEach { wire ->
                assertTrue(
                    "trial $trial left an unverifiable message in the store",
                    MessageVerifier.verify(wire) is VerifyResult.Valid,
                )
            }
            // Checked independently of the verifier, which would otherwise be both the code
            // under test and the oracle judging it. Corrupting a session can only ever cost
            // content: it must never conjure a message an honest session would not have
            // delivered.
            assertTrue(
                "trial $trial stored something the honest session never sent: ${store.ids - honest}",
                honest.containsAll(store.ids),
            )
        }
    }

    // ---- helpers ---------------------------------------------------------------------

    /** Ids sent before the first PHASE_DONE — the guaranteed half of the session. */
    private fun Recording.priorityMessageIds(): Set<MessageId> {
        val end = sent.indexOf(Record.PhaseDone).let { if (it < 0) sent.size else it }
        return sent.take(end)
            .filterIsInstance<Record.Message>()
            .mapNotNullTo(mutableSetOf()) {
                (MessageVerifier.verify(it.wire) as? VerifyResult.Valid)?.message?.id
            }
    }

    private fun Set<com.jonoshields.driftwood.core.model.Message>.ids(): Set<MessageId> =
        mapTo(mutableSetOf()) { it.id }

    /** Pushes a recorded frame sequence at our session while draining what comes back, so a hang is a bug in the session, not the test. */
    private suspend fun replay(peer: Connection, frames: List<ByteArray>) = coroutineScope {
        val drain = async { runCatching { while (peer.receive() != null) Unit } }
        runCatching { frames.forEach { peer.send(it) } }
        peer.close()
        drain.await()
        Unit
    }
}
