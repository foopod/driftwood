package com.jonoshields.gossip.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.model.Ed25519Signer
import com.jonoshields.gossip.core.model.Message
import com.jonoshields.gossip.core.model.MessageCodec
import com.jonoshields.gossip.core.model.MessageFactory
import com.jonoshields.gossip.core.model.MessageId
import com.jonoshields.gossip.core.model.MessageVerifier
import com.jonoshields.gossip.core.model.ProfileCodec
import com.jonoshields.gossip.core.model.VerifyResult
import com.jonoshields.gossip.core.store.Clock
import com.jonoshields.gossip.core.store.StorageConfig
import com.jonoshields.gossip.core.store.TierClassifier
import com.jonoshields.gossip.core.sync.PhaseOutcome
import com.jonoshields.gossip.core.sync.WANT_TTL
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [RoomSyncStore] against real SQLite.
 *
 * The protocol itself is proven in `:core:sync` against an in-memory implementation of the
 * same port, in seconds and without a device. What genuinely needs a real database is
 * everything those tests cannot see: SQLite's variable limit, converters, transactions, and
 * whether the queries mean what they say.
 */
@RunWith(AndroidJUnit4::class)
class RoomSyncStoreTest {

    /**
     * Seeds are spread over two bytes on purpose. A one-byte seed wraps at 256, so the
     * hundreds of identities the chunking tests need would silently collapse onto 256 real
     * keypairs — and a test asserting "no rows lost" would fail against perfectly good code.
     */
    private class Person(seed: Int, val name: String) {
        private val signer = Ed25519Signer(
            ByteArray(32).also {
                it[0] = (seed and 0xFF).toByte()
                it[1] = ((seed shr 8) and 0xFF).toByte()
            }
        )
        val key: AuthorId = signer.publicKey
        fun root(text: String, at: Long) = MessageFactory.createRoot(key, text, at, signer)
        fun reply(root: MessageId, parent: MessageId?, text: String, at: Long) =
            MessageFactory.createReply(key, root, parent, text, at, signer)
        fun profile(at: Long) = ProfileCodec.create(key, name, at, signer)
    }

    private val alice = Person(1, "alice")
    private val bob = Person(60, "bob")
    private val now = 1_700_000_000_000L

