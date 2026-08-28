package com.jonoshields.driftwood.core.data

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.MessageId
import com.jonoshields.driftwood.core.store.Tier
import kotlinx.coroutines.flow.Flow

@Dao
internal interface MessageDao {

    /** The id is the primary key, so re-ingesting an already-held message is a no-op, not a conflict. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages ORDER BY effective_time DESC, id ASC")
    fun observeAll(): Flow<List<MessageEntity>>

    /** A single indexed existence check behind the first-run empty state, not a full-table load. */
    @Query("SELECT EXISTS(SELECT 1 FROM messages LIMIT 1)")
    fun observeHasAnyMessage(): Flow<Boolean>

    @Query("SELECT * FROM messages WHERE thread_root = :threadRoot ORDER BY effective_time ASC, id ASC")
    fun observeThread(threadRoot: MessageId): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages")
    suspend fun all(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun find(id: MessageId): MessageEntity?

    @Query("SELECT DISTINCT thread_root FROM messages WHERE author = :author AND root IS NULL")
    suspend fun rootsAuthoredBy(author: AuthorId): List<MessageId>

    /** Chunk-sized only — call through [chunkedAction]. */
    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteChunk(ids: List<MessageId>)

    // ---- sync reads: metadata only, so a session can decide what to send without loading text.

    @Query("SELECT id, author, thread_root, effective_time FROM messages WHERE author IN (:authors)")
    suspend fun heldByChunk(authors: List<AuthorId>): List<HeldRow>

    @Query(
        "SELECT id, author, thread_root, effective_time FROM messages " +
            "WHERE author IN (:authors) AND effective_time >= :since"
    )
    suspend fun heldBySinceChunk(authors: List<AuthorId>, since: Long): List<HeldRow>

    @Query(
        "SELECT id, author, thread_root, effective_time FROM messages " +
            "WHERE thread_root IN (:roots) AND effective_time >= :since"
    )
    suspend fun heldInThreadsChunk(roots: List<MessageId>, since: Long): List<HeldRow>

    @Query("SELECT id, author, thread_root, effective_time FROM messages WHERE id IN (:ids)")
    suspend fun heldWithIdsChunk(ids: List<MessageId>): List<HeldRow>

    /** A page of the newest held, for building a gossip offer — paged rather than filtered since the exclusion set can be huge. */
    @Query(
        "SELECT id, author, thread_root, effective_time FROM messages " +
            "ORDER BY effective_time DESC, id ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun newestPage(limit: Int, offset: Int): List<HeldRow>

    @Query("SELECT * FROM messages WHERE id IN (:ids)")
    suspend fun findChunk(ids: List<MessageId>): List<MessageEntity>

    @Query("UPDATE messages SET read = :read WHERE id = :id")
    suspend fun setRead(id: MessageId, read: Boolean)

    @Query("UPDATE messages SET read = 1 WHERE thread_root = :rootId")
    suspend fun markThreadRead(rootId: MessageId)

    @Query("UPDATE messages SET tier = :tier WHERE id = :id")
    suspend fun setTier(id: MessageId, tier: Tier)

    // ---- the paginated thread list, one row per thread.
    // "in scope" (tab membership) means tier = :followTier OR author = :myAuthor.
    // "known" (naming/preview eligibility) is broader: in scope, OR a verified contact.
    @Query(
        """
        SELECT
            m.thread_root AS thread_root,
            root.author AS root_author,
            root.text AS root_text,
            root.effective_time AS root_timestamp,
            COALESCE(root.read = 0, 0) AS root_unread,
            (SELECT COUNT(*) FROM messages c WHERE c.thread_root = m.thread_root AND c.id != m.thread_root) AS reply_count,
            (SELECT COUNT(*) FROM messages u WHERE u.thread_root = m.thread_root AND u.id != m.thread_root AND u.read = 0) AS unread_reply_count,
            (SELECT COUNT(DISTINCT r.author) FROM messages r
                WHERE r.thread_root = m.thread_root AND r.id != m.thread_root
                  AND (r.tier = :followTier OR r.author = :myAuthor
                       OR EXISTS(SELECT 1 FROM contacts kc WHERE kc.author = r.author AND kc.verified = 1))
            ) AS known_reply_count,
            (SELECT COUNT(DISTINCT r.author) FROM messages r
                WHERE r.thread_root = m.thread_root AND r.id != m.thread_root AND r.read = 0
                  AND (r.tier = :followTier OR r.author = :myAuthor
                       OR EXISTS(SELECT 1 FROM contacts kc WHERE kc.author = r.author AND kc.verified = 1))
            ) AS known_unread_reply_count,
            reply.author AS latest_known_reply_author,
            reply.text AS latest_known_reply_text,
            reply.effective_time AS latest_known_reply_timestamp,
            reply2.author AS second_known_reply_author,
            reply2.text AS second_known_reply_text,
            reply2.effective_time AS second_known_reply_timestamp,
            unread_reply.author AS latest_known_unread_reply_author,
            unread_reply.text AS latest_known_unread_reply_text,
            unread_reply.effective_time AS latest_known_unread_reply_timestamp,
            unread_reply2.author AS second_known_unread_reply_author,
            unread_reply2.text AS second_known_unread_reply_text,
            unread_reply2.effective_time AS second_known_unread_reply_timestamp,
            EXISTS(SELECT 1 FROM favourite_roots f WHERE f.root = m.thread_root) AS is_favourite
        FROM messages m
        LEFT JOIN messages root ON root.id = m.thread_root
        LEFT JOIN messages reply ON reply.id = (
            -- Greatest-n-per-group: the single newest known, non-root message in this thread.
            SELECT r2.id FROM messages r2
            WHERE r2.thread_root = m.thread_root
              AND r2.id != m.thread_root
              AND (r2.tier = :followTier OR r2.author = :myAuthor
                   OR EXISTS(SELECT 1 FROM contacts kc WHERE kc.author = r2.author AND kc.verified = 1))
            ORDER BY r2.effective_time DESC, r2.id ASC
            LIMIT 1
        )
        LEFT JOIN messages reply2 ON reply2.id = (
            -- The next-newest known reply from a *different* author than [reply] — feeds both the
            -- second snippet card (small threads) and the second named author (busy threads).
            SELECT r3.id FROM messages r3
            WHERE r3.thread_root = m.thread_root AND r3.id != m.thread_root
              AND r3.author != reply.author
              AND (r3.tier = :followTier OR r3.author = :myAuthor
                   OR EXISTS(SELECT 1 FROM contacts kc WHERE kc.author = r3.author AND kc.verified = 1))
            ORDER BY r3.effective_time DESC, r3.id ASC
            LIMIT 1
        )
        LEFT JOIN messages unread_reply ON unread_reply.id = (
            -- Same, restricted to unread — feeds the "since you last opened this" preview.
            SELECT r5.id FROM messages r5
            WHERE r5.thread_root = m.thread_root
              AND r5.id != m.thread_root
              AND r5.read = 0
              AND (r5.tier = :followTier OR r5.author = :myAuthor
                   OR EXISTS(SELECT 1 FROM contacts kc WHERE kc.author = r5.author AND kc.verified = 1))
            ORDER BY r5.effective_time DESC, r5.id ASC
            LIMIT 1
        )
        LEFT JOIN messages unread_reply2 ON unread_reply2.id = (
            SELECT r4.id FROM messages r4
            WHERE r4.thread_root = m.thread_root AND r4.id != m.thread_root AND r4.read = 0
              AND r4.author != unread_reply.author
              AND (r4.tier = :followTier OR r4.author = :myAuthor
                   OR EXISTS(SELECT 1 FROM contacts kc WHERE kc.author = r4.author AND kc.verified = 1))
            ORDER BY r4.effective_time DESC, r4.id ASC
            LIMIT 1
        )
        WHERE EXISTS(
            SELECT 1 FROM messages s
            WHERE s.thread_root = m.thread_root AND (s.tier = :followTier OR s.author = :myAuthor)
        ) = :wantFollowing
        AND (:unreadOnly = 0 OR EXISTS(
            SELECT 1 FROM messages u2 WHERE u2.thread_root = m.thread_root AND u2.read = 0
        ))
        AND (:authorFilter IS NULL OR EXISTS(
            SELECT 1 FROM messages af WHERE af.thread_root = m.thread_root AND af.author = :authorFilter
        ))
        AND (:textQuery IS NULL OR EXISTS(
            SELECT 1 FROM messages tq WHERE tq.thread_root = m.thread_root AND tq.text LIKE '%' || :textQuery || '%'
        ))
        GROUP BY m.thread_root
        ORDER BY
            -- A root-text match beats a reply-only match, then recency (no real FTS ranking).
            (CASE WHEN :textQuery IS NOT NULL AND root.text LIKE '%' || :textQuery || '%' THEN 0 ELSE 1 END),
            MAX(m.effective_time) DESC,
            m.thread_root ASC
        """
    )
    fun pagedThreads(
        myAuthor: AuthorId?,
        wantFollowing: Boolean,
        unreadOnly: Boolean,
        authorFilter: AuthorId?,
        textQuery: String?,
        followTier: Tier = Tier.FOLLOW,
    ): PagingSource<Int, ThreadSummaryRow>
}

@Dao
internal interface FollowDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(entry: FollowEntity)

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

    @Query("SELECT author FROM blocklist")
    fun observeBlockedAuthors(): Flow<List<AuthorId>>

    @Query("SELECT root FROM blocked_roots")
    suspend fun blockedRoots(): List<MessageId>
}

@Dao
internal interface PinDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun pin(entry: PinnedRootEntity)

