package com.jonoshields.gossip.core.data

import android.content.Context
import com.jonoshields.gossip.core.identity.IdentityStore
import com.jonoshields.gossip.core.store.Clock
import com.jonoshields.gossip.core.store.StorageConfig

/**
 * The data layer's entry point: one database, and the repositories that read it.
 *
 * Exists so there is exactly **one** [GossipDatabase] instance. Two Room instances over the
 * same file would read and write the same data, but each keeps its own invalidation
 * tracker — so a Flow in one would never fire for a write made through the other, and the
 * UI would silently go stale in ways that look like a caching bug rather than a wiring one.
 *
 * The database type itself stays internal to this module; callers get repositories.
 */
class GossipStore(
    context: Context,
    identity: IdentityStore,
    clock: Clock = Clock.System,
    config: StorageConfig = StorageConfig(),
) {
    private val database = buildGossipDatabase(context)

    val messages: MessageRepository = RoomMessageRepository(database, identity, clock, config)

    val directory: DirectoryRepository = RoomDirectoryRepository(database, identity, clock)

    /**
     * What a sync session reads and writes through. Exposed as the port rather than the
     * implementation: `:core:sync` drives this identically to the in-memory store the whole
     * protocol was proven against, which is what makes the convergence suite meaningful.
     */
    val sync: com.jonoshields.gossip.core.sync.SyncStore = RoomSyncStore(database, clock, config)
}
