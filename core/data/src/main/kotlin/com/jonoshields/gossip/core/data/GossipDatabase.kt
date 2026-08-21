package com.jonoshields.gossip.core.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.model.MessageId
import com.jonoshields.gossip.core.store.Tier
import kotlinx.coroutines.flow.Flow

@Dao
internal interface MessageDao {

    /**
     * Content-addressing gives dedup for free: the id is the primary key, so re-ingesting a
     * message you already hold is a no-op rather than a conflict to resolve.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages ORDER BY effective_time DESC, id ASC")
    fun observeAll(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE thread_root = :threadRoot ORDER BY effective_time ASC, id ASC")
    fun observeThread(threadRoot: MessageId): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages")
    suspend fun all(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun find(id: MessageId): MessageEntity?

    @Query("SELECT DISTINCT thread_root FROM messages WHERE author = :author AND root IS NULL")
    suspend fun rootsAuthoredBy(author: AuthorId): List<MessageId>

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun delete(ids: List<MessageId>)

    @Query("UPDATE messages SET read = :read WHERE id = :id")
    suspend fun setRead(id: MessageId, read: Boolean)

    @Query("UPDATE messages SET tier = :tier WHERE id = :id")
    suspend fun setTier(id: MessageId, tier: Tier)
}

@Dao
internal interface ListenDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(entry: ListenEntity)

    @Query("DELETE FROM listen_list WHERE author = :author")
    suspend fun remove(author: AuthorId)

    @Query("SELECT author FROM listen_list")
    suspend fun authors(): List<AuthorId>

    @Query("SELECT author FROM listen_list")
    fun observeAuthors(): Flow<List<AuthorId>>
}

@Dao
internal interface BlocklistDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun blockAuthor(entry: BlockedAuthorEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun blockRoots(entries: List<BlockedRootEntity>)

    @Query("DELETE FROM blocklist WHERE author = :author")
    suspend fun unblockAuthor(author: AuthorId)

    @Query("SELECT author FROM blocklist")
    suspend fun blockedAuthors(): List<AuthorId>

    @Query("SELECT root FROM blocked_roots")
    suspend fun blockedRoots(): List<MessageId>
}

@Dao
internal interface FavouriteDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun star(entry: FavouriteRootEntity)

    @Query("DELETE FROM favourite_roots WHERE root = :root")
    suspend fun unstar(root: MessageId)

    @Query("SELECT root FROM favourite_roots")
    suspend fun starredRoots(): List<MessageId>

    @Query("SELECT EXISTS(SELECT 1 FROM favourite_roots WHERE root = :root)")
    fun observeIsStarred(root: MessageId): Flow<Boolean>

    @Query("SELECT root FROM favourite_roots")
    fun observeStarredRoots(): Flow<List<MessageId>>
}

@Dao
internal interface DirectoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: DirectoryEntity)

    @Query("SELECT * FROM directory WHERE author = :author")
    suspend fun find(author: AuthorId): DirectoryEntity?

    @Query("SELECT * FROM directory")
    suspend fun all(): List<DirectoryEntity>

    @Query("SELECT * FROM directory")
    fun observeAll(): Flow<List<DirectoryEntity>>

    @Query("DELETE FROM directory WHERE author IN (:authors)")
    suspend fun delete(authors: List<AuthorId>)

    @Query("UPDATE directory SET last_seen_post = :at WHERE author = :author AND last_seen_post < :at")
    suspend fun touch(author: AuthorId, at: Long)

    @Query("SELECT DISTINCT author FROM messages")
    suspend fun authorsWithMessages(): List<AuthorId>
}

@Dao
internal interface ContactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity)

    @Query("SELECT * FROM contacts")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT author FROM contacts")
    suspend fun authors(): List<AuthorId>
}

@Database(
    entities = [
        MessageEntity::class,
        ListenEntity::class,
        BlockedAuthorEntity::class,
        BlockedRootEntity::class,
        FavouriteRootEntity::class,
        DirectoryEntity::class,
        WantEntity::class,
        ContactEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
internal abstract class GossipDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun listen(): ListenDao
    abstract fun blocklist(): BlocklistDao
    abstract fun favourites(): FavouriteDao
    abstract fun directory(): DirectoryDao
    abstract fun contacts(): ContactDao
}

internal fun buildGossipDatabase(context: Context, name: String = "gossip.db"): GossipDatabase =
    Room.databaseBuilder(context, GossipDatabase::class.java, name).build()
