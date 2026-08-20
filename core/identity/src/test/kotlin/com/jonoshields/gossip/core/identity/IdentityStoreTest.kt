package com.jonoshields.gossip.core.identity

import com.jonoshields.gossip.core.crypto.Ed25519
import com.jonoshields.gossip.core.crypto.Mnemonic
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Identity lifecycle, exercised against fakes so it runs as a fast JVM test. The real
 * Keystore binding is covered separately by an instrumented test — it cannot run here.
 */
class IdentityStoreTest {

    /** Obviously not encryption. It exists to prove the seam is used, not to protect anything. */
    private class FakeSeedCipher : SeedCipher {
        var sealCount = 0
        override fun seal(plaintext: ByteArray): ByteArray {
            sealCount++
            return ByteArray(plaintext.size) { (plaintext[it] + 1).toByte() }
        }
        override fun open(sealed: ByteArray): ByteArray =
            ByteArray(sealed.size) { (sealed[it] - 1).toByte() }
    }

    private class InMemoryStorage : SeedStorage {
        var bytes: ByteArray? = null
        override fun read(): ByteArray? = bytes
        override fun write(record: ByteArray) { bytes = record.copyOf() }
        override fun clear() { bytes = null }
    }

    private val cipher = FakeSeedCipher()
    private val storage = InMemoryStorage()
    private fun store() = IdentityStore(cipher, storage, SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) })

    @Test
    fun `a fresh install has no identity`() {
        assertEquals(IdentityState.None, store().state())
    }

    @Test
    fun `creating an identity needs backup before it is ready`() {
        // The forced-backup gate: an identity that has never been written down is one
        // keystroke away from being lost forever, so it is not "ready" yet.
        val store = store()
        val author = store.create()

        assertEquals(IdentityState.NeedsBackup(author), store.state())
        store.confirmBackedUp()
        assertEquals(IdentityState.Ready(author), store.state())
    }

    @Test
    fun `an identity survives a restart`() {
        val author = store().create()
        assertEquals(IdentityState.NeedsBackup(author), store().state())
    }

    @Test
    fun `the backed-up flag survives a restart`() {
        val store = store()
        val author = store.create()
        store.confirmBackedUp()
        assertEquals(IdentityState.Ready(author), store().state())
    }

    @Test
    fun `the seed is never written in the clear`() {
        val store = store()
        store.create()
        val stored = requireNotNull(storage.bytes)
        val seed = Mnemonic.decode(store.recoveryPhrase()).let {
            (it as com.jonoshields.gossip.core.crypto.MnemonicResult.Success).seed
        }

        assertTrue("the cipher seam must actually be used", cipher.sealCount > 0)
        assertEquals(
            "the raw seed must not appear anywhere in the stored record",
            -1,
            indexOfSubsequence(stored, seed),
        )
    }

    @Test
    fun `the recovery phrase restores the same identity on another device`() {
        // The whole point: the Keystore copy never migrates, so this path is the only way
        // an identity outlives its phone.
        val original = store()
        val author = original.create()
        val phrase = original.recoveryPhrase()

        val freshDevice = IdentityStore(FakeSeedCipher(), InMemoryStorage(), SecureRandom())
        val result = freshDevice.restore(phrase)

        assertTrue("$result", result is RestoreResult.Success)
        assertEquals(author, (result as RestoreResult.Success).author)
        assertEquals(IdentityState.Ready(author), freshDevice.state())
    }

    @Test
    fun `restoring counts as backed up`() {
        // Someone restoring is holding the phrase by definition; asking them to write it
        // down again would be theatre.
        val phrase = store().let { it.create(); it.recoveryPhrase() }
        val fresh = IdentityStore(FakeSeedCipher(), InMemoryStorage(), SecureRandom())
        fresh.restore(phrase)
        assertTrue(fresh.state() is IdentityState.Ready)
    }

    @Test
    fun `a bad phrase is reported and changes nothing`() {
        val fresh = IdentityStore(FakeSeedCipher(), InMemoryStorage(), SecureRandom())
        val result = fresh.restore(List(24) { "abandon" })

        assertTrue("$result", result is RestoreResult.InvalidPhrase)
        assertEquals(IdentityState.None, fresh.state())
    }

    @Test
    fun `a short phrase is reported and changes nothing`() {
        val fresh = IdentityStore(FakeSeedCipher(), InMemoryStorage(), SecureRandom())
        assertTrue(fresh.restore(listOf("abandon", "ability")) is RestoreResult.InvalidPhrase)
        assertEquals(IdentityState.None, fresh.state())
    }

    @Test
    fun `creating over an existing identity is refused`() {
        // Silently replacing a key would destroy an identity permanently and irreversibly.
        val store = store()
        val author = store.create()

        val error = runCatching { store.create() }.exceptionOrNull()
        assertTrue("expected a refusal, got $error", error is IllegalStateException)
        assertEquals(author, store.publicKey())
    }

    @Test
    fun `restoring over an existing identity is refused`() {
        val store = store()
        store.create()
        val other = IdentityStore(FakeSeedCipher(), InMemoryStorage(), SecureRandom())
        other.create()

        assertEquals(RestoreResult.AlreadyExists, store.restore(other.recoveryPhrase()))
    }

    @Test
    fun `the signer produces signatures that verify under the public key`() {
        val store = store()
        val author = store.create()
        val message = "a message".toByteArray()

        val signature = store.signer().sign(message)

        assertTrue(Ed25519.verify(message, signature, author.toByteArray()))
    }

    @Test
    fun `two identities differ`() {
        val a = store().create()
        val b = IdentityStore(FakeSeedCipher(), InMemoryStorage(), SecureRandom()).create()
        assertNotEquals(a, b)
    }

    @Test
    fun `an unreadable record is never mistaken for having no identity`() {
        // The one irreversible mistake: if a damaged record read as "no identity", create()
        // would mint a replacement over a key the user still holds the phrase for, and the
        // app would simply become a stranger. It must fail loudly instead, so the UI can
        // offer to restore.
        storage.write(byteArrayOf(99, 99, 99))

        assertTrue(runCatching { store().state() }.exceptionOrNull() is GeneralSecurityFailure)
        assertTrue(
            "create() must not overwrite a record it cannot read",
            runCatching { store().create() }.exceptionOrNull() is GeneralSecurityFailure,
        )
    }

    @Test
    fun `a record that cannot be decrypted fails loudly`() {
        // What an invalidated or missing Keystore key looks like from here.
        val store = store()
        store.create()
        val brokenCipher = object : SeedCipher {
            override fun seal(plaintext: ByteArray) = plaintext
            override fun open(sealed: ByteArray): ByteArray = error("key unavailable")
        }
        val result = runCatching { IdentityStore(brokenCipher, storage, SecureRandom()).state() }
        assertTrue("$result", result.exceptionOrNull() is GeneralSecurityFailure)
    }

    @Test
    fun `phrase and public key stay consistent`() {
        val store = store()
        val author = store.create()
        val seed = (Mnemonic.decode(store.recoveryPhrase()) as com.jonoshields.gossip.core.crypto.MnemonicResult.Success).seed
        assertArrayEquals(author.toByteArray(), Ed25519.publicKeyFromSeed(seed))
    }

    private fun indexOfSubsequence(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > haystack.size) return -1
        outer@ for (start in 0..haystack.size - needle.size) {
            for (i in needle.indices) if (haystack[start + i] != needle[i]) continue@outer
            return start
        }
        return -1
    }
}