    @Query("DELETE FROM favourite_roots WHERE root = :root")
    suspend fun unpin(root: MessageId)

    @Query("SELECT root FROM favourite_roots")
    suspend fun pinnedRoots(): List<MessageId>

    @Query("SELECT EXISTS(SELECT 1 FROM favourite_roots WHERE root = :root)")
    fun observeIsPinned(root: MessageId): Flow<Boolean>
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

    @Query("SELECT * FROM directory WHERE author IN (:authors)")
    suspend fun findChunk(authors: List<AuthorId>): List<DirectoryEntity>

    /** Chunk-sized only. */
    @Query("DELETE FROM directory WHERE author IN (:authors)")
    suspend fun deleteChunk(authors: List<AuthorId>)

    @Query("UPDATE directory SET last_seen_post = :at WHERE author = :author AND last_seen_post < :at")
    suspend fun touch(author: AuthorId, at: Long)

    @Query("SELECT DISTINCT author FROM messages")
    suspend fun authorsWithMessages(): List<AuthorId>
}

@Dao
internal interface ContactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity)

    /** Creates the row if absent; unlike [upsert], never clobbers an existing nickname or verified flag. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(contact: ContactEntity)

    @Query("SELECT * FROM contacts")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT author FROM contacts")
    suspend fun authors(): List<AuthorId>

    /** Only settable by a completed live-sync confirm or QR verify scan — never as a side effect of naming someone. */
    @Query("UPDATE contacts SET verified = 1 WHERE author = :author")
    suspend fun markVerified(author: AuthorId)

    /** Partial-column update so setting a nickname never clobbers [ContactEntity.verified] back to false. */
    @Query("UPDATE contacts SET display_name = :nickname, added_at = :at WHERE author = :author")
    suspend fun updateNickname(author: AuthorId, nickname: String?, at: Long)
}

