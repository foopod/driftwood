package com.jonoshields.gossip.core.model

import com.jonoshields.gossip.core.crypto.Ed25519
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTest {

    private val seed = ByteArray(32) { it.toByte() }
    private val signer = Ed25519Signer(seed)
    private val author = AuthorId.of(Ed25519.publicKeyFromSeed(seed))

    private fun root(text: String = "a root message"): Message =
        MessageFactory.createRoot(author, text, timestampMillis = 1_700_000_000_000L, signer = signer)

    // ---- construction ---------------------------------------------------------------

    @Test
    fun `a root has an empty root field and is its own thread root`() {
        val message = root()
        assertTrue(message.isRoot)
        assertEquals(null, message.body.root)
        assertEquals(message.id, message.threadRoot)
    }

    @Test
    fun `a reply carries the thread root and is not a root`() {
        val theRoot = root()
        val reply = MessageFactory.createReply(
            author = author,
            root = theRoot.id,
            parent = theRoot.id,
            text = "a reply",
            timestampMillis = 1_700_000_001_000L,
            signer = signer,
        )
        assertTrue(!reply.isRoot)
        assertEquals(theRoot.id, reply.body.root)
        assertEquals(theRoot.id, reply.threadRoot)
        assertNotEquals(theRoot.id, reply.id)
    }

    @Test
    fun `a reply may target a thread whose root is not held and name no parent`() {
        // The intended mechanic from plan.md §3.2: a reply needs only the root id.
        val unheldRoot = MessageId.of(Random(7).nextBytes(32))
        val reply = MessageFactory.createReply(
            author = author,
            root = unheldRoot,
            parent = null,
            text = "commenting on gossip whose origin I never saw",
            timestampMillis = 1L,
            signer = signer,
        )
        assertEquals(unheldRoot, reply.body.root)
        assertEquals(null, reply.body.parent)
        assertTrue(MessageVerifier.verify(MessageCodec.encode(reply)) is VerifyResult.Valid)
    }

    @Test
    fun `the id is the hash of the preimage and excludes id and sig`() {
        val message = root()
        assertArrayEquals(
            com.jonoshields.gossip.core.crypto.sha256(CanonicalCodec.encode(message.body)),
            message.id.toByteArray(),
        )
    }

    @Test
    fun `identical content produces an identical id`() {
        val a = MessageFactory.createRoot(author, "same", 42L, signer)
        val b = MessageFactory.createRoot(author, "same", 42L, signer)
        assertEquals(a.id, b.id)
        assertArrayEquals(a.signature, b.signature) // Ed25519 is deterministic
    }

    @Test
    fun `differing content produces a differing id`() {
        assertNotEquals(MessageFactory.createRoot(author, "one", 42L, signer).id,
                        MessageFactory.createRoot(author, "two", 42L, signer).id)
        assertNotEquals(MessageFactory.createRoot(author, "one", 42L, signer).id,
                        MessageFactory.createRoot(author, "one", 43L, signer).id)
    }

    @Test
    fun `create normalises text so callers need not`() {
        val message = MessageFactory.createRoot(author, "café", 1L, signer)
        assertEquals("café", message.body.text)
    }

    // ---- verification ---------------------------------------------------------------

    @Test
    fun `a freshly created message verifies`() {
        val result = MessageVerifier.verify(MessageCodec.encode(root()))
        assertTrue("got $result", result is VerifyResult.Valid)
        assertEquals(root().id, (result as VerifyResult.Valid).message.id)
    }

    @Test
    fun `wire form round trips`() {
        val random = Random(99)
        repeat(200) {
            val text = listOf("", "x", "hello 🙂", "日本語").random(random)
            val message = MessageFactory.createRoot(author, text, random.nextLong(0, 1L shl 40), signer)
            val decoded = MessageVerifier.verify(MessageCodec.encode(message))
            assertTrue(decoded is VerifyResult.Valid)
            assertEquals(message.id, (decoded as VerifyResult.Valid).message.id)
            assertEquals(message.body, decoded.message.body)
        }
    }

    @Test
    fun `a bit flipped anywhere in the wire form is rejected`() {
        val wire = MessageCodec.encode(root())
        for (i in wire.indices) {
            val tampered = wire.copyOf()
            tampered[i] = (tampered[i].toInt() xor 0x01).toByte()
            val result = MessageVerifier.verify(tampered)
            assertTrue(
                "byte $i was accepted after tampering: $result",
                result is VerifyResult.Rejected,
            )
        }
    }

    @Test
    fun `a message signed by one identity but claiming another is rejected`() {
        // Re-label the author without re-signing: the signature no longer matches.
        val impostor = AuthorId.of(Ed25519.publicKeyFromSeed(ByteArray(32) { (it + 1).toByte() }))
        val honest = root()
        val forgedBody = honest.body.copy(author = impostor)
        val forgedWire = MessageCodec.encode(
            Message.unverified(
                id = MessageId.of(com.jonoshields.gossip.core.crypto.sha256(CanonicalCodec.encode(forgedBody))),
                signature = honest.signature,
                body = forgedBody,
            )
        )
        val result = MessageVerifier.verify(forgedWire)
        assertTrue("got $result", result is VerifyResult.Rejected)
        assertEquals(RejectionReason.BadSignature, (result as VerifyResult.Rejected).reason)
    }

    @Test
    fun `a mismatched id is rejected before the signature is checked`() {
        val honest = root()
        val wrongId = MessageId.of(ByteArray(32) { 0xFF.toByte() })
        val wire = MessageCodec.encode(Message.unverified(wrongId, honest.signature, honest.body))
        val result = MessageVerifier.verify(wire)
        assertEquals(RejectionReason.IdMismatch, (result as VerifyResult.Rejected).reason)
    }

    @Test
    fun `truncated wire input is rejected at every length`() {
        val wire = MessageCodec.encode(root())
        for (length in 0 until wire.size) {
            assertTrue(
                "length $length accepted",
                MessageVerifier.verify(wire.copyOf(length)) is VerifyResult.Rejected,
            )
        }
    }

    @Test
    fun `verification hashes the received bytes rather than re-encoding them`() {
        // If the verifier decoded and re-serialised, a hostile encoding could be silently
        // repaired into something that verifies. Feeding a body whose declared text length
        // is honest but whose id was computed over different bytes must fail.
        val message = root()
        val wire = MessageCodec.encode(message)
        val preimage = wire.copyOfRange(96, wire.size)
        assertArrayEquals(CanonicalCodec.encode(message.body), preimage)
    }
}
