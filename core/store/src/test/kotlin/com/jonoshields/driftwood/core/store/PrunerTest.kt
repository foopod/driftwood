package com.jonoshields.driftwood.core.store

import com.jonoshields.driftwood.core.model.AuthorId
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fair-share pruning rules, tested as pure functions over metadata so this stays exhaustive and fast. */
class PrunerTest {

    private val now = 1_000_000L
    private val window = 100L
    private val listened = author(1)
    private val stranger = author(2)

    private fun plan(
        held: List<HeldMessage>,
        follow: Set<AuthorId> = setOf(listened),
        blocklist: Blocklist = noBlocks(),
        pinnedRoots: PinnedRoots = PinnedRoots.NONE,
        budgets: PartitionBudgets = UNLIMITED,
        windowMillis: Long = window,
        nowMillis: Long = now,
    ) = Pruner.plan(held, follow, blocklist, pinnedRoots, budgets, windowMillis, nowMillis)

    private fun survivorsOf(held: List<HeldMessage>, plan: PruningPlan) =
        held.filterNot { it.id in plan.evict }

    // ---- ordering of the rules -------------------------------------------------------

    @Test
    fun `blocking beats starring`() {
        // "Never show me this person again" cannot be overridden by having pinned a thread
        // they happen to be in.
        val pinnedThread = msgId(700)
        val theirs = held(stranger, now, pinnedThread)
        val mine = held(listened, now, pinnedThread)

        val result = plan(
            listOf(theirs, mine),
            blocklist = Blocklist(setOf(stranger), emptySet()),
            pinnedRoots = pinned(pinnedThread),
        )

        assertEquals(setOf(theirs.id), result.evict)
        assertEquals(EvictionReason.BLOCKED, result.reasons[theirs.id])
    }

    @Test
    fun `a blocked root takes its whole thread including strangers`() {
        val blockedThread = msgId(500)
        val theRoot = held(stranger, now, blockedThread)
        val replyFromElsewhere = held(listened, now, blockedThread)
        val unrelated = held(listened, now, msgId(600))

        val result = plan(
            listOf(theRoot, replyFromElsewhere, unrelated),
            blocklist = Blocklist(emptySet(), setOf(blockedThread)),
        )

        assertEquals(setOf(theRoot.id, replyFromElsewhere.id), result.evict)
    }

    @Test
    fun `a blocked root still applies after the root message itself is gone`() {
        // blocked_roots is remembered separately so replies that outlive the root keep being dropped.
        val blockedThread = msgId(500)
        val orphanReply = held(listened, now, blockedThread)
        val result = plan(listOf(orphanReply), blocklist = Blocklist(emptySet(), setOf(blockedThread)))
        assertEquals(setOf(orphanReply.id), result.evict)
    }

    @Test
    fun `out-of-window messages are dropped`() {
        val old = held(listened, now - window - 1)
        val onTheBoundary = held(listened, now - window)
        val fresh = held(listened, now)

        val result = plan(listOf(old, onTheBoundary, fresh))

        assertEquals(setOf(old.id), result.evict)
        assertEquals(EvictionReason.OUT_OF_WINDOW, result.reasons[old.id])
    }

    @Test
    fun `a pinned thread survives ageing out of the window`() {
        // Pinned threads are exempt from the caps, and the window is part of how the caps
        // are kept — so a thread you have deliberately kept does not quietly expire.
        val thread = msgId(800)
        val old = held(listened, now - window - 1, thread)
        assertTrue(plan(listOf(old), pinnedRoots = pinned(thread)).evict.isEmpty())
    }

    // ---- fair share ------------------------------------------------------------------

    @Test
    fun `an author over its share loses its oldest messages first`() {
        val messages = messagesBy(listened, count = 10, startingAt = now - 20)
        val result = plan(messages, budgets = budgets(follow = 4, context = 0, gossip = 0))

        val survivors = survivorsOf(messages, result)
        assertEquals(4, survivors.size)
        // Effective times were now-20 … now-11; the four newest must be what is left.
        assertEquals(messages.takeLast(4).map { it.id }.toSet(), survivors.map { it.id }.toSet())
        assertTrue(result.evict.all { result.reasons[it] == EvictionReason.OVER_FAIR_SHARE })
    }

    @Test
    fun `the budget is split equally between authors`() {
        val a = messagesBy(author(1), 50, startingAt = now - 60)
        val b = messagesBy(author(2), 50, startingAt = now - 60)
        val result = plan(a + b, follow = setOf(author(1), author(2)), budgets = budgets(20, 0, 0))

        val survivors = survivorsOf(a + b, result)
        assertEquals(20, survivors.size)
        assertEquals(10, survivors.count { it.author == author(1) })
        assertEquals(10, survivors.count { it.author == author(2) })
    }

