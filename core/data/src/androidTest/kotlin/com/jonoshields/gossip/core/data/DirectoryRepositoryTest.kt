package com.jonoshields.gossip.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jonoshields.gossip.core.identity.IdentityStore
import com.jonoshields.gossip.core.identity.SeedCipher
import com.jonoshields.gossip.core.identity.SeedStorage
import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.model.Ed25519Signer
import com.jonoshields.gossip.core.model.ProfileCodec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectoryRepositoryTest {

    private class InMemorySeedStorage : SeedStorage {
        private var bytes: ByteArray? = null
        override fun read() = bytes
        override fun write(record: ByteArray) { bytes = record.copyOf() }
        override fun clear() { bytes = null }
    }

    private val passthroughCipher = object : SeedCipher {
        override fun seal(plaintext: ByteArray) = plaintext.copyOf()
        override fun open(sealed: ByteArray) = sealed.copyOf()
    }

    private lateinit var database: GossipDatabase
    private lateinit var identity: IdentityStore
    private var now = 1_700_000_000_000L

    // Someone else, with their own key, whose claims we receive rather than make.
    private val strangerSigner = Ed25519Signer(ByteArray(32) { (it + 40).toByte() })
    private val stranger: AuthorId = strangerSigner.publicKey

    private fun directory() = RoomDirectoryRepository(database, identity, { now })
    private fun messages() = RoomMessageRepository(database, identity, { now }, com.jonoshields.gossip.core.store.StorageConfig())

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, GossipDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        identity = IdentityStore(passthroughCipher, InMemorySeedStorage()).apply { create() }
    }

    @After
    fun tearDown() = database.close()

    private fun strangerClaims(name: String, at: Long) =
        ProfileCodec.encode(ProfileCodec.create(stranger, name, at, strangerSigner))

    @Test
    fun ownUsernameIsSignedAndStored() = runTest {
        val repository = directory()

        val profile = repository.setMyUsername("jono").getOrThrow()

        assertEquals("jono", profile.username)
        assertEquals(identity.publicKey(), profile.author)
        assertEquals("jono", repository.myProfile().getOrThrow()?.username)
    }

    @Test
    fun ownUsernameCanBeChanged() = runTest {
        val repository = directory()
        repository.setMyUsername("jono").getOrThrow()
        now += 1000
        repository.setMyUsername("jonathan").getOrThrow()

        assertEquals("jonathan", repository.myProfile().getOrThrow()?.username)
    }

    @Test
    fun anUnusableUsernameIsATypedErrorNotACrash() = runTest {
        val repository = directory()
        val error = repository.setMyUsername("").exceptionOrNull()
        assertTrue("got $error", error is DataError.InvalidMessage)
    }

    @Test
    fun aReceivedClaimIsStored() = runTest {
        val repository = directory()

        val ingested = repository.ingest(strangerClaims("sam", now), now).getOrThrow()

        assertEquals("sam", ingested?.username)
        assertEquals("sam", repository.observeNames().first()[stranger]?.label)
    }

    @Test
    fun aTamperedClaimIsRejectedAndStoresNothing() = runTest {
        val repository = directory()
        val record = strangerClaims("sam", now)
        val tampered = record.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        assertNull(repository.ingest(tampered, now).getOrThrow())
        assertTrue(repository.observeNames().first().isEmpty())
    }

    @Test
    fun aNewerClaimReplacesAnOlderOne() = runTest {
        val repository = directory()
        repository.ingest(strangerClaims("sam", now), now).getOrThrow()

        now += 10_000
        repository.ingest(strangerClaims("samantha", now), now).getOrThrow()

        assertEquals("samantha", repository.observeNames().first()[stranger]?.label)
    }

    @Test
    fun anOlderClaimDoesNotOverwriteANewerOne() = runTest {
        // Replays must not roll a name back to something someone used to be called.
        val repository = directory()
        val old = strangerClaims("sam", now)
        now += 10_000
        repository.ingest(strangerClaims("samantha", now), now).getOrThrow()

        assertNull(repository.ingest(old, now).getOrThrow())
        assertEquals("samantha", repository.observeNames().first()[stranger]?.label)
    }

    @Test
    fun aForwardDatedClaimCannotPinANamePermanently() = runTest {
        // Clamped to arrival, like effective_time for messages (plan.md §4) — otherwise a
        // claim stamped in the year 3000 could never be superseded.
        val repository = directory()
        repository.ingest(strangerClaims("first", now + 1_000_000_000), now).getOrThrow()

        now += 10_000
        val later = repository.ingest(strangerClaims("second", now), now).getOrThrow()

        assertEquals("second", later?.username)
        assertEquals("second", repository.observeNames().first()[stranger]?.label)
    }

    @Test
    fun aNicknameWinsOverAClaimedName() = runTest {
        val repository = directory()
        repository.ingest(strangerClaims("definitely not dad", now), now).getOrThrow()
        database.contacts().upsert(ContactEntity(stranger, "Dad", now))

        val name = repository.observeNames().first().getValue(stranger)

        assertTrue(name.verified)
        assertEquals("Dad", name.text)
    }

    @Test
    fun aClaimedNameAlwaysCarriesItsFingerprint() = runTest {
        val repository = directory()
        repository.ingest(strangerClaims("sam", now), now).getOrThrow()

        val name = repository.observeNames().first().getValue(stranger)

        assertTrue(name.text, name.text.contains("·"))
        assertTrue(name.text.contains(name.fingerprint))
    }

    @Test
    fun aStaleUnknownNameAgesOutButAListenedOneDoesNot() = runTest {
        val repository = directory()
        repository.ingest(strangerClaims("sam", now), now).getOrThrow()

        now += com.jonoshields.gossip.core.store.DIRECTORY_TTL_MILLIS + 1
        assertEquals(setOf(stranger), repository.prune().getOrThrow())
        assertTrue(repository.observeNames().first().isEmpty())

        // Now the same again, but listened to.
        repository.ingest(strangerClaims("sam", now), now).getOrThrow()
        database.listen().add(ListenEntity(stranger, now))
        now += com.jonoshields.gossip.core.store.DIRECTORY_TTL_MILLIS + 1

        assertTrue(repository.prune().getOrThrow().isEmpty())
        assertNotNull(repository.observeNames().first()[stranger])
    }

    @Test
    fun aNameIsKeptWhileYouStillHoldTheirMessages() = runTest {
        // Your own messages, so the author is you — held content keeps the name alive well
        // past the TTL.
        val repository = directory()
        repository.setMyUsername("jono").getOrThrow()
        messages().post("something").getOrThrow()

        now += com.jonoshields.gossip.core.store.DIRECTORY_TTL_MILLIS + 1

        assertTrue(repository.prune().getOrThrow().isEmpty())
    }

    @Test
    fun aRelayCannotRenameSomeoneOnTheWayThrough() = runTest {
        // End to end: the signature is what stops a peer editing a name in transit.
        val repository = directory()
        val honest = ProfileCodec.create(stranger, "sam", now, strangerSigner)
        val forged = honest.signature + ProfileCodec.encodePreimage(stranger, "impostor", now)

        assertNull(repository.ingest(forged, now).getOrThrow())
        assertTrue(repository.observeNames().first().isEmpty())
    }

    @Test
    fun yourOwnNameNeedsNoFingerprint() = runTest {
        // You are not trying to establish whether you are really you, so your own messages
        // should not carry the apparatus for doing so.
        val repository = directory()
        repository.setMyUsername("jono").getOrThrow()

        val mine = repository.observeNames().first().getValue(identity.publicKey())

        assertTrue(mine.verified)
        assertEquals("jono", mine.text)
    }

    @Test
    fun blockingDropsTheirNameToo() = runTest {
        val repository = directory()
        repository.ingest(strangerClaims("sam", now), now).getOrThrow()
        database.blocklist().blockAuthor(BlockedAuthorEntity(stranger, now))

        assertEquals(setOf(stranger), repository.prune().getOrThrow())
    }

    @Test
    fun settingANicknameMakesTheDisplayNameVerified() = runTest {
        val repository = directory()
        repository.ingest(strangerClaims("definitely not dad", now), now).getOrThrow()

        repository.setNickname(stranger, "Dad").getOrThrow()

        val name = repository.observeNames().first().getValue(stranger)
        assertTrue(name.verified)
        assertEquals("Dad", name.text)
    }

    @Test
    fun anUnusableNicknameIsATypedErrorNotACrash() = runTest {
        val repository = directory()
        val error = repository.setNickname(stranger, "").exceptionOrNull()
        assertTrue("got $error", error is DataError.InvalidMessage)
    }

    @Test
    fun listeningAddsToTheScopeAndStoppingRemovesIt() = runTest {
        val repository = directory()

        assertTrue(repository.observeListenScope().first().isEmpty())

        repository.listenTo(stranger).getOrThrow()
        assertEquals(setOf(stranger), repository.observeListenScope().first())

        repository.stopListening(stranger).getOrThrow()
        assertTrue(repository.observeListenScope().first().isEmpty())
    }
}