/** The want-list: parent ids we noticed missing, opportunistically accepted from anyone until they age out after [WANT_TTL] fruitless syncs. */
@Dao
internal interface WantDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(entries: List<WantEntity>)

    @Query("SELECT parent FROM want_list")
    suspend fun all(): List<MessageId>

    /** Chunk-sized only. */
    @Query("DELETE FROM want_list WHERE parent IN (:ids)")
    suspend fun removeChunk(ids: List<MessageId>)

    @Query("UPDATE want_list SET unsatisfied_syncs = unsatisfied_syncs + 1")
    suspend fun ageAll()

    @Query("DELETE FROM want_list WHERE unsatisfied_syncs >= :ttl")
    suspend fun dropExpired(ttl: Int)
}

@Database(
    entities = [
        MessageEntity::class,
        FollowEntity::class,
        BlockedAuthorEntity::class,
        BlockedRootEntity::class,
        PinnedRootEntity::class,
        DirectoryEntity::class,
        WantEntity::class,
        ContactEntity::class,
    ],
    // Bump this and add a Migration(N, N+1) to Migrations.kt for every schema change — see that file.
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
internal abstract class DriftwoodDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun follow(): FollowDao
    abstract fun blocklist(): BlocklistDao
    abstract fun pins(): PinDao
    abstract fun directory(): DirectoryDao
    abstract fun contacts(): ContactDao
    abstract fun wants(): WantDao
}

internal fun buildDriftwoodDatabase(context: Context, name: String = "driftwood.db"): DriftwoodDatabase =
    Room.databaseBuilder(context, DriftwoodDatabase::class.java, name)
        // Every version bump ships a real Migration in Migrations.kt. No destructive fallback,
        // and deliberately no fallbackToDestructiveMigrationOnDowngrade(): a missing/forgotten
        // migration should crash loudly in testing, not silently wipe a real user's messages.
        .addMigrations(*DRIFTWOOD_MIGRATIONS)
        .build()