    @Test
    fun `unused share is redistributed to authors that need it`() {
        // A quiet author must not hold budget hostage from a prolific one.
        val quiet = messagesBy(author(1), 2, startingAt = now - 10)
        val prolific = messagesBy(author(2), 100, startingAt = now - 90)
        val result = plan(
            quiet + prolific,
            follow = setOf(author(1), author(2)),
            budgets = budgets(50, 0, 0),
        )

        val survivors = survivorsOf(quiet + prolific, result)
        assertEquals("the partition should be filled, not left at the naive 25+2", 50, survivors.size)
        assertEquals(2, survivors.count { it.author == author(1) })
        assertEquals(48, survivors.count { it.author == author(2) })
    }

    @Test
    fun `one redistribution pass can leave a partition under budget`() {
        // Known limitation: a second redistribution round that would reclaim this surplus isn't done.
        val quiet = messagesBy(author(1), 1, startingAt = now - 5)
        val middling = messagesBy(author(2), 40, startingAt = now - 50)
        val result = plan(
            quiet + middling,
            follow = setOf(author(1), author(2)),
            budgets = budgets(100, 0, 0),
        )

        // Both fit comfortably, so nothing is evicted at all — the under-use is invisible
        // here; the point is simply that no second pass is attempted.
        assertTrue(result.evict.isEmpty())
    }

    @Test
    fun `a pinned thread consumes nobody's share`() {
        val pinnedThread = msgId(900)
        val kept = messagesBy(listened, 10, threadRoot = pinnedThread, startingAt = now - 20)
        val ordinary = messagesBy(listened, 10, threadRoot = msgId(901), startingAt = now - 10)

        val result = plan(
            kept + ordinary,
            pinnedRoots = pinned(pinnedThread),
            budgets = budgets(follow = 4, context = 0, gossip = 0),
        )

        val survivors = survivorsOf(kept + ordinary, result)
        assertEquals("10 pinned plus the 4 the budget allows", 14, survivors.size)
        assertTrue(kept.none { it.id in result.evict })
    }

    @Test
    fun `starring a thread protects everyone in it, not just the person you follow`() {
        // This is the point of moving the pin to the thread: you keep the conversation,
        // which means keeping the strangers' half of it too.
        val pinnedThread = msgId(910)
        val mine = messagesBy(listened, 5, threadRoot = pinnedThread, startingAt = now - 50)
        val theirs = messagesBy(stranger, 5, threadRoot = pinnedThread, startingAt = now - 50)

        val result = plan(
            mine + theirs,
            pinnedRoots = pinned(pinnedThread),
            budgets = budgets(follow = 0, context = 0, gossip = 0),
        )

        assertTrue("a zero budget must not touch a pinned thread", result.evict.isEmpty())
    }

    @Test
    fun `starring works on a thread whose root is not held`() {
        // The pin is keyed on the root id, which always exists, not the root message, which may be pruned.
        val absentRoot = msgId(920)
        val replies = messagesBy(stranger, 4, threadRoot = absentRoot, startingAt = now - window - 10)

        val result = plan(replies, pinnedRoots = pinned(absentRoot), budgets = budgets(0, 0, 0))

        assertTrue(result.evict.isEmpty())
    }

    @Test
    fun `unstarring a thread returns it to the ordinary rules`() {
        val thread = msgId(930)
        val messages = messagesBy(listened, 6, threadRoot = thread, startingAt = now - 20)

        assertTrue(plan(messages, pinnedRoots = pinned(thread), budgets = budgets(2, 0, 0)).evict.isEmpty())
        assertEquals(4, plan(messages, budgets = budgets(2, 0, 0)).evict.size)
    }

    @Test
    fun `partitions are budgeted independently`() {
        val threadA = msgId(100)
        val mine = messagesBy(listened, 10, threadRoot = threadA, startingAt = now - 20)
        val context = messagesBy(stranger, 10, threadRoot = threadA, startingAt = now - 20)
        val gossip = messagesBy(author(9), 10, threadRoot = msgId(200), startingAt = now - 20)

        val result = plan(mine + context + gossip, budgets = budgets(follow = 3, context = 2, gossip = 1))
        val survivors = survivorsOf(mine + context + gossip, result)

        assertEquals(3, survivors.count { it.author == listened })
        assertEquals(2, survivors.count { it.author == stranger })
        assertEquals(1, survivors.count { it.author == author(9) })
    }

    // ---- degenerate cases the prose does not cover ------------------------------------

    @Test
    fun `a budget smaller than the author count keeps exactly budget messages`() {
        // Naive integer division gives every author a share of zero, which would wipe the
        // partition. The remainder has to be handed out.
        val messages = (1..20).flatMap { messagesBy(author(it), 1, startingAt = now - 5) }
        val result = plan(messages, follow = (1..20).map { author(it) }.toSet(), budgets = budgets(10, 0, 0))
        assertEquals(10, survivorsOf(messages, result).size)
    }

