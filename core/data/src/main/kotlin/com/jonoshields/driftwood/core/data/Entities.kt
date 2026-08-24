package com.jonoshields.driftwood.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.MessageId
import com.jonoshields.driftwood.core.store.Tier

/** Converts ids through [MessageId]/[AuthorId] wrappers so entities keep value equality instead of `ByteArray` identity equality. */
internal class Converters {
    @TypeConverter fun fromMessageId(id: MessageId?): ByteArray? = id?.toByteArray()
    @TypeConverter fun toMessageId(bytes: ByteArray?): MessageId? = bytes?.let(MessageId::of)

    @TypeConverter fun fromAuthorId(id: AuthorId?): ByteArray? = id?.toByteArray()
    @TypeConverter fun toAuthorId(bytes: ByteArray?): AuthorId? = bytes?.let(AuthorId::of)

    @TypeConverter fun fromTier(tier: Tier): String = tier.name
    @TypeConverter fun toTier(name: String): Tier = Tier.valueOf(name)
}

/** A held message. Fields through [firstReceivedTime] mirror the canonical form; [threadRoot]/[effectiveTime] are derived and stored since ordering, assembly, windowing and eviction all index on them. */
@Entity(
    tableName = "messages",
    indices = [
        Index("thread_root"),
        Index("author"),
        Index("effective_time"),
        Index(value = ["author", "effective_time"]),
        Index(value = ["thread_root", "effective_time"]),
        // Covers the paginated thread-list query's `thread_root = ? AND <column> = ?` EXISTS checks.
        Index(value = ["thread_root", "tier"]),
        Index(value = ["thread_root", "read"]),
    ],
)
internal class MessageEntity(
    @PrimaryKey val id: MessageId,
    val version: Int,
    val author: AuthorId,
    val root: MessageId?,
    val parent: MessageId?,
    @ColumnInfo(name = "thread_root") val threadRoot: MessageId,
    @ColumnInfo(name = "timestamp_millis") val timestampMillis: Long,
    val text: String,
    val signature: ByteArray,
    @ColumnInfo(name = "first_received_time") val firstReceivedTime: Long,
    @ColumnInfo(name = "effective_time") val effectiveTime: Long,
    val read: Boolean,
    val tier: Tier,
)

/** The projection every sync read returns — not a [MessageEntity], since a session only needs ids, not text and signatures. */
internal class HeldRow(
    val id: MessageId,
    val author: AuthorId,
    @ColumnInfo(name = "thread_root") val threadRoot: MessageId,
    @ColumnInfo(name = "effective_time") val effectiveTime: Long,
)

/** One row of the paginated thread list — the root, the latest in-scope reply, and small per-thread booleans, computed by a `GROUP BY thread_root` query rather than stored. */
internal class ThreadSummaryRow(
    @ColumnInfo(name = "thread_root") val rootId: MessageId,
    @ColumnInfo(name = "root_author") val rootAuthor: AuthorId?,
    @ColumnInfo(name = "root_text") val rootText: String?,
    @ColumnInfo(name = "root_timestamp") val rootTimestamp: Long?,
    @ColumnInfo(name = "latest_listened_author") val latestListenedAuthor: AuthorId?,
    @ColumnInfo(name = "latest_listened_text") val latestListenedText: String?,
    @ColumnInfo(name = "latest_listened_timestamp") val latestListenedTimestamp: Long?,
    @ColumnInfo(name = "message_count") val messageCount: Int,
    @ColumnInfo(name = "has_unread") val hasUnread: Boolean,
    @ColumnInfo(name = "is_favourite") val isFavourite: Boolean,
)

/** Threads the user has starred, keyed by root id so a thread survives its root message being pruned. */
@Entity(tableName = "favourite_roots")
internal class FavouriteRootEntity(
    @PrimaryKey val root: MessageId,
    @ColumnInfo(name = "favourited_at") val favouritedAt: Long,
)

@Entity(tableName = "listen_list")
internal class ListenEntity(
    @PrimaryKey val author: AuthorId,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

@Entity(tableName = "blocklist")
internal class BlockedAuthorEntity(
    @PrimaryKey val author: AuthorId,
    @ColumnInfo(name = "blocked_at") val blockedAt: Long,
)

/** Threads whose root was written by a blocked author, remembered separately so replies keep being dropped after the root itself is gone. */
@Entity(tableName = "blocked_roots")
internal class BlockedRootEntity(
    @PrimaryKey val root: MessageId,
    @ColumnInfo(name = "blocked_at") val blockedAt: Long,
)

/** A parent id we're missing, tracked across syncs so a peer can be asked for it. */
@Entity(tableName = "want_list")
internal class WantEntity(
    @PrimaryKey val parent: MessageId,
    @ColumnInfo(name = "first_wanted_at") val firstWantedAt: Long,
    @ColumnInfo(name = "unsatisfied_syncs") val unsatisfiedSyncs: Int,
)

/** An identity you've confirmed — presence in this table *is* confirmation; [nickname] is just an optional label on top. */
@Entity(tableName = "contacts")
internal class ContactEntity(
    @PrimaryKey val author: AuthorId,
    @ColumnInfo(name = "display_name") val nickname: String?,
    @ColumnInfo(name = "added_at") val confirmedAt: Long,
)

/** A claimed username heard for an identity — a cache of other people's claims, never authority; [lastSeenPost] drives ageing. */
@Entity(tableName = "directory")
internal class DirectoryEntity(
    @PrimaryKey val author: AuthorId,
    @ColumnInfo(name = "nickname") val username: String,
    @ColumnInfo(name = "claimed_at") val claimedAt: Long,
    @ColumnInfo(name = "first_received") val firstReceived: Long,
    @ColumnInfo(name = "last_seen_post") val lastSeenPost: Long,
    /** The signed record itself, kept so it can be relayed onward unchanged. */
    val record: ByteArray,
)
