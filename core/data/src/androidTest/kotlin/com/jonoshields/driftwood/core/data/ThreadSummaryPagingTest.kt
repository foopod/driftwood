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
    private val alice = Person(2) // followed
    private val bob = Person(3) // followed
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

    private suspend fun follow(vararg authors: AuthorId) {
        authors.forEach { database.follow().add(FollowEntity(it, now)) }
    }

    /** Applies as sync would — tier is computed from the current follow list at apply time. */
    private suspend fun given(vararg messages: Message) {
        syncStore.apply(PhaseOutcome(messages.toList(), emptyList(), emptyMap()), now)
    }

    private suspend fun refresh(
        tab: FeedTab = FeedTab.FOLLOWING,
        unreadOnly: Boolean = false,
        authorFilter: AuthorId? = null,
        textQuery: String? = null,
    ): List<ThreadSummaryRow> {
        val source: PagingSource<Int, ThreadSummaryRow> =
            database.messages().pagedThreads(me.key, tab.name, unreadOnly, authorFilter, textQuery)
        val page = TestPager(config, source).refresh() as PagingSource.LoadResult.Page
        return page.data
    }

    private suspend fun contextTab(unreadOnly: Boolean = false): List<ThreadSummaryRow> =
        refresh(tab = FeedTab.CONTEXT, unreadOnly = unreadOnly)

    private suspend fun otherTab(unreadOnly: Boolean = false): List<ThreadSummaryRow> =
        refresh(tab = FeedTab.OTHER, unreadOnly = unreadOnly)

    @Test
    fun aRootFromSomeoneListenedToLandsInFollowing() = runTest {
        follow(alice.key)
        val root = alice.root("hello", now - 1_000)
        given(root)

        val rows = refresh()

        assertEquals(1, rows.size)
        assertEquals(root.id, rows.single().rootId)
    }

    @Test
    fun aRootFromMyselfLandsInFollowingEvenThoughSelfIsNeverInTheListenList() = runTest {
        val root = me.root("my own post", now - 1_000)
        given(root)

        val rows = refresh()

        assertEquals(1, rows.size)
        assertEquals(root.id, rows.single().rootId)
    }

    @Test
    fun aRootFromAStrangerWithNoInvolvementLandsOnTheOtherTabOnly() = runTest {
        val root = carol.root("stranger's post", now - 1_000)
        given(root)

        assertTrue(refresh().isEmpty())
        assertTrue(contextTab().isEmpty())
        assertEquals(listOf(root.id), otherTab().map { it.rootId })
    }

    @Test
    fun aStrangersThreadAFollowedPersonRepliedToLandsInContextNotFollowing() = runTest {
        // The behaviour change Option A is actually for: today "My Circle" would have shown
        // this thread since Alice's reply is in it; tab membership now follows the root alone.
        follow(alice.key)
        val root = carol.root("stranger's post", now - 2_000)
        val aliceReplies = alice.reply(root.id, root.id, "joining in", now - 1_000)
        given(root, aliceReplies)

        assertTrue("no longer in Following, since the root itself is a stranger's", refresh().isEmpty())
        assertEquals(listOf(root.id), contextTab().map { it.rootId })
        assertTrue(otherTab().isEmpty())
    }

    @Test
    fun myOwnReplyToAStrangersThreadCountsTheSameAsAFollowedPersonsReply() = runTest {
        val root = carol.root("stranger's post", now - 2_000)
        val myReply = me.reply(root.id, root.id, "just me chiming in", now - 1_000)
        given(root, myReply)

        assertTrue(refresh().isEmpty())
        assertEquals(listOf(root.id), contextTab().map { it.rootId })
        assertTrue(otherTab().isEmpty())
    }

    @Test
    fun aStrangersThreadWithNoFollowedOrSelfReplyStaysOnOther() = runTest {
        follow(alice.key)
        val root = carol.root("stranger's post", now - 2_000)
        val strangerReply = bob.reply(root.id, root.id, "another stranger", now - 1_000) // bob unfollowed here
        given(root, strangerReply)

        assertTrue(refresh().isEmpty())
        assertTrue(contextTab().isEmpty())
        assertEquals(listOf(root.id), otherTab().map { it.rootId })
    }

    @Test
    fun aMissingRootFallsBackToTheBestTierAmongWhatRemains() = runTest {
        // The root itself was never held; the surviving reply is by a followed author, so the
        // thread still bucket into Following via the fallback, not Other by default.
        follow(alice.key)
        val root = carol.root("never held", now - 2_000)
        val aliceReply = alice.reply(root.id, root.id, "the part that remains", now - 1_000)
        given(aliceReply) // root itself never given

        assertEquals(listOf(root.id), refresh().map { it.rootId })
        assertTrue(contextTab().isEmpty())
        assertTrue(otherTab().isEmpty())
    }

    @Test
    fun aStrangerReplyInAListenedThreadNeverBecomesTheHighlightedReply() = runTest {
        // Not a "known" reply: the thread lands in Following because Alice's own root is in
        // it, but Carol replying doesn't make Carol "known" for preview purposes.
        follow(alice.key)
        val root = alice.root("what do you think?", now - 3_000)
        val strangerReply = carol.reply(root.id, root.id, "I have thoughts", now - 2_000)
        given(root, strangerReply)

        val row = refresh().single()

        assertEquals(null, row.latestKnownReplyAuthor)
    }

    @Test
    fun aVerifiedButUnfollowedAuthorsReplyIsKnownToo() = runTest {
        // Not followed — but verified, which is now enough to count as "known" for naming/preview.
        val root = alice.root("root", now - 3_000)
        val verifiedReply = carol.reply(root.id, root.id, "verified reply", now - 1_000)
        given(root, verifiedReply)
        database.contacts().upsert(ContactEntity(carol.key, nickname = null, confirmedAt = now, verified = true))

        val row = otherTab().single()

        assertEquals(carol.key, row.latestKnownReplyAuthor)
        assertEquals(1, row.knownReplyCount)
    }

    @Test
    fun theLatestListenedReplyIsPickedOverAnEarlierOne() = runTest {
        // The greatest-n-per-group case this query exists to get right: two in-scope replies
        // in the same thread, only the newest should surface.
        follow(alice.key, bob.key)
        val root = alice.root("root", now - 5_000)
        val earlier = bob.reply(root.id, root.id, "earlier reply", now - 4_000)
        val later = bob.reply(root.id, root.id, "later reply", now - 1_000)
        given(root, earlier, later)

        val row = refresh().single()

        assertEquals("later reply", row.latestKnownReplyText)
        assertEquals(2, row.replyCount)
    }

    @Test
    fun theSecondKnownReplyCarriesItsOwnTextAndTimestampFromADifferentAuthor() = runTest {
        follow(alice.key, bob.key)
        val root = alice.root("root", now - 5_000)
        val fromBob = bob.reply(root.id, root.id, "bob's take", now - 3_000)
        val fromAlice = alice.reply(root.id, root.id, "alice's take", now - 1_000)
        given(root, fromBob, fromAlice)

        val row = refresh().single()

        assertEquals(alice.key, row.latestKnownReplyAuthor)
        assertEquals("alice's take", row.latestKnownReplyText)
        assertEquals(bob.key, row.secondKnownReplyAuthor)
        assertEquals("bob's take", row.secondKnownReplyText)
    }

    @Test
    fun replyAndKnownCountsReflectTheThread() = runTest {
        follow(alice.key)
        val root = alice.root("root", now - 5_000)
        val known = alice.reply(root.id, root.id, "known reply", now - 4_000)
        val stranger = carol.reply(root.id, root.id, "stranger reply", now - 3_000)
        given(root, known, stranger)
        database.messages().setRead(known.id, true)

        val row = refresh().single()

        assertEquals(2, row.replyCount)
        assertEquals(1, row.unreadReplyCount) // only the stranger reply is unread
        assertEquals(1, row.knownReplyCount)
        assertEquals(0, row.knownUnreadReplyCount) // the one known reply was marked read
    }

    @Test
    fun rootUnreadIsIndependentOfReplyReadState() = runTest {
        val root = me.root("root", now - 2_000)
        val reply = alice.reply(root.id, root.id, "reply", now - 1_000)
        given(root, reply)
        database.messages().setRead(root.id, true)
        // Root is read, but the reply is still unread (RoomSyncStore leaves incoming unread).

        val row = refresh().single()

        assertFalse(row.rootUnread)
        assertEquals(1, row.unreadReplyCount)
    }

    @Test
    fun missingRootReportsRootUnreadFalse() = runTest {
        // A reply whose root was never synced in — root_author/text/timestamp all null.
        follow(alice.key)
        val root = alice.root("never held", now - 2_000)
        val reply = alice.reply(root.id, root.id, "orphaned reply", now - 1_000)
        given(reply) // root itself never given

        val row = refresh().single()

        assertEquals(null, row.rootAuthor)
        assertFalse(row.rootUnread)
    }

    @Test
    fun sortIsNewestFirst() = runTest {
        follow(alice.key)
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
        follow(alice.key, bob.key)
        val aliceRoot = alice.root("alice's post", now - 2_000)
        val bobRoot = bob.root("bob's post", now - 1_000)
        given(aliceRoot, bobRoot)

        val rows = refresh(authorFilter = alice.key)

        assertEquals(listOf(aliceRoot.id), rows.map { it.rootId })
    }

    @Test
    fun authorFilterMatchesAReplyAuthorToo() = runTest {
        // Not just root authors — the filter narrows to any thread the person appears in.
        follow(alice.key, bob.key)
        val root = alice.root("root", now - 2_000)
        val bobsReply = bob.reply(root.id, root.id, "bob chimes in", now - 1_000)
        given(root, bobsReply)

        val rows = refresh(authorFilter = bob.key)

        assertEquals(listOf(root.id), rows.map { it.rootId })
    }

    @Test
    fun authorFilterExcludesThreadsThatPersonNeverAppearsIn() = runTest {
        follow(alice.key, bob.key)
        given(alice.root("alice's post", now - 1_000))

        assertTrue(refresh(authorFilter = bob.key).isEmpty())
    }

    @Test
    fun textQueryMatchesRootText() = runTest {
        follow(alice.key)
        val match = alice.root("a post about kayaking", now - 2_000)
        val noMatch = alice.root("a post about knitting", now - 1_000)
        given(match, noMatch)

        val rows = refresh(textQuery = "kayak")

        assertEquals(listOf(match.id), rows.map { it.rootId })
    }

    @Test
    fun textQueryMatchesReplyTextToo() = runTest {
        follow(alice.key, bob.key)
        val root = alice.root("root", now - 2_000)
        val reply = bob.reply(root.id, root.id, "mentions kayaking here", now - 1_000)
        given(root, reply)

        val rows = refresh(textQuery = "kayak")

        assertEquals(listOf(root.id), rows.map { it.rootId })
    }

    @Test
    fun aRootMatchRanksAboveAReplyOnlyMatch() = runTest {
        follow(alice.key, bob.key)
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
        follow(alice.key)
        given(alice.root("a post about knitting", now - 1_000))

        assertTrue(refresh(textQuery = "kayak").isEmpty())
    }

    @Test
    fun pinAndUnreadFlagsReflectTheirTables() = runTest {
        val root = me.root("pin me", now - 1_000)
        given(root)
        database.pins().pin(PinnedRootEntity(root.id, now))

        val row = refresh().single()

        assertTrue(row.isPinned)
        assertTrue("RoomSyncStore.apply leaves incoming messages unread", row.rootUnread)
    }

    @Test
    fun aThreadAlreadyFullyReadHasNoUnreadFlag() = runTest {
        val root = me.root("read this", now - 1_000)
        given(root)
        database.messages().markThreadRead(root.id)

        assertFalse(refresh().single().rootUnread)
    }

    private suspend fun unreadCountsByTab(): Map<String, Int> =
        database.messages().observeUnreadCountsByTab(me.key).first().associate { it.tab to it.count }

    @Test
    fun unreadCountsByTabGroupsByFeedTabAndCountsDistinctThreads() = runTest {
        follow(alice.key)
        // FOLLOWING: alice's own root, unread by default via given().
        given(alice.root("alice's post", now - 3_000))
        // CONTEXT: a stranger's root that I replied to, still unread.
        val strangersRoot = carol.root("stranger's post", now - 2_000)
        given(strangersRoot, me.reply(strangersRoot.id, strangersRoot.id, "joining in", now - 1_500))
        // OTHER: a stranger's root with no involvement from me or alice.
        given(carol.root("unrelated stranger post", now - 1_000))

        val counts = unreadCountsByTab()

        assertEquals(1, counts["FOLLOWING"])
        assertEquals(1, counts["CONTEXT"])
        assertEquals(1, counts["OTHER"])
    }

    @Test
    fun unreadCountsByTabExcludesThreadsWithNothingUnread() = runTest {
        val root = me.root("read this", now - 1_000)
        given(root)
        database.messages().markThreadRead(root.id)

        assertTrue(unreadCountsByTab().isEmpty())
    }

    @Test
    fun unreadCountsByTabCountsAThreadOnceEvenWithMultipleUnreadReplies() = runTest {
        follow(alice.key)
        val root = alice.root("alice's post", now - 3_000)
        val reply1 = alice.reply(root.id, root.id, "reply one", now - 2_000)
        val reply2 = alice.reply(root.id, root.id, "reply two", now - 1_000)
        given(root, reply1, reply2)

        assertEquals(1, unreadCountsByTab()["FOLLOWING"])
    }
}