    private lateinit var database: GossipDatabase
    private lateinit var store: RoomSyncStore

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, GossipDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomSyncStore(database, Clock { now }, StorageConfig())
    }

    @After
    fun tearDown() = database.close()

    private suspend fun given(vararg messages: Message) {
        store.apply(PhaseOutcome(messages.toList(), emptyList(), emptyMap()), now)
    }

    // ---- the variable-limit defect ---------------------------------------------------

    @Test
    fun aPruneLargerThanSqlitesVariableLimitStillRuns() = runTest {
        // The M1 defect this test exists for. `DELETE ... WHERE id IN (:ids)` binds one
        // variable per id, and SQLite refuses past 999 on older Android builds. A store that
        // fills to a cap by design hits that on an ordinary prune, not an exotic one — so
        // before chunking, the first real prune on a full device would simply throw.
        val many = (1..SQL_VARIABLE_LIMIT * 3).map { n -> alice.root("m$n", now - 1_000L - n) }
        given(*many.toTypedArray())
        assertEquals(many.size, database.messages().all().size)

        // Everything is out of window, so the plan evicts the lot in one pass.
        store.pruneAfterSession(nowMillis = now + StorageConfig().windowMillis * 2)

        assertEquals(0, database.messages().all().size)
    }

    @Test
    fun readingMoreIdsThanTheVariableLimitReturnsThemAll() = runTest {
        val many = (1..SQL_VARIABLE_LIMIT * 2 + 7).map { n -> alice.root("m$n", now - 1_000L - n) }
        given(*many.toTypedArray())

        val wire = store.readMessages(many.map { it.id })

        assertEquals(many.size, wire.size)
    }

    @Test
    fun heldByChunksWithoutLosingOrDuplicatingRows() = runTest {
        // Chunking is only sound where the result is the union over batches. Splitting a set
        // of authors across batches must return each author's messages exactly once.
        val people = (1..SQL_VARIABLE_LIMIT + 50).map { Person(it * 3 + 200, "p$it") }
        val messages = people.map { it.root("hello", now - 1_000) }
        given(*messages.toTypedArray())

        val held = store.heldBy(people.mapTo(mutableSetOf()) { it.key })

        assertEquals(messages.size, held.size)
        assertEquals(messages.mapTo(mutableSetOf()) { it.id }, held.mapTo(mutableSetOf()) { it.id })
    }

    // ---- the queries mean what they say ----------------------------------------------

    @Test
    fun heldByHonoursTheSinceBoundAndTheAuthorSet() = runTest {
        val recent = alice.root("recent", now - 1_000)
        val old = alice.root("old", now - 100_000)
        val someoneElse = bob.root("theirs", now - 1_000)
        given(recent, old, someoneElse)

        val held = store.heldBy(setOf(alice.key), since = now - 50_000)

        assertEquals(setOf(recent.id), held.mapTo(mutableSetOf()) { it.id })
    }

    @Test
    fun heldInThreadsFindsRepliesByAnyoneInTheThread() = runTest {
        val root = alice.root("start", now - 5_000)
        val reply = bob.reply(root.id, root.id, "joining in", now - 4_000)
        val elsewhere = bob.root("unrelated", now - 4_000)
        given(root, reply, elsewhere)

        val held = store.heldInThreads(setOf(root.id), since = now - 10_000)

        assertEquals(setOf(root.id, reply.id), held.mapTo(mutableSetOf()) { it.id })
    }

    @Test
    fun newestHeldSkipsWhatIsExcludedAndStaysNewestFirst() = runTest {
        val messages = (1..10).map { n -> alice.root("m$n", now - 1_000L * n) }
        given(*messages.toTypedArray())

        val newest = store.newestHeld(limit = 3, excluding = setOf(messages[0].id, messages[1].id))

        assertEquals(listOf(messages[2].id, messages[3].id, messages[4].id), newest.map { it.id })
    }

    @Test
    fun newestHeldPagesPastAnExclusionSetLargerThanOnePage() = runTest {
        // The paging exists because `excluding` can be the peer's entire hash-list. If it
        // stopped after one page it would return short, and the gossip offer would silently
        // shrink to nothing on exactly the well-connected peers it matters most for.
        val messages = (1..1_200).map { n -> alice.root("m$n", now - 1_000L - n) }
        given(*messages.toTypedArray())
        val excluded = messages.take(1_000).mapTo(mutableSetOf()) { it.id }

        val newest = store.newestHeld(limit = 100, excluding = excluded)

        assertEquals(100, newest.size)
        assertTrue(newest.none { it.id in excluded })
    }

    // ---- ingest ----------------------------------------------------------------------

    @Test
    fun anIngestedMessageIsStoredVerbatimAndReEncodesToTheSameBytes() = runTest {
        // The invariant relaying depends on: we re-encode from stored fields rather than
        // keeping raw bytes, so a column that mangles anything would produce a different id
        // two devices downstream, with no way to tell why.
        val message = alice.reply(
            MessageId.of(ByteArray(32) { 7 }),
            MessageId.of(ByteArray(32) { 9 }),
            "unicode ok: café 🎉",
            now - 1_000,
        )
        given(message)

        val wire = store.readMessages(listOf(message.id)).single()

        assertTrue(MessageVerifier.verify(wire) is VerifyResult.Valid)
        assertEquals(
            MessageCodec.encode(message).toList(),
            wire.toList(),
        )
    }

    @Test
    fun aForwardDatedMessageIsClampedToWhenItArrived() = runTest {
        // effective_time is min(claimed, received), so claiming the future buys nothing
        // (plan.md §3.2). Backdating is deliberately left alone — it only disadvantages the
        // message itself.
        val fromTheFuture = alice.root("later", now + 500_000)
        given(fromTheFuture)

        val stored = database.messages().find(fromTheFuture.id)!!

        assertEquals(now, stored.effectiveTime)
        assertEquals("the claim itself is kept, unaltered", now + 500_000, stored.timestampMillis)
    }

    @Test
    fun anIngestedMessageIsClassifiedIntoATier() = runTest {
        database.listen().add(ListenEntity(alice.key, now))
        val theirs = alice.root("followed", now - 1_000)
        val stranger = bob.root("unfollowed", now - 1_000)
        given(theirs, stranger)

        assertEquals(
            com.jonoshields.gossip.core.store.Tier.LISTEN,
            database.messages().find(theirs.id)!!.tier,
        )
        assertEquals(
            com.jonoshields.gossip.core.store.Tier.GOSSIP,
            database.messages().find(stranger.id)!!.tier,
        )
    }

    @Test
    fun aProfileArrivingWithContentBecomesAReadableName() = runTest {
        store.apply(PhaseOutcome(emptyList(), listOf(alice.profile(now - 1_000)), emptyMap()), now)

        assertEquals("alice", database.directory().find(alice.key)!!.nickname)
        assertNotNull("the signed record is kept so it can be relayed on", store.readProfiles(setOf(alice.key)).singleOrNull())
    }

    // ---- the want-list finally gets used ---------------------------------------------

    @Test
    fun aReplyWhoseParentIsMissingAddsAWant() = runTest {
        val root = alice.root("start", now - 5_000)
        val reply = bob.reply(root.id, root.id, "reply to something we lack", now - 4_000)

        given(reply)

        assertEquals(setOf(root.id), store.wants())
    }

    @Test
    fun aWantIsDroppedTheMomentItIsSatisfied() = runTest {
        val root = alice.root("start", now - 5_000)
        val reply = bob.reply(root.id, root.id, "reply", now - 4_000)
        given(reply)
        assertTrue(root.id in store.wants())

        given(root)

        assertFalse(root.id in store.wants())
    }

    @Test
    fun aParentWeAlreadyHoldIsNeverWanted() = runTest {
        val root = alice.root("start", now - 5_000)
        val reply = bob.reply(root.id, root.id, "reply", now - 4_000)

        given(root, reply)

        assertTrue("nothing is missing", store.wants().isEmpty())
    }

    @Test
    fun aWantNobodyCanFillIsForgottenAfterItsTtl() = runTest {
        val root = alice.root("start", now - 5_000)
        given(bob.reply(root.id, root.id, "reply", now - 4_000))
        assertTrue(root.id in store.wants())

        // Each fruitless phase ages every outstanding want by one.
        repeat(WANT_TTL) { store.apply(PhaseOutcome(listOf(alice.root("filler $it", now - 1_000)), emptyList(), emptyMap()), now) }

        assertTrue("given up on, not chased", store.wants().isEmpty())
    }

    // ---- local policy ----------------------------------------------------------------

    @Test
    fun theBlocklistCoversAuthorsAndThreads() = runTest {
        val root = alice.root("start", now - 5_000)
        database.blocklist().blockAuthor(BlockedAuthorEntity(bob.key, now))
        database.blocklist().blockRoots(listOf(BlockedRootEntity(root.id, now)))

        val blocklist = store.blocklist()

        assertEquals(setOf(bob.key), blocklist.authors)
        assertEquals(setOf(root.id), blocklist.roots)
    }

    @Test
    fun anEmptyOutcomeTouchesNothing() = runTest {
        given(alice.root("kept", now - 1_000))

        store.apply(PhaseOutcome.EMPTY, now)

        assertEquals(1, database.messages().all().size)
        assertTrue(store.wants().isEmpty())
    }
}
