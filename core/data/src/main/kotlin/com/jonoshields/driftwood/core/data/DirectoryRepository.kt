package com.jonoshields.driftwood.core.data

import android.database.SQLException
import com.jonoshields.driftwood.core.identity.IdentityStore
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.Username
import com.jonoshields.driftwood.core.model.Profile
import com.jonoshields.driftwood.core.model.ProfileCodec
import com.jonoshields.driftwood.core.model.ProfileVerifyResult
import com.jonoshields.driftwood.core.store.Clock
import com.jonoshields.driftwood.core.store.DirectoryEntry
import com.jonoshields.driftwood.core.store.DirectoryPruner
import com.jonoshields.driftwood.core.store.DisplayName
import com.jonoshields.driftwood.core.store.NameResolver
import com.jonoshields.driftwood.core.store.TierClassifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Names: the ones people claim for themselves, and the ones you have assigned. Separate from [MessageRepository] since a name is not content. */
interface DirectoryRepository {

    /** Sets *your* username: signs a fresh claim and records it as your own directory row. */
    suspend fun setMyUsername(username: String): Result<Profile>

    suspend fun myProfile(): Result<Profile?>

    /** Every name currently known, for resolving a screenful of authors at once. */
    fun observeNames(): Flow<Map<AuthorId, DisplayName>>

    /** Marks [author] verified; a no-op if already verified, and never overwrites an existing nickname. Only ever called from a completed live-sync confirm or QR "Quick verify" scan. */
    suspend fun verify(author: AuthorId): Result<Unit>

    /** Everyone verified — the only real trust signal, and the only thing that suppresses a fingerprint. */
    fun observeVerifiedAuthors(): Flow<Set<AuthorId>>

    /** Everyone with a claimed username in the directory cache, for the Contacts list's scope. */
    fun observeClaimedAuthors(): Flow<Set<AuthorId>>

    /** Your local name for [author] — purely cosmetic, no security meaning; never sets or clears [verify]. */
    suspend fun setNickname(author: AuthorId, nickname: String): Result<Unit>

    /** Identities you currently follow. */
    fun observeFollowList(): Flow<Set<AuthorId>>

    /** Also re-tiers everything already held, so existing threads jump between follow and gossip tier immediately rather than waiting for the next sync. */
    suspend fun follow(author: AuthorId): Result<Unit>

    /** Also re-tiers everything already held, same as [follow]. */
    suspend fun unfollow(author: AuthorId): Result<Unit>

    /** Ages names out. Returns the identities whose names were dropped. */
    suspend fun prune(): Result<Set<AuthorId>>
}

class RoomDirectoryRepository internal constructor(
    private val database: DriftwoodDatabase,
    private val identity: IdentityStore,
    private val clock: Clock,
) : DirectoryRepository {

    private val directory get() = database.directory()

    override suspend fun setMyUsername(username: String): Result<Profile> = runCatching {
        val me = try {
            identity.publicKey()
        } catch (e: IllegalStateException) {
            throw DataError.NoIdentity()
        }
        val now = clock.nowMillis()
        val profile = try {
            ProfileCodec.create(me, username, now, identity.signer())
        } catch (e: IllegalArgumentException) {
            throw DataError.InvalidMessage(e)
        }
        directory.upsert(
            DirectoryEntity(
                author = me,
                username = profile.username,
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

    override fun observeNames(): Flow<Map<AuthorId, DisplayName>> =
        combine(
            directory.observeAll(),
            database.contacts().observeAll(),
        ) { claims, contacts ->
            val me = runCatching { identity.publicKey() }.getOrNull()
            val verified = contacts.filter { it.verified }.mapTo(mutableSetOf()) { it.author }
            val nicknames = contacts.associate { it.author to it.nickname }
            val usernames = claims.associate { it.author to it.username }

            (verified + nicknames.keys + usernames.keys).associateWith { author ->
                // Your own name is treated as a nickname: no fingerprint, no colour.
                val nickname = if (author == me) usernames[author] ?: nicknames[author] else nicknames[author]
                NameResolver.resolve(author, nickname, usernames[author], confirmed = author in verified || author == me)
            }
        }

    override suspend fun verify(author: AuthorId): Result<Unit> = runCatching {
        database.contacts().insertIfAbsent(ContactEntity(author, nickname = null, confirmedAt = clock.nowMillis()))
        database.contacts().markVerified(author)
    }.mapDirectoryErrors()

    override fun observeVerifiedAuthors(): Flow<Set<AuthorId>> =
        database.contacts().observeAll().map { contacts -> contacts.filter { it.verified }.mapTo(mutableSetOf()) { it.author } }

    override fun observeClaimedAuthors(): Flow<Set<AuthorId>> =
        directory.observeAll().map { claims -> claims.mapTo(mutableSetOf()) { it.author } }

    override suspend fun setNickname(author: AuthorId, nickname: String): Result<Unit> = runCatching {
        val validated = try {
            Username.validate(nickname).getOrThrow()
        } catch (e: Throwable) {
            throw DataError.InvalidMessage(e)
        }
        database.contacts().insertIfAbsent(ContactEntity(author, nickname = null, confirmedAt = clock.nowMillis()))
        database.contacts().updateNickname(author, validated, clock.nowMillis())
    }.mapDirectoryErrors()

    override fun observeFollowList(): Flow<Set<AuthorId>> =
        database.follow().observeAuthors().map { it.toSet() }

    override suspend fun follow(author: AuthorId): Result<Unit> = runCatching {
        database.follow().add(FollowEntity(author, clock.nowMillis()))
        retierHeldMessages()
    }.mapDirectoryErrors()

    override suspend fun unfollow(author: AuthorId): Result<Unit> = runCatching {
        database.follow().remove(author)
        retierHeldMessages()
    }.mapDirectoryErrors()

    /** Recomputes every held message's tier against the current follow scope, same classification the prune pass uses. */
    private suspend fun retierHeldMessages() {
        val follow = database.follow().authors().toSet()
        val held = database.messages().all().map { it.toHeldMessage() }
        TierClassifier.classify(held, follow).forEach { (id, tier) -> database.messages().setTier(id, tier) }
    }

    override suspend fun prune(): Result<Set<AuthorId>> = runCatching {
        val drop = DirectoryPruner.plan(
            entries = directory.all().map {
                DirectoryEntry(it.author, it.username, it.lastSeenPost)
            },
            follow = database.follow().authors().toSet(),
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
