package com.jonoshields.gossip.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.model.MessageId
import com.jonoshields.gossip.core.store.Tier

/**
 * Ids are stored through converters rather than as raw `ByteArray` columns so the entities
 * keep the correct value equality that [MessageId] and [AuthorId] provide. A `ByteArray`
 * field would give every entity identity-based equals — the exact trap those wrappers exist
 * to close.
 */
internal class Converters {
    @TypeConverter fun fromMessageId(id: MessageId?): ByteArray? = id?.toByteArray()
    @TypeConverter fun toMessageId(bytes: ByteArray?): MessageId? = bytes?.let(MessageId::of)

    @TypeConverter fun fromAuthorId(id: AuthorId?): ByteArray? = id?.toByteArray()
    @TypeConverter fun toAuthorId(bytes: ByteArray?): AuthorId? = bytes?.let(AuthorId::of)

    @TypeConverter fun fromTier(tier: Tier): String = tier.name
    @TypeConverter fun toTier(name: String): Tier = Tier.valueOf(name)
}

/**
 * A held message. The signed fields mirror the canonical form exactly; everything after
 * [firstReceivedTime] is local-only and never transmitted (plan.md §3.3).
 *
 * [threadRoot] and [effectiveTime] are derived rather than signed, and are stored rather
 * than computed per query because they are precisely what ordering, thread assembly,
 * windowing and eviction all index on.
 */
@Entity(
    tableName = "messages",
    indices = [
        Index("thread_root"),
        Index("author"),
        Index("effective_time"),
        Index(value = ["author", "effective_time"]),
        Index(value = ["thread_root", "effective_time"]),
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

/**
 * Threads the user has starred. Keyed by root **id**, so a thread can be kept even when its
 * root message is not held — the mirror image of [BlockedRootEntity].
 */
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

/**
 * Threads whose root was written by a blocked author, remembered separately so replies keep
 * being dropped after the root message itself is gone (plan.md §4).
 */
@Entity(tableName = "blocked_roots")
internal class BlockedRootEntity(
    @PrimaryKey val root: MessageId,
    @ColumnInfo(name = "blocked_at") val blockedAt: Long,
)

/** Orphan parent-ids worth accepting from a peer. Unused until M2. */
@Entity(tableName = "want_list")
internal class WantEntity(
    @PrimaryKey val parent: MessageId,
    @ColumnInfo(name = "first_wanted_at") val firstWantedAt: Long,
    @ColumnInfo(name = "unsatisfied_syncs") val unsatisfiedSyncs: Int,
)

@Entity(tableName = "contacts")
internal class ContactEntity(
    @PrimaryKey val author: AuthorId,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)
