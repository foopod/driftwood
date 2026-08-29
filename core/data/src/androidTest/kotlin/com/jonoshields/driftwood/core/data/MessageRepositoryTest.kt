package com.jonoshields.driftwood.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jonoshields.driftwood.core.identity.IdentityStore
import com.jonoshields.driftwood.core.identity.SeedCipher
import com.jonoshields.driftwood.core.identity.SeedStorage
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.Message
import com.jonoshields.driftwood.core.model.MessageId
import com.jonoshields.driftwood.core.store.Clock
import com.jonoshields.driftwood.core.store.PartitionSplit
import com.jonoshields.driftwood.core.store.StorageConfig
import com.jonoshields.driftwood.core.store.Tier
import kotlinx.coroutines.flow.first
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

/** The data layer against a real in-memory SQLite engine, catching schema/converter/query problems a JVM fake would miss. */
@RunWith(AndroidJUnit4::class)
class MessageRepositoryTest {

    private class InMemorySeedStorage : SeedStorage {
        private var bytes: ByteArray? = null
        override fun read() = bytes
        override fun write(record: ByteArray) { bytes = record.copyOf() }
        override fun clear() { bytes = null }
    }

    private val passthroughCipher = object : SeedCipher {
        override fun seal(plaintext: ByteArray) = plaintext.copyOf()
        override fun open(sealed: ByteArray) = sealed.copyOf()
    }

    private lateinit var database: DriftwoodDatabase
    private lateinit var identity: IdentityStore
    private var now = 1_700_000_000_000L

    private fun repository(config: StorageConfig = StorageConfig()) =
        RoomMessageRepository(database, identity, { now }, config)

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, DriftwoodDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        identity = IdentityStore(passthroughCipher, InMemorySeedStorage()).apply { create() }
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun postedMessageIsStoredAndObservable() = runTest {
        val repository = repository()

        val posted = repository.post("hello world").getOrThrow()
        val all = repository.observeAll().first()

        assertEquals(1, all.size)
        assertEquals(posted.id, all.single().id)
        assertEquals("hello world", all.single().body.text)
        assertEquals(identity.publicKey(), all.single().body.author)
    }

    @Test
    fun storedMessageSurvivesTheRoundTripIntact() = runTest {
        // Converters and column mapping are the risk here: an id or signature mangled on the
        // way through the database would break verification for every peer later.
        val repository = repository()
        val posted = repository.post("café 🙂 日本語").getOrThrow()

        val loaded = repository.observeAll().first().single()

        assertEquals(posted.id, loaded.id)
        assertEquals(posted.body, loaded.body)
        assertTrue(posted.signature.contentEquals(loaded.signature))
        assertEquals(posted.threadRoot, loaded.threadRoot)
    }

    @Test
    fun contentAddressingGivesDedupForFree() = runTest {
        val repository = repository()
        val message = repository.post("same").getOrThrow()

        // Re-inserting the identical message is a no-op, not a conflict.
        database.messages().insert(
            requireNotNull(database.messages().find(message.id))
        )

        assertEquals(1, repository.observeAll().first().size)
    }

    @Test
    fun repliesAssembleIntoAThread() = runTest {
        val repository = repository()
        val root = repository.post("the root").getOrThrow()
        now += 1000
        val reply = repository.reply(root.id, root.id, "a reply").getOrThrow()
        now += 1000
        val nested = repository.reply(root.id, reply.id, "nested").getOrThrow()

        val thread = repository.observeThread(root.id).first()

        assertEquals(root.id, thread.root?.id)
        assertEquals(reply.id, thread.replies.single().message.id)
        assertEquals(nested.id, thread.replies.single().children.single().message.id)
    }

    @Test
    fun aThreadWhoseRootIsGoneStillRenders() = runTest {
        val repository = repository()
        val root = repository.post("will be removed").getOrThrow()
        now += 1000
        val reply = repository.reply(root.id, root.id, "outlives its root").getOrThrow()

        database.messages().deleteChunk(listOf(root.id))

        val thread = repository.observeThread(root.id).first()
        assertNull(thread.root)
        assertEquals(reply.id, thread.replies.single().message.id)
    }

    @Test
    fun ownMessagesTakeTheirOwnTimestampAsEffectiveTime() = runTest {
        // A message you authored was first seen at creation.
        val repository = repository()
        val posted = repository.post("mine").getOrThrow()

        val stored = requireNotNull(database.messages().find(posted.id))
        assertEquals(posted.body.timestampMillis, stored.firstReceivedTime)
        assertEquals(posted.body.timestampMillis, stored.effectiveTime)
    }

    @Test
    fun ownMessagesAreGossipTierUntilYouListenToYourself() = runTest {
        // Nothing is in the follow list yet, so even your own messages classify as gossip.
        val repository = repository()
        val posted = repository.post("mine").getOrThrow()
        assertEquals(Tier.GOSSIP, requireNotNull(database.messages().find(posted.id)).tier)
    }

