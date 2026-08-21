package com.jonoshields.gossip.sync

import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.store.Clock
import com.jonoshields.gossip.core.sync.DebugPeer
import com.jonoshields.gossip.core.sync.Pipe
import com.jonoshields.gossip.core.sync.Role
import com.jonoshields.gossip.core.sync.Session
import com.jonoshields.gossip.core.sync.SessionResult
import com.jonoshields.gossip.core.sync.SyncStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Runs one real session against an in-process synthetic peer — no radio, no second device.
 *
 * This is the M2 plan's "seeing it work" step: real framing, real reconciliation, real
 * verification, real ingest, landing in the same [SyncStore] ([RoomSyncStore][
 * com.jonoshields.gossip.core.data.RoomSyncStore]) the rest of the app reads. The peer is
 * [DebugPeer], the reference [SyncStore] implementation seeded with invented identities.
 */
object DebugSync {

    suspend fun run(ourStore: SyncStore, ourAuthor: AuthorId, clock: Clock): SessionResult = coroutineScope {
        val peerStore = DebugPeer.build(clock.nowMillis())
        val (ours, theirs) = Pipe.open()
        try {
            // Both sides run concurrently — the session strictly alternates turns on the
            // wire, but the two coroutines driving it still have to be running at once.
            // There is nobody to ask for confirmation here, so both sides accept — the
            // debug peer exists precisely so this path can be exercised without a human.
            val peer = async { Session(peerStore, clock).run(Role.RESPONDER, theirs, DebugPeer.device) }
            val result = Session(ourStore, clock).run(Role.INITIATOR, ours, ourAuthor)
            peer.await()
            result
        } finally {
            ours.close()
            theirs.close()
        }
    }
}
