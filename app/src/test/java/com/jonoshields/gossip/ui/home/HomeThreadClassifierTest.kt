package com.jonoshields.gossip.ui.home

import com.jonoshields.gossip.core.model.Ed25519Signer
import com.jonoshields.gossip.core.model.MessageFactory
import com.jonoshields.gossip.core.model.MessageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JUnit, no Robolectric: [HomeThreadClassifier] touches nothing Android, so this can
 * run at the speed [com.jonoshields.gossip.core.store.TierClassifier]'s own tests do.
 */
class HomeThreadClassifierTest {

    private val meSigner = Ed25519Signer(ByteArray(32) { it.toByte() })
    private val me = meSigner.publicKey
    private val strangerSigner = Ed25519Signer(ByteArray(32) { (it + 90).toByte() })
    private val stranger = strangerSigner.publicKey
    private var clock = 1_000L

    private fun myRoot(text: String) = MessageFactory.createRoot(me, text, clock++, meSigner)
    private fun theirRoot(text: String) = MessageFactory.createRoot(stranger, text, clock++, strangerSigner)
    private fun theirReply(root: MessageId, parent: MessageId?, text: String) =
        MessageFactory.createReply(stranger, root, parent, text, clock++, strangerSigner)

    private fun rootIdsIn(summaries: List<ThreadSummary>) = summaries.map { it.rootId }.toSet()

    @Test
    fun `a thread authored entirely by someone you listen to is in Listening`() {
        val root = myRoot("hello")
        val result = HomeThreadClassifier.classify(listOf(root), listenScope = setOf(me))

        assertEquals(setOf(root.threadRoot), rootIdsIn(result.listening))
        assertTrue(result.gossip.isEmpty())
    }

    @Test
    fun `a thread with no listened author anywhere in it is Gossip`() {
        val root = theirRoot("did you hear")
        val result = HomeThreadClassifier.classify(listOf(root), listenScope = setOf(me))

        assertTrue(result.listening.isEmpty())
        assertEquals(setOf(root.threadRoot), rootIdsIn(result.gossip))
    }

    @Test
    fun `a stranger's reply in a listened author's thread still counts as Listening`() {
        // The rule asked for, and exactly what plan.md calls the context tier: a stranger
        // replying in a thread you follow doesn't fragment that conversation into Gossip.
        val root = myRoot("thoughts on the meetup?")
        val reply = theirReply(root.id, root.id, "count me in")
        val result = HomeThreadClassifier.classify(listOf(root, reply), listenScope = setOf(me))

        assertEquals(setOf(root.threadRoot), rootIdsIn(result.listening))
        assertTrue(result.gossip.isEmpty())
    }

    @Test
    fun `removing the last listened author demotes a thread back to Gossip`() {
        val root = myRoot("hello")
        val withListen = HomeThreadClassifier.classify(listOf(root), listenScope = setOf(me))
        val withoutListen = HomeThreadClassifier.classify(listOf(root), listenScope = emptySet())

        assertEquals(setOf(root.threadRoot), rootIdsIn(withListen.listening))
        assertEquals(setOf(root.threadRoot), rootIdsIn(withoutListen.gossip))
    }

    @Test
    fun `unrelated threads sort independently into their own tabs`() {
        val mine = myRoot("hello")
        val theirs = theirRoot("unrelated")
        val result = HomeThreadClassifier.classify(listOf(mine, theirs), listenScope = setOf(me))

        assertEquals(setOf(mine.threadRoot), rootIdsIn(result.listening))
        assertEquals(setOf(theirs.threadRoot), rootIdsIn(result.gossip))
    }

    @Test
    fun `an author who is not you can also be listened to`() {
        val root = theirRoot("hello from a stranger you follow")
        val result = HomeThreadClassifier.classify(listOf(root), listenScope = setOf(stranger))

        assertEquals(setOf(root.threadRoot), rootIdsIn(result.listening))
    }

    @Test
    fun `with nobody listened to, everything held lands in Gossip`() {
        val mine = myRoot("hello")
        val theirs = theirRoot("unrelated")
        val result = HomeThreadClassifier.classify(listOf(mine, theirs), listenScope = emptySet())

        assertTrue(result.listening.isEmpty())
        assertEquals(setOf(mine.threadRoot, theirs.threadRoot), rootIdsIn(result.gossip))
    }
}
