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

    // Stored strings are frozen at "LISTEN"/"CONTEXT"/"GOSSIP" — Tier.FOLLOW was renamed from
    // Tier.FOLLOW after rows using that name were already on disk, so this can't just be
    // tier.name/Tier.valueOf(name) without breaking every existing install.
    @TypeConverter
    fun fromTier(tier: Tier): String = when (tier) {
        Tier.FOLLOW -> "LISTEN"
        Tier.CONTEXT -> "CONTEXT"
        Tier.GOSSIP -> "GOSSIP"
    }

    @TypeConverter
    fun toTier(name: String): Tier = when (name) {
        "LISTEN" -> Tier.FOLLOW
        "CONTEXT" -> Tier.CONTEXT
        "GOSSIP" -> Tier.GOSSIP
        else -> throw IllegalArgumentException("Unknown tier: $name")
    }
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
    /** True only while this device holds a message it authored that has never left it — cleared the moment it's actually served to a peer. The one thing local deletion is allowed to touch. */
    @ColumnInfo(name = "unsent") val unsent: Boolean = false,
)

/** The projection every sync read returns — not a [MessageEntity], since a session only needs ids, not text and signatures. */
internal class HeldRow(
    val id: MessageId,
    val author: AuthorId,
    @ColumnInfo(name = "thread_root") val threadRoot: MessageId,
    @ColumnInfo(name = "effective_time") val effectiveTime: Long,
)

/** One row of the paginated thread list — the root, up to two "known" (verified/followed/self) reply previews, and small per-thread counts, computed by a `GROUP BY thread_root` query rather than stored. */
internal class ThreadSummaryRow(
    @ColumnInfo(name = "thread_root") val rootId: MessageId,
    @ColumnInfo(name = "root_author") val rootAuthor: AuthorId?,
    @ColumnInfo(name = "root_text") val rootText: String?,
    @ColumnInfo(name = "root_timestamp") val rootTimestamp: Long?,
    @ColumnInfo(name = "root_unread") val rootUnread: Boolean,
    @ColumnInfo(name = "reply_count") val replyCount: Int,
    @ColumnInfo(name = "unread_reply_count") val unreadReplyCount: Int,
    @ColumnInfo(name = "known_reply_count") val knownReplyCount: Int,
    @ColumnInfo(name = "known_unread_reply_count") val knownUnreadReplyCount: Int,
    @ColumnInfo(name = "latest_known_reply_author") val latestKnownReplyAuthor: AuthorId?,
    @ColumnInfo(name = "latest_known_reply_text") val latestKnownReplyText: String?,
    @ColumnInfo(name = "latest_known_reply_timestamp") val latestKnownReplyTimestamp: Long?,
    @ColumnInfo(name = "second_known_reply_author") val secondKnownReplyAuthor: AuthorId?,
    @ColumnInfo(name = "second_known_reply_text") val secondKnownReplyText: String?,
    @ColumnInfo(name = "second_known_reply_timestamp") val secondKnownReplyTimestamp: Long?,
    @ColumnInfo(name = "latest_known_unread_reply_author") val latestKnownUnreadReplyAuthor: AuthorId?,
    @ColumnInfo(name = "latest_known_unread_reply_text") val latestKnownUnreadReplyText: String?,
    @ColumnInfo(name = "latest_known_unread_reply_timestamp") val latestKnownUnreadReplyTimestamp: Long?,
    @ColumnInfo(name = "second_known_unread_reply_author") val secondKnownUnreadReplyAuthor: AuthorId?,
    @ColumnInfo(name = "second_known_unread_reply_text") val secondKnownUnreadReplyText: String?,
    @ColumnInfo(name = "second_known_unread_reply_timestamp") val secondKnownUnreadReplyTimestamp: Long?,
    @ColumnInfo(name = "is_favourite") val isPinned: Boolean,
)

/** Threads the user has pinned, keyed by root id so a thread survives its root message being pruned. */
@Entity(tableName = "favourite_roots")
internal class PinnedRootEntity(
    @PrimaryKey val root: MessageId,
    @ColumnInfo(name = "favourited_at") val pinnedAt: Long,
)

@Entity(tableName = "listen_list")
internal class FollowEntity(
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

/**
 * An identity you have a local row for — [nickname] is a purely cosmetic label with no security
 * meaning; [verified] is the one real trust signal, settable only by a completed live-sync confirm
 * or QR "Quick verify" scan, and is what suppresses the fingerprint next to this identity's name.
 */
@Entity(tableName = "contacts")
internal class ContactEntity(
    @PrimaryKey val author: AuthorId,
    @ColumnInfo(name = "display_name") val nickname: String?,
    @ColumnInfo(name = "added_at") val confirmedAt: Long,
    @ColumnInfo(name = "verified") val verified: Boolean = false,
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
