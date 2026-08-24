package com.jonoshields.driftwood.core.data

import android.content.Context
import com.jonoshields.driftwood.core.identity.IdentityStore
import com.jonoshields.driftwood.core.store.Clock
import com.jonoshields.driftwood.core.store.StorageConfig

/** The data layer's entry point: exactly one [DriftwoodDatabase] instance, plus the repositories that read it. */
class DriftwoodStore(
    context: Context,
    identity: IdentityStore,
    clock: Clock = Clock.System,
    config: StorageConfig = StorageConfig(),
) {
    private val database = buildDriftwoodDatabase(context)

    val messages: MessageRepository = RoomMessageRepository(database, identity, clock, config)

    val directory: DirectoryRepository = RoomDirectoryRepository(database, identity, clock)

    /** What a sync session reads and writes through, exposed as the port rather than the implementation. */
    val sync: com.jonoshields.driftwood.core.sync.SyncStore = RoomSyncStore(database, clock, config)
}