    @Test
    fun `the remainder goes to authors in a deterministic order`() {
        val messages = (1..3).flatMap { messagesBy(author(it), 5, startingAt = now - 10) }
        val follow = (1..3).map { author(it) }.toSet()
        val first = plan(messages, follow = follow, budgets = budgets(7, 0, 0))
        val again = plan(messages.shuffled(Random(3)), follow = follow, budgets = budgets(7, 0, 0))
        assertEquals(first.evict, again.evict)

        val survivors = survivorsOf(messages, first)
        assertEquals(7, survivors.size)
        // 7 across 3 authors: base 2 each, remainder 1 to the lowest author id.
        assertEquals(3, survivors.count { it.author == author(1) })
        assertEquals(2, survivors.count { it.author == author(2) })
        assertEquals(2, survivors.count { it.author == author(3) })
    }

    @Test
    fun `a zero budget empties a partition apart from pinned threads`() {
        val pinnedThread = msgId(940)
        val messages = messagesBy(listened, 5, threadRoot = msgId(941), startingAt = now - 5)
        val kept = messagesBy(listened, 1, threadRoot = pinnedThread, startingAt = now - 5)

        val result = plan(messages + kept, pinnedRoots = pinned(pinnedThread), budgets = budgets(0, 0, 0))

        assertEquals(messages.map { it.id }.toSet(), result.evict)
    }

    @Test
    fun `an empty store plans nothing`() {
        assertTrue(plan(emptyList()).evict.isEmpty())
    }

    @Test
    fun `an empty partition does not divide by zero`() {
        val messages = messagesBy(listened, 3, startingAt = now - 5)
        assertTrue(plan(messages, budgets = budgets(10, 0, 0)).evict.isEmpty())
    }

    // ---- invariants over random corpora ----------------------------------------------

    @Test
    fun `invariants hold across randomised corpora`() {
        val random = Random(20260821)
        repeat(300) { iteration ->
            val authors = (1..random.nextInt(1, 8)).map { author(it) }
            val threads = (1..random.nextInt(1, 4)).map { msgId(it * 100) }
            val messages = (1..random.nextInt(0, 60)).map {
                held(
                    author = authors.random(random),
                    effectiveTime = now - random.nextLong(0, window * 2),
                    threadRoot = threads.random(random),
                )
            }
            val follow = authors.filter { random.nextBoolean() }.toSet()
            val blocked = Blocklist(
                authors = authors.filter { random.nextInt(8) == 0 }.toSet(),
                roots = threads.filter { random.nextInt(8) == 0 }.toSet(),
            )
            val pinnedRoots = PinnedRoots(threads.filter { random.nextInt(5) == 0 }.toSet())
            val caps = budgets(random.nextInt(0, 20), random.nextInt(0, 20), random.nextInt(0, 20))

            val result = plan(
                messages,
                follow = follow,
                blocklist = blocked,
                pinnedRoots = pinnedRoots,
                budgets = caps,
            )
            val survivors = survivorsOf(messages, result)

            fun fail(why: String): Nothing = throw AssertionError("iteration $iteration: $why")

            // Anything in a pinned thread is only ever evicted for being blocked.
            messages.filter { it.threadRoot in pinnedRoots && it.id in result.evict }.forEach { m ->
                if (result.reasons[m.id] != EvictionReason.BLOCKED) {
                    fail("pinned ${m.id} evicted for ${result.reasons[m.id]}")
                }
            }
            // Nothing blocked survives, ever.
            survivors.forEach { m ->
                if (m.author in blocked.authors) fail("blocked author survived")
                if (m.threadRoot in blocked.roots) fail("blocked root survived")
            }
            // Every survivor outside a pinned thread is in window.
            survivors.filterNot { it.threadRoot in pinnedRoots }.forEach { m ->
                if (m.effectiveTime < now - window) fail("stale message survived")
            }
            // No partition exceeds its cap, counting only unpinned threads.
            val tiers = result.tiers
            Tier.entries.forEach { tier ->
                val cap = when (tier) {
                    Tier.FOLLOW -> caps.follow
                    Tier.CONTEXT -> caps.context
                    Tier.GOSSIP -> caps.gossip
                }
                val kept = survivors.count { it.threadRoot !in pinnedRoots && tiers[it.id] == tier }
                if (kept > cap) fail("$tier kept $kept over cap $cap")
            }
            // Evicting is never gratuitous: an id is evicted at most once, with a reason.
            result.evict.forEach { id ->
                if (result.reasons[id] == null) fail("no reason recorded for $id")
            }
        }
    }
}
