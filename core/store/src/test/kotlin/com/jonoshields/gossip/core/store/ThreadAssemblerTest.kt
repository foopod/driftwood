package com.jonoshields.gossip.core.store

import com.jonoshields.gossip.core.model.Ed25519Signer
import com.jonoshields.gossip.core.model.Message
import com.jonoshields.gossip.core.model.MessageFactory
import com.jonoshields.gossip.core.model.MessageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Thread assembly (plan.md §3.2, §6). Fragmentation is the normal case here, not an error
 * case: you are expected to hold pieces of conversations, and the job is to render what you
 * have calmly rather than to pretend the gaps are failures.
 */
class ThreadAssemblerTest {

    private val signer = Ed25519Signer(ByteArray(32) { it.toByte() })
    private val me = signer.publicKey
    private var clock = 1_000L

    private fun root(text: String): Message =
        MessageFactory.createRoot(me, text, clock++, signer)

    private fun reply(to: MessageId, parent: MessageId?, text: String): Message =
        MessageFactory.createReply(me, to, parent, text, clock++, signer)

    @Test
    fun `a lone root is a thread of one`() {
        val r = root("hello")
        val view = ThreadAssembler.assemble(r.id, listOf(r))

        assertEquals(r.id, view.root?.id)
        assertTrue(view.replies.isEmpty())
    }

    @Test
    fun `replies naming the root sit directly under it`() {
        val r = root("hello")
        val a = reply(r.id, r.id, "first")
        val b = reply(r.id, r.id, "second")

        val view = ThreadAssembler.assemble(r.id, listOf(r, a, b))

        assertEquals(listOf(a.id, b.id), view.replies.map { it.message.id })
        assertTrue("naming the root is not a gap", view.replies.none { it.detached })
    }

    @Test
    fun `a reply with no parent attaches to the root and is not a gap`() {
        // parent is optional enrichment (plan.md §3.2). Its absence means the author didn't
        // target anything specific, which is not the same as a missing message.
        val r = root("hello")
        val a = reply(r.id, null, "just replying to the thread")

        val view = ThreadAssembler.assemble(r.id, listOf(r, a))

        assertEquals(1, view.replies.size)
        assertTrue(view.replies.single().detached.not())
    }

    @Test
    fun `nested replies form a tree where parents are held`() {
        val r = root("hello")
        val a = reply(r.id, r.id, "a")
        val b = reply(r.id, a.id, "b under a")
        val c = reply(r.id, b.id, "c under b")

        val view = ThreadAssembler.assemble(r.id, listOf(r, a, b, c))

        val nodeA = view.replies.single()
        assertEquals(a.id, nodeA.message.id)
        val nodeB = nodeA.children.single()
        assertEquals(b.id, nodeB.message.id)
        assertEquals(c.id, nodeB.children.single().message.id)
    }

    @Test
    fun `a reply whose parent is not held flattens to the root and is marked`() {
        // The quiet "replying to a message not carried here" marker from plan.md §6.
        val r = root("hello")
        val missing = reply(r.id, r.id, "pruned away")
        val orphan = reply(r.id, missing.id, "answering something you don't have")

        val view = ThreadAssembler.assemble(r.id, listOf(r, orphan))

        val node = view.replies.single()
        assertEquals(orphan.id, node.message.id)
        assertTrue("must be flagged as detached", node.detached)
    }

    @Test
    fun `a thread whose root is not held still assembles`() {
        // Root ids outlive root content (plan.md §3.2) — long-running threads whose original
        // root nobody still holds are a normal end state, not damage.
        val r = root("this will be pruned")
        val a = reply(r.id, r.id, "still here")
        val b = reply(r.id, a.id, "also still here")

        val view = ThreadAssembler.assemble(r.id, listOf(a, b))

        assertNull("the root message is genuinely absent", view.root)
        assertEquals(r.id, view.rootId)
        assertEquals(a.id, view.replies.single().message.id)
        assertTrue("naming an absent root is not a per-message gap", view.replies.none { it.detached })
        assertEquals(b.id, view.replies.single().children.single().message.id)
    }

    @Test
    fun `messages from other threads are ignored`() {
        val r = root("mine")
        val other = root("someone else's")
        val a = reply(r.id, r.id, "in my thread")
        val b = reply(other.id, other.id, "in the other thread")

        val view = ThreadAssembler.assemble(r.id, listOf(r, other, a, b))

        assertEquals(listOf(a.id), view.replies.map { it.message.id })
    }

    @Test
    fun `siblings are ordered deterministically`() {
        val r = root("hello")
        val a = reply(r.id, r.id, "first")
        val b = reply(r.id, r.id, "second")
        val c = reply(r.id, r.id, "third")

        val shuffled = ThreadAssembler.assemble(r.id, listOf(c, r, b, a))
        assertEquals(listOf(a.id, b.id, c.id), shuffled.replies.map { it.message.id })
    }

    @Test
    fun `a parent pointing outside the thread is treated as missing`() {
        val r = root("hello")
        val elsewhere = root("another thread")
        val confused = reply(r.id, elsewhere.id, "parent is in a different thread")

        val view = ThreadAssembler.assemble(r.id, listOf(r, elsewhere, confused))

        assertTrue(view.replies.single().detached)
    }

    @Test
    fun `every message in the thread appears exactly once`() {
        val r = root("hello")
        val a = reply(r.id, r.id, "a")
        val b = reply(r.id, a.id, "b")
        val c = reply(r.id, MessageId.of(ByteArray(32) { 0x7A }), "orphan")

        val view = ThreadAssembler.assemble(r.id, listOf(r, a, b, c))

        val seen = mutableListOf<MessageId>()
        fun walk(nodes: List<ThreadNode>) {
            nodes.forEach { seen += it.message.id; walk(it.children) }
        }
        walk(view.replies)

        assertEquals(listOf(a.id, b.id, c.id).sorted(), seen.sorted())
        assertEquals("no duplicates", seen.size, seen.toSet().size)
    }

    @Test
    fun `an empty thread is empty rather than an error`() {
        val view = ThreadAssembler.assemble(MessageId.of(ByteArray(32)), emptyList())
        assertNull(view.root)
        assertTrue(view.replies.isEmpty())
    }
}
