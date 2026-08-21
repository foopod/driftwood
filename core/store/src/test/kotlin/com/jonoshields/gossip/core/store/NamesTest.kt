package com.jonoshields.gossip.core.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The display rule is where the safety of the whole naming design lives, so it is a pure
 * function with tests rather than a decision scattered through composables.
 */
class NameResolverTest {

    private val someone = author(1)

    @Test
    fun `a petname is shown alone because you assigned it`() {
        val name = NameResolver.resolve(someone, petname = "Dad", claimed = "xxx")
        assertTrue(name.verified)
        assertEquals("Dad", name.text)
    }

    @Test
    fun `a petname beats whatever they call themselves`() {
        // Deliberately the case where the two disagree: your name for someone is the one
        // you can trust, so it wins outright.
        val name = NameResolver.resolve(someone, petname = "Dad", claimed = "Definitely Not Dad")
        assertEquals("Dad", name.text)
    }

    @Test
    fun `a claimed name always carries its fingerprint`() {
        val name = NameResolver.resolve(someone, petname = null, claimed = "jono")
        assertFalse(name.verified)
        assertTrue("$name", name.text.startsWith("jono ·"))
        assertTrue(name.text.contains(name.fingerprint))
    }

    @Test
    fun `an unnamed key stands for itself`() {
        val name = NameResolver.resolve(someone, petname = null, claimed = null)
        assertEquals(name.fingerprint, name.text)
    }

    @Test
    fun `two keys claiming the same name remain distinguishable`() {
        // The expected case, not an anomaly (plan.md §3.1). The always-on fingerprint is
        // what keeps a collision merely confusing rather than dangerous.
        val impostor = author(2)
        val real = NameResolver.resolve(someone, null, "jono")
        val fake = NameResolver.resolve(impostor, null, "jono")

        assertEquals("jono", real.label)
        assertEquals("jono", fake.label)
        assertTrue("the rendered names must differ", real.text != fake.text)
    }

    @Test
    fun `the fingerprint comes from the key and nothing else`() {
        assertEquals(NameResolver.fingerprint(someone), NameResolver.fingerprint(author(1)))
        assertTrue(NameResolver.fingerprint(someone) != NameResolver.fingerprint(author(2)))
    }
}

class DirectoryPrunerTest {

    private val now = 1_000_000_000L
    private val ttl = 1_000L

    private fun entry(n: Int, lastSeen: Long) = DirectoryEntry(author(n), "name$n", lastSeen)

    private fun plan(
        entries: List<DirectoryEntry>,
        listen: Set<com.jonoshields.gossip.core.model.AuthorId> = emptySet(),
        contacts: Set<com.jonoshields.gossip.core.model.AuthorId> = emptySet(),
        held: Set<com.jonoshields.gossip.core.model.AuthorId> = emptySet(),
        blocked: Set<com.jonoshields.gossip.core.model.AuthorId> = emptySet(),
    ) = DirectoryPruner.plan(entries, listen, contacts, held, blocked, ttl, now)

    @Test
    fun `a stale unknown name ages out`() {
        assertEquals(setOf(author(1)), plan(listOf(entry(1, now - ttl - 1))))
    }

    @Test
    fun `a recent name is kept`() {
        assertTrue(plan(listOf(entry(1, now - ttl + 1))).isEmpty())
    }

    @Test
    fun `a listened author is immune however stale`() {
        val ancient = listOf(entry(1, 0))
        assertTrue(plan(ancient, listen = setOf(author(1))).isEmpty())
    }

    @Test
    fun `a contact is immune however stale`() {
        assertTrue(plan(listOf(entry(1, 0)), contacts = setOf(author(1))).isEmpty())
    }

    @Test
    fun `an author whose messages you still hold is kept`() {
        assertTrue(plan(listOf(entry(1, 0)), held = setOf(author(1))).isEmpty())
    }

    @Test
    fun `blocking drops the name even if you listen to them`() {
        // Blocked beats every reason to keep, exactly as it does for content.
        assertEquals(
            setOf(author(1)),
            plan(listOf(entry(1, now)), listen = setOf(author(1)), blocked = setOf(author(1))),
        )
    }

    @Test
    fun `names outlive the messages that introduced them`() {
        // The point of a TTL longer than the message window: a thread you have partly
        // pruned still reads as people talking rather than as hex.
        val recentlySeen = entry(1, now - ttl + 1)
        assertTrue(plan(listOf(recentlySeen), held = emptySet()).isEmpty())
    }

    @Test
    fun `an empty directory plans nothing`() {
        assertTrue(plan(emptyList()).isEmpty())
    }
}
