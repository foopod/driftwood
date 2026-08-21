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

    // ---- nickname validation ---------------------------------------------------------

    @Test
    fun `accepts ordinary names`() {
        listOf("jono", "Jono Shields", "日本語", "sam 🙂", "a".repeat(NICKNAME_MAX_CHARS))
            .forEach { assertTrue(it, Nickname.isValid(it)) }
    }

    @Test
    fun `normalises before counting, like message text`() {
        val decomposed = "cafe" + Char(0x0301)
        assertEquals("caf" + Char(0x00E9), Nickname.validate(decomposed).getOrThrow())
        // 32 decomposed pairs are 64 code points raw and 32 once composed, so they fit.
        assertTrue(Nickname.isValid(("e" + Char(0x0301)).repeat(NICKNAME_MAX_CHARS)))
    }

    @Test
    fun `rejects empty and padded names`() {
        assertProblem(NicknameProblem.Empty, "")
        assertProblem(NicknameProblem.Empty, "   ")
        assertProblem(NicknameProblem.HasPadding, " jono")
        assertProblem(NicknameProblem.HasPadding, "jono ")
    }

    @Test
    fun `rejects over-length names`() {
        val problem = problemFor("a".repeat(NICKNAME_MAX_CHARS + 1))
        assertTrue("$problem", problem is NicknameProblem.TooLong)
    }

    @Test
    fun `rejects bidi overrides and other invisibles`() {
        assertProblem(NicknameProblem.HasInvisibleCharacters, bidiOverride)
        assertProblem(NicknameProblem.HasInvisibleCharacters, leftToRightOverride + "jono")
        assertProblem(NicknameProblem.HasInvisibleCharacters, "jo" + zeroWidthSpace + "no")
        assertProblem(NicknameProblem.HasInvisibleCharacters, "jo" + nul + "no")
        assertProblem(NicknameProblem.HasInvisibleCharacters, "jo\nno")
    }

    @Test
    fun `rejects unpaired surrogates`() {
        assertProblem(NicknameProblem.NotWellFormed, "jo" + Char(0xD83D) + "no")
    }

    // ---- signing and verification ----------------------------------------------------

    @Test
    fun `a created profile verifies`() {
        val result = ProfileCodec.verify(ProfileCodec.encode(profile("jono")))
        assertTrue("$result", result is ProfileVerifyResult.Valid)
        assertEquals("jono", (result as ProfileVerifyResult.Valid).profile.nickname)
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
            assertEquals(name, (decoded as ProfileVerifyResult.Valid).profile.nickname)
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
    fun `a nickname that is not already normalised is rejected on ingest`() {
        // Repairing it would change the bytes the signature was made over — the same rule
        // as message text in section 3.2.
        val preimage = ProfileCodec.encodePreimage(me, "cafe" + Char(0x0301), 1L)
        assertTrue(ProfileCodec.verify(signer.sign(preimage) + preimage) is ProfileVerifyResult.Rejected)
    }

    @Test
    fun `a signed but invisible-laden nickname is still rejected`() {
        // Validation is not only a create-time nicety: a hostile peer signs its own claims,
        // so ingest re-checks rather than trusting that the sender bothered.
        val preimage = ProfileCodec.encodePreimage(me, bidiOverride, 1L)
        assertTrue(ProfileCodec.verify(signer.sign(preimage) + preimage) is ProfileVerifyResult.Rejected)
    }

    @Test
    fun `creating with an unusable nickname throws rather than signing rubbish`() {
        assertTrue(runCatching { profile("") }.exceptionOrNull() is InvalidNickname)
        assertTrue(runCatching { profile(bidiOverride) }.exceptionOrNull() is InvalidNickname)
    }

    @Test
    fun `changing the name changes the bytes`() {
        assertNotEquals(
            ProfileCodec.encode(profile("jono")).toList(),
            ProfileCodec.encode(profile("sam")).toList(),
        )
    }

    private fun problemFor(raw: String): NicknameProblem? =
        (Nickname.validate(raw).exceptionOrNull() as? InvalidNickname)?.problem

    private fun assertProblem(expected: NicknameProblem, raw: String) =
        assertEquals("for the given input", expected, problemFor(raw))
}
