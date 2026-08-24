package com.jonoshields.driftwood.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jonoshields.driftwood.core.identity.IdentityStore
import com.jonoshields.driftwood.core.identity.SeedCipher
import com.jonoshields.driftwood.core.identity.SeedStorage
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.Ed25519Signer
import com.jonoshields.driftwood.core.model.MessageFactory
import com.jonoshields.driftwood.core.model.ProfileCodec
import com.jonoshields.driftwood.core.store.Clock
import com.jonoshields.driftwood.core.store.StorageConfig
import com.jonoshields.driftwood.core.store.Tier
import com.jonoshields.driftwood.core.sync.PhaseOutcome
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

    private lateinit var database: DriftwoodDatabase
    private lateinit var identity: IdentityStore
    private var now = 1_700_000_000_000L

    // Someone else, with their own key, whose claims we receive rather than make.
    private val strangerSigner = Ed25519Signer(ByteArray(32) { (it + 40).toByte() })
    private val stranger: AuthorId = strangerSigner.publicKey

    private fun directory() = RoomDirectoryRepository(database, identity, { now })
    private fun messages() = RoomMessageRepository(database, identity, { now }, StorageConfig())

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, DriftwoodDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        identity = IdentityStore(passthroughCipher, InMemorySeedStorage()).apply { create() }
    }

    @After
    fun tearDown() = database.close()

    private fun strangerClaims(name: String, at: Long) =
        ProfileCodec.encode(ProfileCodec.create(stranger, name, at, strangerSigner))

    /** Seeds a claimed username directly, the way a real sync's Ingest would persist one. */
    private suspend fun seedClaim(name: String, at: Long = now) {
        database.directory().upsert(
            DirectoryEntity(
                author = stranger,
                username = name,
                claimedAt = at,
                firstReceived = at,
                lastSeenPost = at,
                record = strangerClaims(name, at),
            )
        )
    }

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
    fun aNicknameWinsOverAClaimedName() = runTest {
        val repository = directory()
        seedClaim("definitely not dad")
        database.contacts().upsert(ContactEntity(stranger, "Dad", now))

        val name = repository.observeNames().first().getValue(stranger)

        assertTrue(name.verified)
        assertEquals("Dad", name.text)
    }

    @Test
    fun aClaimedNameAlwaysCarriesItsFingerprint() = runTest {
        val repository = directory()
        seedClaim("sam")

        val name = repository.observeNames().first().getValue(stranger)

        assertTrue(name.text, name.text.contains("·"))
        assertTrue(name.text.contains(name.fingerprint))
    }

    @Test
    fun aStaleUnknownNameAgesOutButAListenedOneDoesNot() = runTest {
        val repository = directory()
        seedClaim("sam")

        now += com.jonoshields.driftwood.core.store.DIRECTORY_TTL_MILLIS + 1
        assertEquals(setOf(stranger), repository.prune().getOrThrow())
        assertTrue(repository.observeNames().first().isEmpty())

        // Now the same again, but listened to.
        seedClaim("sam")
        database.listen().add(ListenEntity(stranger, now))
        now += com.jonoshields.driftwood.core.store.DIRECTORY_TTL_MILLIS + 1

        assertTrue(repository.prune().getOrThrow().isEmpty())
        assertNotNull(repository.observeNames().first()[stranger])
    }

    @Test
    fun aNameIsKeptWhileYouStillHoldTheirMessages() = runTest {
        // Held content keeps your own name alive well past the TTL.
        val repository = directory()
        repository.setMyUsername("jono").getOrThrow()
        messages().post("something").getOrThrow()

        now += com.jonoshields.driftwood.core.store.DIRECTORY_TTL_MILLIS + 1

        assertTrue(repository.prune().getOrThrow().isEmpty())
    }

    @Test
    fun yourOwnNameNeedsNoFingerprint() = runTest {
        val repository = directory()
        repository.setMyUsername("jono").getOrThrow()

        val mine = repository.observeNames().first().getValue(identity.publicKey())

        assertTrue(mine.verified)
        assertEquals("jono", mine.text)
    }

    @Test
    fun blockingDropsTheirNameToo() = runTest {
        val repository = directory()
        seedClaim("sam")
        database.blocklist().blockAuthor(BlockedAuthorEntity(stranger, now))

        assertEquals(setOf(stranger), repository.prune().getOrThrow())
    }

    @Test
    fun settingANicknameMakesTheDisplayNameVerified() = runTest {
        val repository = directory()
        seedClaim("definitely not dad")

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

    @Test
    fun listeningRetiersAlreadyHeldMessagesSoTheyJumpTabsImmediately() = runTest {
        val syncStore = RoomSyncStore(database, Clock { now }, StorageConfig())
        val root = MessageFactory.createRoot(stranger, "hello", now - 1_000, strangerSigner)
        syncStore.apply(PhaseOutcome(listOf(root), emptyList(), emptyMap()), now)

        // Held before ever listening to them: classified as gossip at ingest time.
        assertEquals(Tier.GOSSIP, database.messages().find(root.id)?.tier)

        val repository = directory()
        repository.listenTo(stranger).getOrThrow()
        assertEquals(Tier.LISTEN, database.messages().find(root.id)?.tier)

        repository.stopListening(stranger).getOrThrow()
        assertEquals(Tier.GOSSIP, database.messages().find(root.id)?.tier)
    }
}
