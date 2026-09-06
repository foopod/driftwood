package com.jonoshields.driftwood.core.data

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.testing.TestPager
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.Ed25519Signer
import com.jonoshields.driftwood.core.model.Message
import com.jonoshields.driftwood.core.model.MessageFactory
import com.jonoshields.driftwood.core.model.MessageId
import com.jonoshields.driftwood.core.store.Clock
import com.jonoshields.driftwood.core.store.StorageConfig
import com.jonoshields.driftwood.core.sync.PhaseOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** `MessageDao.pagedActivity` / `observeActivityUnreadCount` against real SQLite. */
@RunWith(AndroidJUnit4::class)
class ActivityQueryTest {

    private class Person(seed: Int) {
        private val signer = Ed25519Signer(ByteArray(32) { seed.toByte() })
        val key: AuthorId = signer.publicKey
        fun root(text: String, at: Long) = MessageFactory.createRoot(key, text, at, signer)
        fun reply(root: MessageId, parent: MessageId?, text: String, at: Long) =
            MessageFactory.createReply(key, root, parent, text, at, signer)
    }

    private val me = Person(1)
    private val alice = Person(2)
    private val carol = Person(4) // stranger
    private val now = 1_700_000_000_000L

    private lateinit var database: DriftwoodDatabase
    private lateinit var syncStore: RoomSyncStore
    private val config = PagingConfig(pageSize = 20, enablePlaceholders = false)

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, DriftwoodDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        syncStore = RoomSyncStore(database, Clock { now }, StorageConfig())
    }

    @After
    fun tearDown() = database.close()

    /** Applies as sync would (everything lands unread). [receivedAt] feeds `first_received_time`. */
    private suspend fun given(vararg messages: Message, receivedAt: Long = now) {
        syncStore.apply(PhaseOutcome(messages.toList(), emptyList(), emptyMap()), receivedAt)
    }

    private suspend fun activity(): List<ActivityRow> {
        val source: PagingSource<Int, ActivityRow> = database.messages().pagedActivity(me.key)
        return (TestPager(config, source).refresh() as PagingSource.LoadResult.Page).data
    }

    private suspend fun unreadCount(): Int =
        database.messages().observeActivityUnreadCount(me.key).first()

    @Test
    fun aStrangerReplyingToMyRootIsActivity() = runTest {
        val myRoot = me.root("my post", now - 2_000)
        val reply = carol.reply(myRoot.id, myRoot.id, "nice one", now - 1_000)
        given(myRoot, reply)

        val rows = activity()

        assertEquals(1, rows.size)
        assertEquals(reply.id, rows.single().replyId)
        assertEquals(true, rows.single().parentIsRoot)
        assertEquals(1, unreadCount())
    }

    @Test
    fun aReplyToMyMidThreadMessageIsActivityButNotFlaggedAsToTheRoot() = runTest {
        val strangerRoot = carol.root("stranger's thread", now - 3_000)
        val myReply = me.reply(strangerRoot.id, strangerRoot.id, "chiming in", now - 2_000)
        val theirReply = alice.reply(strangerRoot.id, myReply.id, "replying to you", now - 1_000)
        given(strangerRoot, myReply, theirReply)

        val rows = activity()

        assertEquals(listOf(theirReply.id), rows.map { it.replyId })
        assertEquals(false, rows.single().parentIsRoot)
    }

    @Test
    fun myOwnReplyToMyOwnMessageIsNotActivity() = runTest {
        val myRoot = me.root("my post", now - 2_000)
        val mySelfReply = me.reply(myRoot.id, myRoot.id, "adding to it", now - 1_000)
        given(myRoot, mySelfReply)

        assertEquals(emptyList<MessageId>(), activity().map { it.replyId })
        assertEquals(0, unreadCount())
    }

    @Test
    fun aReplyWhoseParentIsNotHeldIsNotActivity() = runTest {
        val myRoot = me.root("my post", now - 3_000)
        val myReply = me.reply(myRoot.id, myRoot.id, "a follow-up", now - 2_000)
        val theirReply = carol.reply(myRoot.id, myReply.id, "re your follow-up", now - 1_000)
        // Only the stranger's reply arrives — its parent (myReply) was never carried here.
        given(theirReply)

        assertEquals(emptyList<MessageId>(), activity().map { it.replyId })
    }

    @Test
    fun blockedAuthorsAndBlockedRootsAreExcluded() = runTest {
        val myRoot = me.root("my post", now - 3_000)
        val blockedAuthorReply = carol.reply(myRoot.id, myRoot.id, "from a blocked person", now - 2_000)
        given(myRoot, blockedAuthorReply)
        database.blocklist().blockAuthor(BlockedAuthorEntity(carol.key, now))

        assertEquals(emptyList<MessageId>(), activity().map { it.replyId })

        database.blocklist().unblockAuthor(carol.key)
        database.blocklist().blockRoots(listOf(BlockedRootEntity(myRoot.id, now)))
        assertEquals(emptyList<MessageId>(), activity().map { it.replyId })
    }

    @Test
    fun rowsAreOrderedByArrivalNotByAuthorClaimedTime() = runTest {
        val myRoot = me.root("my post", now - 10_000)
        given(myRoot)
        // Arrives first, claims a recent time.
        val early = carol.reply(myRoot.id, myRoot.id, "arrived first", now - 1_000)
        given(early, receivedAt = now - 5_000)
        // Arrives later, but claims a much older time — a backfilled reply.
        val backfilled = alice.reply(myRoot.id, myRoot.id, "arrived later", now - 9_000)
        given(backfilled, receivedAt = now - 1_000)

        assertEquals(listOf(backfilled.id, early.id), activity().map { it.replyId })
    }

    @Test
    fun openingTheThreadClearsItsUnreadContribution() = runTest {
        val myRoot = me.root("my post", now - 2_000)
        given(myRoot, carol.reply(myRoot.id, myRoot.id, "hi", now - 1_000))
        assertEquals(1, unreadCount())

        database.messages().markThreadRead(myRoot.id)

        assertEquals(0, unreadCount())
        assertEquals(1, activity().size) // still listed, just no longer unread
    }
}
