package com.jonoshields.driftwood.core.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The display rule is where the naming design's safety lives, so it's a pure function with tests, not a decision scattered through composables. */
class NameResolverTest {

    private val someone = author(1)

    @Test
    fun `a nickname is shown alone because you assigned it`() {
        val name = NameResolver.resolve(someone, nickname = "Dad", username = "xxx")
        assertTrue(name.verified)
        assertEquals("Dad", name.text)
    }

    @Test
    fun `a nickname beats whatever they call themselves`() {
        // Deliberately the case where the two disagree: your name for someone is the one
        // you can trust, so it wins outright.
        val name = NameResolver.resolve(someone, nickname = "Dad", username = "Definitely Not Dad")
        assertEquals("Dad", name.text)
    }

    @Test
    fun `a claimed name always carries its fingerprint`() {
        val name = NameResolver.resolve(someone, nickname = null, username = "jono")
        assertFalse(name.verified)
        assertTrue("$name", name.text.startsWith("jono ·"))
        assertTrue(name.text.contains(name.fingerprint))
    }

    @Test
    fun `an unnamed key stands for itself`() {
        val name = NameResolver.resolve(someone, nickname = null, username = null)
        assertEquals(name.fingerprint, name.text)
    }

    @Test
    fun `two keys claiming the same name remain distinguishable`() {
        // A name collision is expected, not an anomaly — the always-on fingerprint is what keeps it merely confusing rather than dangerous.
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
        follow: Set<com.jonoshields.driftwood.core.model.AuthorId> = emptySet(),
        contacts: Set<com.jonoshields.driftwood.core.model.AuthorId> = emptySet(),
        held: Set<com.jonoshields.driftwood.core.model.AuthorId> = emptySet(),
        blocked: Set<com.jonoshields.driftwood.core.model.AuthorId> = emptySet(),
    ) = DirectoryPruner.plan(entries, follow, contacts, held, blocked, ttl, now)

    @Test
    fun `a stale unknown name ages out`() {
        assertEquals(setOf(author(1)), plan(listOf(entry(1, now - ttl - 1))))
    }

    @Test
    fun `a recent name is kept`() {
        assertTrue(plan(listOf(entry(1, now - ttl + 1))).isEmpty())
    }

    @Test
    fun `a followed author is immune however stale`() {
        val ancient = listOf(entry(1, 0))
        assertTrue(plan(ancient, follow = setOf(author(1))).isEmpty())
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
    fun `blocking drops the name even if you follow them`() {
        // Blocked beats every reason to keep, exactly as it does for content.
        assertEquals(
            setOf(author(1)),
            plan(listOf(entry(1, now)), follow = setOf(author(1)), blocked = setOf(author(1))),
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

class AuthorHueTest {

    @Test
    fun `hue is stable for a key`() {
        assertEquals(NameResolver.hue(author(1)), NameResolver.hue(author(1)), 0.0001f)
    }

    @Test
    fun `hue is in range`() {
        (1..200).forEach {
            val hue = NameResolver.hue(author(it))
            assertTrue("$hue", hue >= 0f && hue < 360f)
        }
    }

    @Test
    fun `hue is drawn from bytes the fingerprint does not show`() {
        // So the two channels are independent: an impersonator who grinds a matching
        // fingerprint does not get a matching colour thrown in for free.
        val base = ByteArray(32) { it.toByte() }
        val differentMiddle = base.copyOf().also { it[8] = 99; it[9] = 77 }

        val a = com.jonoshields.driftwood.core.model.AuthorId.of(base)
        val b = com.jonoshields.driftwood.core.model.AuthorId.of(differentMiddle)

        assertEquals("fingerprints match", NameResolver.fingerprint(a), NameResolver.fingerprint(b))
        assertTrue("hues must not", NameResolver.hue(a) != NameResolver.hue(b))
    }

    @Test
    fun `a name is never distinguished by colour alone`() {
        // Colour is unusable for a colour-blind reader, so whatever else changes, the
        // fingerprint must remain part of how a claimed name is rendered.
        val name = NameResolver.resolve(author(3), nickname = null, username = "sam")
        assertTrue(name.text.contains(name.fingerprint))
    }
}
