package com.jonoshields.gossip.di

import android.content.Context
import com.jonoshields.gossip.core.data.DirectoryRepository
import com.jonoshields.gossip.core.data.MessageRepository
import com.jonoshields.gossip.core.data.GossipStore
import com.jonoshields.gossip.core.identity.FileSeedStorage
import com.jonoshields.gossip.core.identity.IdentityStore
import com.jonoshields.gossip.core.identity.KeystoreSeedCipher
import com.jonoshields.gossip.core.store.Clock
import com.jonoshields.gossip.core.store.StorageConfig
import com.jonoshields.gossip.core.sync.SyncStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * The whole object graph. The core modules know nothing about Hilt — they are plain classes
 * with constructor parameters, assembled here.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun clock(): Clock = Clock.System

    /**
     * M1 uses the default caps. Making them user-adjustable needs somewhere to persist
     * them, which is a DataStore-shaped job left for when the settings screen becomes real;
     * the defaults are deliberately ones a user never has to touch (plan.md §4).
     */
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

    /** One database for the whole app — see [GossipStore] for why that matters. */
    @Provides
    @Singleton
    fun gossipStore(
        @ApplicationContext context: Context,
        identity: IdentityStore,
        clock: Clock,
        config: StorageConfig,
    ): GossipStore = GossipStore(context, identity, clock, config)

    @Provides
    @Singleton
    fun messageRepository(store: GossipStore): MessageRepository = store.messages

    @Provides
    @Singleton
    fun directoryRepository(store: GossipStore): DirectoryRepository = store.directory

    /** What the debug-sync action in Settings drives a session against. */
    @Provides
    @Singleton
    fun syncStore(store: GossipStore): SyncStore = store.sync
}
