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
    private fun myReply(root: MessageId, parent: MessageId?, text: String) =
        MessageFactory.createReply(me, root, parent, text, clock++, meSigner)

    private fun summaryFor(rootId: MessageId, summaries: List<ThreadSummary>) =
        summaries.first { it.rootId == rootId }

    private fun rootIdsIn(summaries: List<ThreadSummary>) = summaries.map { it.rootId }.toSet()

    @Test
    fun `a thread authored entirely by someone you listen to is in Listening`() {
        val root = myRoot("hello")
        val result = HomeThreadClassifier.classify(listOf(root), listenScope = setOf(me), myAuthor = null)

        assertEquals(setOf(root.threadRoot), rootIdsIn(result.listening))
        assertTrue(result.gossip.isEmpty())
    }

    @Test
    fun `a thread with no listened author anywhere in it is Gossip`() {
        val root = theirRoot("did you hear")
        val result = HomeThreadClassifier.classify(listOf(root), listenScope = setOf(me), myAuthor = null)

        assertTrue(result.listening.isEmpty())
        assertEquals(setOf(root.threadRoot), rootIdsIn(result.gossip))
    }

    @Test
    fun `a stranger's reply in a listened author's thread still counts as Listening`() {
        // The rule asked for, and exactly what plan.md calls the context tier: a stranger
        // replying in a thread you follow doesn't fragment that conversation into Gossip.
        val root = myRoot("thoughts on the meetup?")
        val reply = theirReply(root.id, root.id, "count me in")
        val result = HomeThreadClassifier.classify(listOf(root, reply), listenScope = setOf(me), myAuthor = null)

        assertEquals(setOf(root.threadRoot), rootIdsIn(result.listening))
        assertTrue(result.gossip.isEmpty())
    }

    @Test
    fun `removing the last listened author demotes a thread back to Gossip`() {
        val root = myRoot("hello")
        val withListen = HomeThreadClassifier.classify(listOf(root), listenScope = setOf(me), myAuthor = null)
        val withoutListen = HomeThreadClassifier.classify(listOf(root), listenScope = emptySet(), myAuthor = null)

        assertEquals(setOf(root.threadRoot), rootIdsIn(withListen.listening))
        assertEquals(setOf(root.threadRoot), rootIdsIn(withoutListen.gossip))
    }

    @Test
    fun `unrelated threads sort independently into their own tabs`() {
        val mine = myRoot("hello")
        val theirs = theirRoot("unrelated")
        val result = HomeThreadClassifier.classify(listOf(mine, theirs), listenScope = setOf(me), myAuthor = null)

        assertEquals(setOf(mine.threadRoot), rootIdsIn(result.listening))
        assertEquals(setOf(theirs.threadRoot), rootIdsIn(result.gossip))
    }

    @Test
    fun `an author who is not you can also be listened to`() {
        val root = theirRoot("hello from a stranger you follow")
        val result = HomeThreadClassifier.classify(listOf(root), listenScope = setOf(stranger), myAuthor = null)

        assertEquals(setOf(root.threadRoot), rootIdsIn(result.listening))
    }

    @Test
    fun `with nobody listened to, everything held lands in Gossip`() {
        val mine = myRoot("hello")
        val theirs = theirRoot("unrelated")
        val result = HomeThreadClassifier.classify(listOf(mine, theirs), listenScope = emptySet(), myAuthor = null)

        assertTrue(result.listening.isEmpty())
        assertEquals(setOf(mine.threadRoot, theirs.threadRoot), rootIdsIn(result.gossip))
    }

    @Test
    fun `a preview carries the root's author, text and timestamp`() {
        val root = myRoot("thoughts on the meetup?")
        val result = HomeThreadClassifier.classify(listOf(root), listenScope = setOf(me), myAuthor = null)

        val summary = summaryFor(root.threadRoot, result.listening)
        assertEquals(me, summary.rootAuthor)
        assertEquals("thoughts on the meetup?", summary.rootText)
        assertEquals(root.body.timestampMillis, summary.rootTimestamp)
    }

    @Test
    fun `a missing root leaves the preview's root fields null`() {
        val root = theirRoot("pruned away")
        val reply = theirReply(root.id, root.id, "what is left")
        val result = HomeThreadClassifier.classify(listOf(reply), listenScope = emptySet(), myAuthor = null)

        val summary = summaryFor(root.threadRoot, result.gossip)
        assertEquals(null, summary.rootAuthor)
        assertEquals(null, summary.rootText)
        assertEquals(null, summary.rootTimestamp)
    }

    @Test
    fun `a reply from a listened author sets the latest-listened preview`() {
        val root = theirRoot("did you hear")
        val reply = myReply(root.id, root.id, "yes, and here's more")
        val result = HomeThreadClassifier.classify(listOf(root, reply), listenScope = setOf(me), myAuthor = null)

        val summary = summaryFor(root.threadRoot, result.listening)
        assertEquals(me, summary.latestListenedAuthor)
        assertEquals("yes, and here's more", summary.latestListenedText)
        assertEquals(reply.body.timestampMillis, summary.latestListenedTimestamp)
    }

    @Test
    fun `the root alone, even when authored by a listened author, sets no latest-listened preview`() {
        // Root plus reply is the rule only when a *reply* exists — showing the root's own
        // author a second time as "the latest listened reply" would be a redundant no-op.
        val root = myRoot("hello")
        val result = HomeThreadClassifier.classify(listOf(root), listenScope = setOf(me), myAuthor = null)

        val summary = summaryFor(root.threadRoot, result.listening)
        assertEquals(null, summary.latestListenedAuthor)
        assertEquals(null, summary.latestListenedText)
        assertEquals(null, summary.latestListenedTimestamp)
    }

    @Test
    fun `a gossip thread never carries a latest-listened preview`() {
        // True by construction — no listened author appears anywhere in a Gossip thread —
        // but pinned explicitly since it's exactly the invariant the two tabs rely on.
        val root = theirRoot("did you hear")
        val reply = theirReply(root.id, root.id, "not from me")
        val result = HomeThreadClassifier.classify(listOf(root, reply), listenScope = setOf(me), myAuthor = null)

        val summary = summaryFor(root.threadRoot, result.gossip)
        assertEquals(null, summary.latestListenedAuthor)
    }

    @Test
    fun `the newest listened reply wins when more than one exists`() {
        val root = theirRoot("did you hear")
        val first = myReply(root.id, root.id, "first thought")
        val second = myReply(root.id, root.id, "actually, more importantly")
        val result = HomeThreadClassifier.classify(listOf(root, first, second), listenScope = setOf(me), myAuthor = null)

        val summary = summaryFor(root.threadRoot, result.listening)
        assertEquals("actually, more importantly", summary.latestListenedText)
    }

    @Test
    fun `a thread you started lands in Listening, even with nobody listened to`() {
        val root = myRoot("hello")
        val result = HomeThreadClassifier.classify(listOf(root), listenScope = emptySet(), myAuthor = me)

        assertEquals(setOf(root.threadRoot), rootIdsIn(result.listening))
        assertTrue(result.gossip.isEmpty())
    }

    @Test
    fun `a thread you only replied to also lands in Listening`() {
        val root = theirRoot("did you hear")
        val reply = myReply(root.id, root.id, "yes, actually")
        val result = HomeThreadClassifier.classify(listOf(root, reply), listenScope = emptySet(), myAuthor = me)

        assertEquals(setOf(root.threadRoot), rootIdsIn(result.listening))
        assertTrue(result.gossip.isEmpty())
    }

    @Test
    fun `without myAuthor, a thread with only your own messages is Gossip as before`() {
        // Pins the default: this is opt-in behaviour, not something that happens by accident
        // when a caller forgets to pass an identity.
        val root = myRoot("hello")
        val result = HomeThreadClassifier.classify(listOf(root), listenScope = emptySet(), myAuthor = null)

        assertTrue(result.listening.isEmpty())
        assertEquals(setOf(root.threadRoot), rootIdsIn(result.gossip))
    }

    @Test
    fun `your own reply can also be the latest-listened preview`() {
        val root = theirRoot("did you hear")
        val reply = myReply(root.id, root.id, "yes, actually")
        val result = HomeThreadClassifier.classify(listOf(root, reply), listenScope = emptySet(), myAuthor = me)

        val summary = summaryFor(root.threadRoot, result.listening)
        assertEquals(me, summary.latestListenedAuthor)
        assertEquals("yes, actually", summary.latestListenedText)
    }
}
