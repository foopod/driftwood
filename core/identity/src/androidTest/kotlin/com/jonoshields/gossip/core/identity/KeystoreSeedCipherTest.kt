package com.jonoshields.gossip.core.identity

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jonoshields.gossip.core.crypto.Ed25519
import java.io.File
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Keystore binding, which cannot run off-device. Everything about the identity
 * *lifecycle* is covered by fast JVM tests; this is only about the real crypto provider
 * behaving as assumed.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreSeedCipherTest {

    private val alias = "gossip.test.${System.nanoTime()}"
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "identity-test-${System.nanoTime()}",
        )
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun sealedSeedRoundTrips() {
        val cipher = KeystoreSeedCipher(alias)
        val seed = ByteArray(Ed25519.SEED_LENGTH) { it.toByte() }

        assertArrayEquals(seed, cipher.open(cipher.seal(seed)))
    }

    @Test
    fun sealedBytesDoNotContainThePlaintext() {
        val cipher = KeystoreSeedCipher(alias)
        val seed = ByteArray(Ed25519.SEED_LENGTH) { 0x5A }

        val sealed = cipher.seal(seed)

        assertNotEquals(seed.toList(), sealed.toList())
        assertTrue("plaintext must not survive in the blob", !sealed.toList().windowed(seed.size).contains(seed.toList()))
    }

    @Test
    fun sealingTwiceProducesDifferentBlobs() {
        // GCM must never reuse an IV for the same key; identical output would mean it had.
        val cipher = KeystoreSeedCipher(alias)
        val seed = ByteArray(Ed25519.SEED_LENGTH) { 7 }

        assertNotEquals(cipher.seal(seed).toList(), cipher.seal(seed).toList())
    }

    @Test
    fun aTamperedBlobFailsToOpen() {
        // GCM is authenticated: a flipped bit must be detected, not silently decrypted.
        val cipher = KeystoreSeedCipher(alias)
        val sealed = cipher.seal(ByteArray(Ed25519.SEED_LENGTH) { 3 })
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        assertTrue(runCatching { cipher.open(tampered) }.isFailure)
    }

    @Test
    fun aDifferentKeyCannotOpenTheBlob() {
        // Stands in for the real scenario the recovery phrase exists to cover: the sealed
        // record is worthless without the exact Keystore key that made it, and that key
        // never leaves this device.
        val sealed = KeystoreSeedCipher(alias).seal(ByteArray(Ed25519.SEED_LENGTH) { 9 })
        val otherAlias = "gossip.test.other.${System.nanoTime()}"
        try {
            assertTrue(runCatching { KeystoreSeedCipher(otherAlias).open(sealed) }.isFailure)
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(otherAlias)
        }
    }

    @Test
    fun identityStoreWorksEndToEndOnDevice() {
        val storage = FileSeedStorage(File(directory, "identity.bin"))
        val store = IdentityStore(KeystoreSeedCipher(alias), storage)

        assertEquals(IdentityState.None, store.state())
        val author = store.create()
        assertEquals(IdentityState.NeedsBackup(author), store.state())

        val phrase = store.recoveryPhrase()
        assertEquals(24, phrase.size)

        store.confirmBackedUp()
        assertEquals(IdentityState.Ready(author), store.state())

        // A fresh IdentityStore over the same files sees the same identity.
        val reopened = IdentityStore(KeystoreSeedCipher(alias), FileSeedStorage(File(directory, "identity.bin")))
        assertEquals(IdentityState.Ready(author), reopened.state())

        val signature = reopened.signer().sign("on device".toByteArray())
        assertTrue(Ed25519.verify("on device".toByteArray(), signature, author.toByteArray()))
    }

    @Test
    fun fileStorageWritesAtomicallyAndClears() {
        val file = File(directory, "record.bin")
        val storage = FileSeedStorage(file)

        assertNull(storage.read())
        storage.write(byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), storage.read())

        storage.write(byteArrayOf(4, 5))
        assertArrayEquals(byteArrayOf(4, 5), storage.read())
        assertTrue("no temp file should be left behind", directory.listFiles()!!.none { it.name.endsWith(".tmp") })

        storage.clear()
        assertNull(storage.read())
    }
}