    @Test
    fun starringAThreadIsPersistedAndReversible() = runTest {
        val repository = repository()
        val root = repository.post("pin this thread").getOrThrow()

        assertFalse(repository.observeThreadPinned(root.id).first())

        repository.setThreadPinned(root.id, true).getOrThrow()
        assertTrue(repository.observeThreadPinned(root.id).first())

        repository.setThreadPinned(root.id, false).getOrThrow()
        assertFalse(repository.observeThreadPinned(root.id).first())
    }

    @Test
    fun aThreadCanBeStarredEvenWhenItsRootIsNotHeld() = runTest {
        // The pin keys on the root id, which survives its message. Someone reading a
        // fragment of an old conversation can still choose to keep it.
        val repository = repository()
        val root = repository.post("about to disappear").getOrThrow()
        now += 1000
        val reply = repository.reply(root.id, root.id, "the part that remains").getOrThrow()
        database.messages().deleteChunk(listOf(root.id))

        repository.setThreadPinned(root.id, true).getOrThrow()
        repository.prune().getOrThrow()

        assertNotNull("the surviving reply must be kept", database.messages().find(reply.id))
    }

    @Test
    fun blockingRemovesTheAuthorAndTheirThreadsImmediately() = runTest {
        val repository = repository()
        val root = repository.post("from the blocked author").getOrThrow()
        now += 1000
        val reply = repository.reply(root.id, root.id, "reply in their thread").getOrThrow()

        repository.block(identity.publicKey()).getOrThrow()

        assertEquals(emptyList<Any>(), repository.observeAll().first())
        assertNull(database.messages().find(root.id))
        assertNull(database.messages().find(reply.id))
        // The thread is remembered so later replies stay blocked after the root is gone.
        assertTrue(root.id in database.blocklist().blockedRoots())
    }

    @Test
    fun pruningEvictsOldestFirstWithinTheCap() = runTest {
        // A tiny budget so fair share bites: three messages of nominal size, all of it
        // given to gossip since that is where an unfollowed author's own posts land.
        val repository = repository(
            StorageConfig(
                totalBudgetBytes = 3L * 512,
                split = PartitionSplit(follow = 0.0, context = 0.0, gossip = 1.0),
            )
        )
        val posted = (1..6).map { now += 1000; repository.post("message $it").getOrThrow() }

        val plan = repository.prune().getOrThrow()
        val survivors = repository.observeAll().first().map { it.id }.toSet()

        assertTrue("something should have been evicted", plan.evict.isNotEmpty())
        assertTrue(
            "the newest message must survive",
            posted.last().id in survivors,
        )
        assertTrue(
            "the oldest message must be the first to go",
            posted.first().id !in survivors,
        )
    }

    @Test
    fun aStarredThreadSurvivesPruningWholeIncludingItsReplies() = runTest {
        val repository = repository(
            StorageConfig(totalBudgetBytes = 512, split = PartitionSplit(0.0, 0.0, 1.0))
        )
        val kept = repository.post("keep this whole conversation").getOrThrow()
        repository.setThreadPinned(kept.id, true).getOrThrow()
        now += 1000
        val keptReply = repository.reply(kept.id, kept.id, "including this reply").getOrThrow()
        (1..5).forEach { now += 1000; repository.post("filler $it").getOrThrow() }

        repository.prune().getOrThrow()

        // The pin covers the thread, not the one message that was pinned.
        assertNotNull(database.messages().find(kept.id))
        assertNotNull(database.messages().find(keptReply.id))
    }

    @Test
    fun aReplyArrivingAfterTheStarIsAlsoKept() = runTest {
        // Starring is forward-looking: it exempts the thread, so replies written later are
        // covered too. This is also the reason a pinned thread has no cap at all.
        val repository = repository(
            StorageConfig(totalBudgetBytes = 512, split = PartitionSplit(0.0, 0.0, 1.0))
        )
        val root = repository.post("pinned before the reply existed").getOrThrow()
        repository.setThreadPinned(root.id, true).getOrThrow()
        (1..5).forEach { now += 1000; repository.post("filler $it").getOrThrow() }

        now += 1000
        val later = repository.reply(root.id, root.id, "written after starring").getOrThrow()
        repository.prune().getOrThrow()

        assertNotNull(database.messages().find(later.id))
    }

    @Test
    fun postingWithoutAnIdentityIsATypedError() = runTest {
        val noIdentity = IdentityStore(passthroughCipher, InMemorySeedStorage())
        val repository = RoomMessageRepository(database, noIdentity, { now }, StorageConfig())

        val error = repository.post("nope").exceptionOrNull()

        assertTrue("got $error", error is DataError.NoIdentity)
    }

