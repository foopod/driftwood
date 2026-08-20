package com.jonoshields.gossip.di

import android.content.Context
import com.jonoshields.gossip.core.data.MessageRepository
import com.jonoshields.gossip.core.data.RoomMessageRepository
import com.jonoshields.gossip.core.identity.FileSeedStorage
import com.jonoshields.gossip.core.identity.IdentityStore
import com.jonoshields.gossip.core.identity.KeystoreSeedCipher
import com.jonoshields.gossip.core.store.Clock
import com.jonoshields.gossip.core.store.StorageConfig
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

    @Provides
    @Singleton
    fun messageRepository(
        @ApplicationContext context: Context,
        identity: IdentityStore,
        clock: Clock,
        config: StorageConfig,
    ): MessageRepository = RoomMessageRepository(context, identity, clock, config)
}
