package com.jonoshields.gossip.core.model

import com.jonoshields.gossip.core.crypto.Ed25519
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTest {

    private val signer = Ed25519Signer(ByteArray(32) { it.toByte() })
    private val me = signer.publicKey

    private fun profile(name: String, at: Long = 1_700_000_000_000L) =
        ProfileCodec.create(me, name, at, signer)

    // Built from code points rather than written literally: as characters these are
    // invisible, so a reader of this file could not tell what is being tested, and neither
    // could a reviewer of the diff that introduced them.
    private val rightToLeftOverride = Char(0x202E)
    private val leftToRightOverride = Char(0x202D)
    private val zeroWidthSpace = Char(0x200B)
    private val nul = Char(0x0000)

    /** The Trojan Source payload: renders as something other than what it contains. */
    private val bidiOverride = "evil" + rightToLeftOverride + "onoj"

    // ---- username validation ---------------------------------------------------------

    @Test
    fun `accepts ordinary names`() {
        listOf("jono", "Jono Shields", "日本語", "sam 🙂", "a".repeat(USERNAME_MAX_CHARS))
            .forEach { assertTrue(it, Username.isValid(it)) }
    }

    @Test
    fun `normalises before counting, like message text`() {
        val decomposed = "cafe" + Char(0x0301)
        assertEquals("caf" + Char(0x00E9), Username.validate(decomposed).getOrThrow())
        // 32 decomposed pairs are 64 code points raw and 32 once composed, so they fit.
        assertTrue(Username.isValid(("e" + Char(0x0301)).repeat(USERNAME_MAX_CHARS)))
    }

    @Test
    fun `rejects empty and padded names`() {
        assertProblem(UsernameProblem.Empty, "")
        assertProblem(UsernameProblem.Empty, "   ")
        assertProblem(UsernameProblem.HasPadding, " jono")
        assertProblem(UsernameProblem.HasPadding, "jono ")
    }

    @Test
    fun `rejects over-length names`() {
        val problem = problemFor("a".repeat(USERNAME_MAX_CHARS + 1))
        assertTrue("$problem", problem is UsernameProblem.TooLong)
    }

    @Test
    fun `rejects bidi overrides and other invisibles`() {
        assertProblem(UsernameProblem.HasInvisibleCharacters, bidiOverride)
        assertProblem(UsernameProblem.HasInvisibleCharacters, leftToRightOverride + "jono")
        assertProblem(UsernameProblem.HasInvisibleCharacters, "jo" + zeroWidthSpace + "no")
        assertProblem(UsernameProblem.HasInvisibleCharacters, "jo" + nul + "no")
        assertProblem(UsernameProblem.HasInvisibleCharacters, "jo\nno")
    }

    @Test
    fun `rejects unpaired surrogates`() {
        assertProblem(UsernameProblem.NotWellFormed, "jo" + Char(0xD83D) + "no")
    }

    // ---- signing and verification ----------------------------------------------------

    @Test
    fun `a created profile verifies`() {
        val result = ProfileCodec.verify(ProfileCodec.encode(profile("jono")))
        assertTrue("$result", result is ProfileVerifyResult.Valid)
        assertEquals("jono", (result as ProfileVerifyResult.Valid).profile.username)
        assertEquals(me, result.profile.author)
    }

    @Test
    fun `wire form round trips`() {
        val random = Random(4)
        repeat(200) {
            val name = listOf("jono", "日本語", "🙂", "a b c").random(random)
            val original = profile(name, random.nextLong(0, 1L shl 40))
            val decoded = ProfileCodec.verify(ProfileCodec.encode(original))
            assertTrue(decoded is ProfileVerifyResult.Valid)
            assertEquals(name, (decoded as ProfileVerifyResult.Valid).profile.username)
            assertEquals(original.timestampMillis, decoded.profile.timestampMillis)
        }
    }

    @Test
    fun `a bit flipped anywhere in the wire form is rejected`() {
        val wire = ProfileCodec.encode(profile("jono"))
        for (i in wire.indices) {
            val tampered = wire.copyOf()
            tampered[i] = (tampered[i].toInt() xor 0x01).toByte()
            assertTrue(
                "byte $i was accepted after tampering",
                ProfileCodec.verify(tampered) is ProfileVerifyResult.Rejected,
            )
        }
    }

    @Test
    fun `a relay cannot rename someone`() {
        // The reason the claim is signed at all: rewriting the name invalidates it.
        val honest = profile("jono")
        val forgedPreimage = ProfileCodec.encodePreimage(me, "someone else", honest.timestampMillis)

        val result = ProfileCodec.verify(honest.signature + forgedPreimage)

        assertEquals(
            RejectionReason.BadSignature,
            (result as ProfileVerifyResult.Rejected).reason,
        )
    }

    @Test
    fun `a profile signed by one key cannot claim another key`() {
        val someoneElse = AuthorId.of(Ed25519.publicKeyFromSeed(ByteArray(32) { (it + 9).toByte() }))
        val preimage = ProfileCodec.encodePreimage(someoneElse, "impostor", 1L)

        assertTrue(ProfileCodec.verify(signer.sign(preimage) + preimage) is ProfileVerifyResult.Rejected)
    }

    @Test
    fun `truncated input is rejected at every length`() {
        val wire = ProfileCodec.encode(profile("jono"))
        for (length in 0 until wire.size) {
            assertTrue(
                "length $length accepted",
                ProfileCodec.verify(wire.copyOf(length)) is ProfileVerifyResult.Rejected,
            )
        }
    }

    @Test
    fun `a username that is not already normalised is rejected on ingest`() {
        // Repairing it would change the bytes the signature was made over — the same rule
        // as message text in section 3.2.
        val preimage = ProfileCodec.encodePreimage(me, "cafe" + Char(0x0301), 1L)
        assertTrue(ProfileCodec.verify(signer.sign(preimage) + preimage) is ProfileVerifyResult.Rejected)
    }

    @Test
    fun `a signed but invisible-laden username is still rejected`() {
        // Validation is not only a create-time nicety: a hostile peer signs its own claims,
        // so ingest re-checks rather than trusting that the sender bothered.
        val preimage = ProfileCodec.encodePreimage(me, bidiOverride, 1L)
        assertTrue(ProfileCodec.verify(signer.sign(preimage) + preimage) is ProfileVerifyResult.Rejected)
    }

    @Test
    fun `creating with an unusable username throws rather than signing rubbish`() {
        assertTrue(runCatching { profile("") }.exceptionOrNull() is InvalidUsername)
        assertTrue(runCatching { profile(bidiOverride) }.exceptionOrNull() is InvalidUsername)
    }

    @Test
    fun `changing the name changes the bytes`() {
        assertNotEquals(
            ProfileCodec.encode(profile("jono")).toList(),
            ProfileCodec.encode(profile("sam")).toList(),
        )
    }

    private fun problemFor(raw: String): UsernameProblem? =
        (Username.validate(raw).exceptionOrNull() as? InvalidUsername)?.problem

    private fun assertProblem(expected: UsernameProblem, raw: String) =
        assertEquals("for the given input", expected, problemFor(raw))
}
