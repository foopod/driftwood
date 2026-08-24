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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The paginated thread-list query (`MessageDao.pagedThreads`) against real SQLite, driven via `TestPager` since a paging flow never completes. */
@RunWith(AndroidJUnit4::class)
class ThreadSummaryPagingTest {

    private class Person(seed: Int) {
        private val signer = Ed25519Signer(ByteArray(32) { seed.toByte() })
        val key: AuthorId = signer.publicKey
        fun root(text: String, at: Long) = MessageFactory.createRoot(key, text, at, signer)
        fun reply(root: MessageId, parent: MessageId?, text: String, at: Long) =
            MessageFactory.createReply(key, root, parent, text, at, signer)
    }

    private val me = Person(1)
    private val alice = Person(2) // listened
    private val bob = Person(3) // listened
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

    private suspend fun listenTo(vararg authors: AuthorId) {
        authors.forEach { database.listen().add(ListenEntity(it, now)) }
    }

    /** Applies as sync would — tier is computed from the current listen list at apply time. */
    private suspend fun given(vararg messages: Message) {
        syncStore.apply(PhaseOutcome(messages.toList(), emptyList(), emptyMap()), now)
    }

    private suspend fun refresh(
        wantListening: Boolean = true,
        unreadOnly: Boolean = false,
        authorFilter: AuthorId? = null,
        textQuery: String? = null,
    ): List<ThreadSummaryRow> {
        val source: PagingSource<Int, ThreadSummaryRow> =
            database.messages().pagedThreads(me.key, wantListening, unreadOnly, authorFilter, textQuery)
        val page = TestPager(config, source).refresh() as PagingSource.LoadResult.Page
        return page.data
    }

    private suspend fun otherTab(unreadOnly: Boolean = false): List<ThreadSummaryRow> =
        refresh(wantListening = false, unreadOnly = unreadOnly)

    @Test
    fun aRootFromSomeoneListenedToLandsInMyCircle() = runTest {
        listenTo(alice.key)
        val root = alice.root("hello", now - 1_000)
        given(root)

        val rows = refresh()

        assertEquals(1, rows.size)
        assertEquals(root.id, rows.single().rootId)
    }

    @Test
    fun aRootFromMyselfLandsInMyCircleEvenThoughSelfIsNeverInTheListenList() = runTest {
        val root = me.root("my own post", now - 1_000)
        given(root)

        val rows = refresh()

        assertEquals(1, rows.size)
        assertEquals(root.id, rows.single().rootId)
    }

    @Test
    fun aRootFromAStrangerLandsOnTheOtherTabOnly() = runTest {
        val root = carol.root("stranger's post", now - 1_000)
        given(root)

        assertTrue(refresh().isEmpty())
        assertEquals(listOf(root.id), otherTab().map { it.rootId })
    }

    @Test
    fun aStrangerReplyInAListenedThreadNeverBecomesTheHighlightedReply() = runTest {
        // Context, not a listened reply: the thread qualifies for My Circle because Alice's
        // root is in it, but Carol replying doesn't make Carol "in scope".
        listenTo(alice.key)
        val root = alice.root("what do you think?", now - 3_000)
        val strangerReply = carol.reply(root.id, root.id, "I have thoughts", now - 2_000)
        given(root, strangerReply)

        val row = refresh().single()

        assertEquals(null, row.latestListenedAuthor)
    }

    @Test
    fun theLatestListenedReplyIsPickedOverAnEarlierOne() = runTest {
        // The greatest-n-per-group case this query exists to get right: two in-scope replies
        // in the same thread, only the newest should surface.
        listenTo(alice.key, bob.key)
        val root = alice.root("root", now - 5_000)
        val earlier = bob.reply(root.id, root.id, "earlier reply", now - 4_000)
        val later = bob.reply(root.id, root.id, "later reply", now - 1_000)
        given(root, earlier, later)

        val row = refresh().single()

        assertEquals("later reply", row.latestListenedText)
        assertEquals(3, row.messageCount)
    }

    @Test
    fun sortIsNewestFirst() = runTest {
        listenTo(alice.key)
        val newer = alice.root("newer", now - 1_000)
        val older = alice.root("older", now - 2_000)
        given(newer, older)

        assertEquals(listOf(newer.id, older.id), refresh().map { it.rootId })
    }

