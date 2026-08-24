package com.jonoshields.driftwood.di

import android.content.Context
import com.jonoshields.driftwood.core.data.DirectoryRepository
import com.jonoshields.driftwood.core.data.MessageRepository
import com.jonoshields.driftwood.core.data.DriftwoodStore
import com.jonoshields.driftwood.core.identity.FileSeedStorage
import com.jonoshields.driftwood.core.identity.IdentityStore
import com.jonoshields.driftwood.core.identity.KeystoreSeedCipher
import com.jonoshields.driftwood.core.store.Clock
import com.jonoshields.driftwood.core.store.StorageConfig
import com.jonoshields.driftwood.core.sync.SyncStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/** The whole object graph — core modules stay Hilt-agnostic, assembled here. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun clock(): Clock = Clock.System

    /** Storage caps aren't user-adjustable yet — always the defaults. */
    @Provides
    @Singleton
    fun storageConfig(): StorageConfig = StorageConfig()

    @Provides
    @Singleton
    fun identityStore(@ApplicationContext context: Context): IdentityStore =
        IdentityStore(
            cipher = KeystoreSeedCipher(),
            storage = FileSeedStorage(File(context.filesDir, "identity.bin")),
        )

    /** One database for the whole app — see [DriftwoodStore] for why that matters. */
    @Provides
    @Singleton
    fun driftwoodStore(
        @ApplicationContext context: Context,
        identity: IdentityStore,
        clock: Clock,
        config: StorageConfig,
    ): DriftwoodStore = DriftwoodStore(context, identity, clock, config)

    @Provides
    @Singleton
    fun messageRepository(store: DriftwoodStore): MessageRepository = store.messages

    @Provides
    @Singleton
    fun directoryRepository(store: DriftwoodStore): DirectoryRepository = store.directory

    /** What SyncCoordinator drives a real Wi-Fi Direct/LAN sync session against. */
    @Provides
    @Singleton
    fun syncStore(store: DriftwoodStore): SyncStore = store.sync
}
