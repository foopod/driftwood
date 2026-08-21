package com.jonoshields.gossip.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jonoshields.gossip.core.model.Ed25519Signer
import com.jonoshields.gossip.core.store.Clock
import com.jonoshields.gossip.core.store.StorageConfig
import com.jonoshields.gossip.core.sync.DebugPeer
import com.jonoshields.gossip.core.sync.Pipe
import com.jonoshields.gossip.core.sync.Role
import com.jonoshields.gossip.core.sync.Session
import com.jonoshields.gossip.core.sync.SessionResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The M2 plan's "seeing it work" step, proven against real SQLite rather than by hand.
 *
 * MIUI on the dev device refuses `adb shell input tap` (`INJECT_EVENTS` denied — the same
 * limitation task #17 is blocked on), so the Settings button this drives cannot be
 * tap-tested from here. This is the automatable substitute: the exact same [DebugPeer] and
 * [Session] the Settings action uses, run against a real [RoomSyncStore] over real SQLite,
 * asserting the two behaviours a person at the device would be checking for — content
 * arrives, and a second run has nothing left to fetch.
 */
@RunWith(AndroidJUnit4::class)
class DebugSyncTest {

    private val now = 1_700_000_000_000L
    private val ourDevice = Ed25519Signer(ByteArray(32) { 0xAB.toByte() }).publicKey
    private lateinit var database: GossipDatabase
    private lateinit var store: RoomSyncStore

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, GossipDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomSyncStore(database, Clock { now }, StorageConfig())
    }

    @After
    fun tearDown() = database.close()

    private suspend fun runAgainstDebugPeer(): SessionResult = coroutineScope {
        val peerStore = DebugPeer.build(now)
        val clock = Clock { now }
        val (ours, theirs) = Pipe.open()
        try {
            val peer = async { Session(peerStore, clock).run(Role.RESPONDER, theirs, DebugPeer.device) }
            val result = Session(store, clock).run(Role.INITIATOR, ours, ourDevice)
            peer.await()
            result
        } finally {
            ours.close()
            theirs.close()
        }
    }

    @Test
    fun theFirstRunFetchesTheDebugPeersContentAndNames() = runTest {
        val result = runAgainstDebugPeer() as? SessionResult.Completed
            ?: throw AssertionError("expected a completed session")

        // DebugPeer seeds three messages (a root, a reply in its thread, and an unrelated
        // root) from three invented identities, each with a profile.
        assertEquals(3, result.summary.messagesAccepted)
        assertEquals(3, result.summary.profilesAccepted)
        assertEquals(0, result.summary.rejectionCount)
        assertTrue(result.summary.priorityPhaseCompleted)
    }

    @Test
    fun aSecondRunReportsNothingNewToFetch() = runTest {
        runAgainstDebugPeer()

        val second = runAgainstDebugPeer() as? SessionResult.Completed
            ?: throw AssertionError("expected a completed session")

        // Content is content-addressed, so the peer's hash-list already covers everything
        // we hold; nothing crosses the wire a second time, and no name rides with content
        // that was never sent.
        assertEquals(0, second.summary.messagesAccepted)
        assertEquals(0, second.summary.profilesAccepted)
    }
}