    @Test
    fun unreadOnlyHidesThreadsWithNothingUnread() = runTest {
        val readRoot = me.root("already read", now - 1_000)
        given(readRoot)
        // RoomSyncStore.apply always inserts unread=false, regardless of author — mark it
        // explicitly to set up the "nothing unread" case this test needs.
        database.messages().markThreadRead(readRoot.id)

        val unreadRoot = me.root("still unread", now - 500)
        given(unreadRoot)

        val rows = refresh(unreadOnly = true)

        assertEquals(listOf(unreadRoot.id), rows.map { it.rootId })
    }

    // ---- search box: authorFilter / textQuery -------------------------------------------

    @Test
    fun authorFilterNarrowsToThreadsContainingThatAuthor() = runTest {
        listenTo(alice.key, bob.key)
        val aliceRoot = alice.root("alice's post", now - 2_000)
        val bobRoot = bob.root("bob's post", now - 1_000)
        given(aliceRoot, bobRoot)

        val rows = refresh(authorFilter = alice.key)

        assertEquals(listOf(aliceRoot.id), rows.map { it.rootId })
    }

    @Test
    fun authorFilterMatchesAReplyAuthorToo() = runTest {
        // Not just root authors — the filter narrows to any thread the person appears in.
        listenTo(alice.key, bob.key)
        val root = alice.root("root", now - 2_000)
        val bobsReply = bob.reply(root.id, root.id, "bob chimes in", now - 1_000)
        given(root, bobsReply)

        val rows = refresh(authorFilter = bob.key)

        assertEquals(listOf(root.id), rows.map { it.rootId })
    }

    @Test
    fun authorFilterExcludesThreadsThatPersonNeverAppearsIn() = runTest {
        listenTo(alice.key, bob.key)
        given(alice.root("alice's post", now - 1_000))

        assertTrue(refresh(authorFilter = bob.key).isEmpty())
    }

    @Test
    fun textQueryMatchesRootText() = runTest {
        listenTo(alice.key)
        val match = alice.root("a post about kayaking", now - 2_000)
        val noMatch = alice.root("a post about knitting", now - 1_000)
        given(match, noMatch)

        val rows = refresh(textQuery = "kayak")

        assertEquals(listOf(match.id), rows.map { it.rootId })
    }

    @Test
    fun textQueryMatchesReplyTextToo() = runTest {
        listenTo(alice.key, bob.key)
        val root = alice.root("root", now - 2_000)
        val reply = bob.reply(root.id, root.id, "mentions kayaking here", now - 1_000)
        given(root, reply)

        val rows = refresh(textQuery = "kayak")

        assertEquals(listOf(root.id), rows.map { it.rootId })
    }

    @Test
    fun aRootMatchRanksAboveAReplyOnlyMatch() = runTest {
        listenTo(alice.key, bob.key)
        val replyOnlyMatchRoot = alice.root("root one", now - 1_000)
        bob.reply(replyOnlyMatchRoot.id, replyOnlyMatchRoot.id, "kayak mentioned only here", now - 500)
            .let { given(replyOnlyMatchRoot, it) }
        val rootMatch = alice.root("kayak trip planning", now - 5_000) // older, but matches the root

        given(rootMatch)

        val rows = refresh(textQuery = "kayak")

        assertEquals("root match ranks first despite being older", rootMatch.id, rows.first().rootId)
    }

    @Test
    fun textQueryWithNoMatchesReturnsNothing() = runTest {
        listenTo(alice.key)
        given(alice.root("a post about knitting", now - 1_000))

        assertTrue(refresh(textQuery = "kayak").isEmpty())
    }

    @Test
    fun favouriteAndUnreadFlagsReflectTheirTables() = runTest {
        val root = me.root("star me", now - 1_000)
        given(root)
        database.favourites().star(FavouriteRootEntity(root.id, now))

        val row = refresh().single()

        assertTrue(row.isFavourite)
        assertTrue("RoomSyncStore.apply leaves incoming messages unread", row.hasUnread)
    }

    @Test
    fun aThreadAlreadyFullyReadHasNoUnreadFlag() = runTest {
        val root = me.root("read this", now - 1_000)
        given(root)
        database.messages().markThreadRead(root.id)

        assertFalse(refresh().single().hasUnread)
    }
}
