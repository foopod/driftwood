package com.jonoshields.gossip.core.data

import android.database.SQLException
import com.jonoshields.gossip.core.identity.IdentityStore
import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.model.Profile
import com.jonoshields.gossip.core.model.ProfileCodec
import com.jonoshields.gossip.core.model.ProfileVerifyResult
import com.jonoshields.gossip.core.store.Clock
import com.jonoshields.gossip.core.store.DirectoryEntry
import com.jonoshields.gossip.core.store.DirectoryPruner
import com.jonoshields.gossip.core.store.DisplayName
import com.jonoshields.gossip.core.store.NameResolver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Names: the ones people claim for themselves, and the ones you have assigned.
 *
 * Separate from [MessageRepository] because a name is not content. Losing this whole store
 * costs readability and nothing else — no message, thread or signature depends on it.
 */
interface DirectoryRepository {

    /** Sets *your* nickname: signs a fresh claim and records it as your own directory row. */
    suspend fun setMyNickname(nickname: String): Result<Profile>

    suspend fun myProfile(): Result<Profile?>

    /**
     * Ingests a claim received from a peer. Rejected outright if it does not verify, and
     * ignored if it is older than the claim already held for that key.
     */
    suspend fun ingest(record: ByteArray, receivedAtMillis: Long): Result<Profile?>

    /** Every name currently known, for resolving a screenful of authors at once. */
    fun observeNames(): Flow<Map<AuthorId, DisplayName>>

    /** Ages names out per plan.md §4. Returns the identities whose names were dropped. */
    suspend fun prune(): Result<Set<AuthorId>>
}

class RoomDirectoryRepository internal constructor(
    private val database: GossipDatabase,
    private val identity: IdentityStore,
    private val clock: Clock,
) : DirectoryRepository {

    private val directory get() = database.directory()

    override suspend fun setMyNickname(nickname: String): Result<Profile> = runCatching {
        val me = try {
            identity.publicKey()
        } catch (e: IllegalStateException) {
            throw DataError.NoIdentity()
        }
        val now = clock.nowMillis()
        val profile = try {
            ProfileCodec.create(me, nickname, now, identity.signer())
        } catch (e: IllegalArgumentException) {
            throw DataError.InvalidMessage(e)
        }
        directory.upsert(
            DirectoryEntity(
                author = me,
                nickname = profile.nickname,
                claimedAt = profile.timestampMillis,
                firstReceived = now,
                lastSeenPost = now,
                record = ProfileCodec.encode(profile),
            )
        )
        profile
    }.mapDirectoryErrors()

    override suspend fun myProfile(): Result<Profile?> = runCatching {
        val me = runCatching { identity.publicKey() }.getOrNull() ?: return@runCatching null
        directory.find(me)?.toProfile()
    }.mapDirectoryErrors()

    override suspend fun ingest(record: ByteArray, receivedAtMillis: Long): Result<Profile?> =
        runCatching {
            val profile = when (val verified = ProfileCodec.verify(record)) {
                is ProfileVerifyResult.Rejected -> return@runCatching null
                is ProfileVerifyResult.Valid -> verified.profile
            }

            // Latest claim wins, but a claimed time is only as good as when it arrived —
            // forward-dating must not let someone pin a name permanently, the same reason
            // effective_time exists for messages (plan.md §4).
            val effective = minOf(profile.timestampMillis, receivedAtMillis)
            val existing = directory.find(profile.author)
            if (existing != null && existing.claimedAt >= effective) return@runCatching null

            directory.upsert(
                DirectoryEntity(
                    author = profile.author,
                    nickname = profile.nickname,
                    claimedAt = effective,
                    firstReceived = existing?.firstReceived ?: receivedAtMillis,
                    lastSeenPost = existing?.lastSeenPost ?: receivedAtMillis,
                    record = record.copyOf(),
                )
            )
            profile
        }.mapDirectoryErrors()

    override fun observeNames(): Flow<Map<AuthorId, DisplayName>> =
        combine(
            directory.observeAll(),
            database.contacts().observeAll(),
        ) { claims, contacts ->
            val me = runCatching { identity.publicKey() }.getOrNull()
            val petnames = contacts.associate { it.author to it.displayName }
            val claimed = claims.associate { it.author to it.nickname }

            (petnames.keys + claimed.keys).associateWith { author ->
                // Your own name needs no fingerprint and no colour: you are not trying to
                // work out whether you are really you. Treating it as a petname is honest
                // rather than a special case — you did assign that name to that key.
                val petname = if (author == me) claimed[author] ?: petnames[author] else petnames[author]
                NameResolver.resolve(author, petname, claimed[author])
            }
        }

    override suspend fun prune(): Result<Set<AuthorId>> = runCatching {
        val drop = DirectoryPruner.plan(
            entries = directory.all().map {
                DirectoryEntry(it.author, it.nickname, it.lastSeenPost)
            },
            listen = database.listen().authors().toSet(),
            contacts = database.contacts().authors().toSet(),
            authorsHeld = directory.authorsWithMessages().toSet(),
            blockedAuthors = database.blocklist().blockedAuthors().toSet(),
            nowMillis = clock.nowMillis(),
        )
        chunkedAction(drop) { directory.deleteChunk(it) }
        drop
    }.mapDirectoryErrors()
}

private fun DirectoryEntity.toProfile(): Profile? =
    (ProfileCodec.verify(record) as? ProfileVerifyResult.Valid)?.profile

private fun <T> Result<T>.mapDirectoryErrors(): Result<T> = recoverCatching { cause ->
    throw when (cause) {
        is DataError -> cause
        is SQLException -> DataError.Local(cause)
        else -> cause
    }
}
