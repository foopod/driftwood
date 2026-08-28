package com.jonoshields.driftwood.ui.home

import com.jonoshields.driftwood.core.data.ThreadSummary
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.MessageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadPreviewTest {

    private fun author(seed: Int) = AuthorId.of(ByteArray(32) { seed.toByte() })

    private fun thread(
        rootUnread: Boolean = false,
        replyCount: Int = 0,
        unreadReplyCount: Int = 0,
        knownReplyCount: Int = 0,
        knownUnreadReplyCount: Int = 0,
        latestKnownReplyAuthor: AuthorId? = null,
        latestKnownReplyText: String? = null,
        latestKnownReplyTimestamp: Long? = null,
        secondKnownReplyAuthor: AuthorId? = null,
        secondKnownReplyText: String? = null,
        secondKnownReplyTimestamp: Long? = null,
        latestKnownUnreadReplyAuthor: AuthorId? = null,
        latestKnownUnreadReplyText: String? = null,
        latestKnownUnreadReplyTimestamp: Long? = null,
        secondKnownUnreadReplyAuthor: AuthorId? = null,
        secondKnownUnreadReplyText: String? = null,
        secondKnownUnreadReplyTimestamp: Long? = null,
    ) = ThreadSummary(
        rootId = MessageId.of(ByteArray(32) { 1 }),
        rootAuthor = author(1),
        rootText = "root",
        rootTimestamp = 1_000L,
        rootUnread = rootUnread,
        replyCount = replyCount,
        unreadReplyCount = unreadReplyCount,
        knownReplyCount = knownReplyCount,
        knownUnreadReplyCount = knownUnreadReplyCount,
        latestKnownReplyAuthor = latestKnownReplyAuthor,
        latestKnownReplyText = latestKnownReplyText,
        latestKnownReplyTimestamp = latestKnownReplyTimestamp,
        secondKnownReplyAuthor = secondKnownReplyAuthor,
        secondKnownReplyText = secondKnownReplyText,
        secondKnownReplyTimestamp = secondKnownReplyTimestamp,
        latestKnownUnreadReplyAuthor = latestKnownUnreadReplyAuthor,
        latestKnownUnreadReplyText = latestKnownUnreadReplyText,
        latestKnownUnreadReplyTimestamp = latestKnownUnreadReplyTimestamp,
        secondKnownUnreadReplyAuthor = secondKnownUnreadReplyAuthor,
        secondKnownUnreadReplyText = secondKnownUnreadReplyText,
        secondKnownUnreadReplyTimestamp = secondKnownUnreadReplyTimestamp,
        isPinned = false,
    )

    @Test
    fun `a never-opened root with 1 or 2 known replies shows snippets`() {
        val alice = author(2)
        for (n in 1..SNIPPET_THRESHOLD) {
            val ui = computeThreadPreview(
                thread(
                    rootUnread = true,
                    replyCount = n,
                    knownReplyCount = 1,
                    latestKnownReplyAuthor = alice,
                    latestKnownReplyText = "hi",
                    latestKnownReplyTimestamp = 2_000L,
                ),
            )

            assertTrue("n=$n should be snippets", ui.preview is ReplyPreview.Snippets)
        }
    }

    @Test
    fun `a thread with exactly 2 known replies shows both as separate snippet cards, oldest first`() {
        // latestKnownReply* is bob's newer reply (fetched newest-first for the naming path);
        // secondKnownReply* is alice's older one. Displayed order should read like the
        // conversation actually happened: alice's older reply above bob's newer one.
        val alice = author(2)
        val bob = author(3)
        val ui = computeThreadPreview(
            thread(
                rootUnread = true,
                replyCount = 2,
                knownReplyCount = 2,
                latestKnownReplyAuthor = bob,
                latestKnownReplyText = "bob's newer reply",
                latestKnownReplyTimestamp = 3_000L,
                secondKnownReplyAuthor = alice,
                secondKnownReplyText = "alice's older reply",
                secondKnownReplyTimestamp = 2_000L,
            ),
        )

        val snippets = (ui.preview as ReplyPreview.Snippets).replies
        assertEquals(2, snippets.size)
        assertEquals(alice, snippets[0].author)
        assertEquals("alice's older reply", snippets[0].text)
        assertEquals(bob, snippets[1].author)
        assertEquals("bob's newer reply", snippets[1].text)
    }

    @Test
    fun `a never-opened root with more than the threshold known replies shows a summary`() {
        val alice = author(2)
        val ui = computeThreadPreview(
            thread(
                rootUnread = true,
                replyCount = SNIPPET_THRESHOLD + 1,
                knownReplyCount = 1,
                latestKnownReplyAuthor = alice,
                latestKnownReplyText = "hi",
                latestKnownReplyTimestamp = 2_000L,
            ),
        )

        assertTrue(ui.preview is ReplyPreview.Summary)
        assertEquals(SNIPPET_THRESHOLD + 1, (ui.preview as ReplyPreview.Summary).count)
    }

    @Test
    fun `a root already read with new unread replies switches focus to the unread ones`() {
        val alice = author(2)
        val ui = computeThreadPreview(
            thread(
                rootUnread = false,
                replyCount = 10,
                unreadReplyCount = 1,
                knownReplyCount = 5,
                knownUnreadReplyCount = 1,
                latestKnownUnreadReplyAuthor = alice,
                latestKnownUnreadReplyText = "new one",
                latestKnownUnreadReplyTimestamp = 3_000L,
            ),
        )

        assertTrue(!ui.rootUnread)
        assertTrue(ui.previewUnread)
        val snippets = (ui.preview as ReplyPreview.Snippets).replies
        assertEquals(1, snippets.size)
        assertEquals(alice, snippets[0].author)
        assertEquals("new one", snippets[0].text)
    }

    @Test
    fun `a root already read with nothing new falls back to the all-replies focus, no dot anywhere`() {
        val alice = author(2)
        val ui = computeThreadPreview(
            thread(
                rootUnread = false,
                replyCount = 2,
                unreadReplyCount = 0,
                knownReplyCount = 1,
                latestKnownReplyAuthor = alice,
                latestKnownReplyText = "old news",
                latestKnownReplyTimestamp = 2_000L,
            ),
        )

        assertTrue(!ui.rootUnread)
        assertTrue(!ui.previewUnread)
        val snippets = (ui.preview as ReplyPreview.Snippets).replies
        assertEquals("old news", snippets.single().text)
    }

    @Test
    fun `a missing root falls through to the unread-replies focus when something is unread`() {
        // rootUnread is always false when the root isn't held.
        val alice = author(2)
        val ui = computeThreadPreview(
            thread(
                rootUnread = false,
                replyCount = 2,
                unreadReplyCount = 1,
                knownReplyCount = 1,
                knownUnreadReplyCount = 1,
                latestKnownUnreadReplyAuthor = alice,
                latestKnownUnreadReplyText = "orphaned",
                latestKnownUnreadReplyTimestamp = 2_000L,
            ),
        )

        assertTrue(ui.previewUnread)
    }

    @Test
    fun `zero known authors in a busy thread falls back to a plain unnamed count`() {
        val ui = computeThreadPreview(
            thread(rootUnread = true, replyCount = 10, knownReplyCount = 0),
        )

        val summary = ui.preview as ReplyPreview.Summary
        assertEquals(10, summary.count)
        assertTrue(summary.names.isEmpty())
    }

    @Test
    fun `zero known authors with a small reply count also has no snippet, just a count`() {
        val ui = computeThreadPreview(
            thread(rootUnread = true, replyCount = 2, knownReplyCount = 0),
        )

        val summary = ui.preview as ReplyPreview.Summary
        assertEquals(2, summary.count)
        assertTrue(summary.names.isEmpty())
    }

    @Test
    fun `exactly two known authors are both named with no more-count suffix`() {
        val alice = author(2)
        val bob = author(3)
        val ui = computeThreadPreview(
            thread(
                rootUnread = true,
                replyCount = 5,
                knownReplyCount = 2,
                latestKnownReplyAuthor = alice,
                latestKnownReplyText = "hi",
                latestKnownReplyTimestamp = 3_000L,
                secondKnownReplyAuthor = bob,
                secondKnownReplyText = "yo",
                secondKnownReplyTimestamp = 2_000L,
            ),
        )

        val summary = ui.preview as ReplyPreview.Summary
        assertEquals(listOf(alice, bob), summary.names)
        assertEquals(0, summary.moreCount)
    }

    @Test
    fun `three or more known authors name two and count the rest as more`() {
        val alice = author(2)
        val bob = author(3)
        val ui = computeThreadPreview(
            thread(
                rootUnread = true,
                replyCount = 6,
                knownReplyCount = 4,
                latestKnownReplyAuthor = alice,
                latestKnownReplyText = "hi",
                latestKnownReplyTimestamp = 3_000L,
                secondKnownReplyAuthor = bob,
                secondKnownReplyText = "yo",
                secondKnownReplyTimestamp = 2_000L,
            ),
        )

        val summary = ui.preview as ReplyPreview.Summary
        assertEquals(listOf(alice, bob), summary.names)
        assertEquals(2, summary.moreCount)
    }

    @Test
    fun `no replies at all means no reply count and no preview card`() {
        val ui = computeThreadPreview(thread(rootUnread = true, replyCount = 0))

        assertEquals(null, ui.replyCount)
        assertEquals(ReplyPreview.None, ui.preview)
    }

    @Test
    fun `the reply count is always the total, independent of the unread-focused preview`() {
        val alice = author(2)
        val ui = computeThreadPreview(
            thread(
                rootUnread = false,
                replyCount = 13,
                unreadReplyCount = 1,
                knownReplyCount = 5,
                knownUnreadReplyCount = 1,
                latestKnownUnreadReplyAuthor = alice,
                latestKnownUnreadReplyText = "new",
                latestKnownUnreadReplyTimestamp = 3_000L,
            ),
        )

        assertEquals(13, ui.replyCount)
    }
}