    @Test
    fun overLongTextIsATypedErrorNotACrash() = runTest {
        val repository = repository()
        val error = repository.post("x".repeat(321)).exceptionOrNull()
        assertTrue("got $error", error is DataError.InvalidMessage)
    }

    @Test
    fun postedMessagesAreUnsentUntilServedToAPeer() = runTest {
        val repository = repository()
        val posted = repository.post("draft").getOrThrow()

        assertTrue(requireNotNull(database.messages().find(posted.id)).unsent)
    }

    @Test
    fun deletingAnUnsentMessageYouAuthoredRemovesIt() = runTest {
        val repository = repository()
        val posted = repository.post("oops").getOrThrow()

        repository.deleteMessage(posted.id).getOrThrow()

        assertNull(database.messages().find(posted.id))
    }

    @Test
    fun deletingAMessageThatHasAlreadySyncedFails() = runTest {
        // Mirrors what RoomSyncStore.readMessages() does the moment content is actually served.
        val repository = repository()
        val posted = repository.post("already out").getOrThrow()
        database.messages().clearUnsent(listOf(posted.id))

        val error = repository.deleteMessage(posted.id).exceptionOrNull()

        assertTrue("got $error", error is DataError.Local)
        assertNotNull("refused, so it must still be there", database.messages().find(posted.id))
    }

    @Test
    fun deletingSomeoneElsesMessageFails() = runTest {
        // The repository itself never writes a row like this — the guard has to hold
        // regardless of how one got there.
        val repository = repository()
        val foreignId = MessageId.of(ByteArray(32) { 9 })
        database.messages().insert(foreignUnsentMessage(foreignId))

        val error = repository.deleteMessage(foreignId).exceptionOrNull()

        assertTrue("got $error", error is DataError.Local)
        assertNotNull(database.messages().find(foreignId))
    }

    @Test
    fun restoringADeletedMessageBringsItBackExactlyAsUnsent() = runTest {
        // "Undo" on the delete snackbar — re-insertion, not a delayed commit, since a commit
        // delayed until the snackbar resolves would silently vanish if the screen is left first.
        val repository = repository()
        val posted = repository.post("oops").getOrThrow()
        repository.deleteMessage(posted.id).getOrThrow()
        assertNull(database.messages().find(posted.id))

        repository.restoreMessage(posted).getOrThrow()

        val restored = requireNotNull(database.messages().find(posted.id))
        assertTrue("restored exactly as it was — still eligible to delete again", restored.unsent)
        assertEquals(posted.body.text, restored.text)
    }

    @Test
    fun restoringSomeoneElsesMessageFails() = runTest {
        val repository = repository()
        val foreign = Message.unverified(
            id = MessageId.of(ByteArray(32) { 5 }),
            signature = ByteArray(64),
            body = com.jonoshields.driftwood.core.model.MessageBody(
                version = 1,
                author = AuthorId.of(ByteArray(32) { 6 }),
                root = null,
                parent = null,
                timestampMillis = now,
                text = "not mine",
            ),
        )

        val error = repository.restoreMessage(foreign).exceptionOrNull()

        assertTrue("got $error", error is DataError.Local)
        assertNull(database.messages().find(foreign.id))
    }

    @Test
    fun deletingAWholeUnsentThreadRemovesEveryRowUnderIt() = runTest {
        val repository = repository()
        val root = repository.post("root").getOrThrow()
        now += 1000
        val reply = repository.reply(root.id, root.id, "reply").getOrThrow()

        repository.deleteThread(root.id).getOrThrow()

        assertNull(database.messages().find(root.id))
        assertNull(database.messages().find(reply.id))
    }

    @Test
    fun deletingAWholeThreadRefusesIfAnyRowIsIneligible() = runTest {
        // Shouldn't be reachable in practice — an unsent root's whole subtree can only ever
        // be authored locally — but the guard must refuse outright rather than partial-delete
        // if it somehow is.
        val repository = repository()
        val root = repository.post("root").getOrThrow()
        database.messages().insert(foreignUnsentMessage(MessageId.of(ByteArray(32) { 7 }), threadRoot = root.id))

        val error = repository.deleteThread(root.id).exceptionOrNull()

        assertTrue("got $error", error is DataError.Local)
        assertNotNull("refused, so nothing should have been deleted", database.messages().find(root.id))
    }

    private fun foreignUnsentMessage(id: MessageId, threadRoot: MessageId = id) = MessageEntity(
        id = id,
        version = 1,
        author = AuthorId.of(ByteArray(32) { 8 }),
        root = if (threadRoot == id) null else threadRoot,
        parent = if (threadRoot == id) null else threadRoot,
        threadRoot = threadRoot,
        timestampMillis = now,
        text = "not mine",
        signature = ByteArray(64),
        firstReceivedTime = now,
        effectiveTime = now,
        read = false,
        tier = Tier.GOSSIP,
        unsent = true,
    )
}
