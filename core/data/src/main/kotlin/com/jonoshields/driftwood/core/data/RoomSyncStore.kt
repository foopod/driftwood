package com.jonoshields.driftwood.core.data

import androidx.room.withTransaction
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.EffectiveTime
import com.jonoshields.driftwood.core.model.Message
import com.jonoshields.driftwood.core.model.MessageCodec
import com.jonoshields.driftwood.core.model.MessageId
import com.jonoshields.driftwood.core.model.ProfileCodec
import com.jonoshields.driftwood.core.store.Blocklist
import com.jonoshields.driftwood.core.store.Clock
import com.jonoshields.driftwood.core.store.Favourites
import com.jonoshields.driftwood.core.store.HeldMessage
import com.jonoshields.driftwood.core.store.Pruner
import com.jonoshields.driftwood.core.store.StorageConfig
import com.jonoshields.driftwood.core.store.TierClassifier
import com.jonoshields.driftwood.core.sync.PhaseOutcome
import com.jonoshields.driftwood.core.sync.SyncStore
import com.jonoshields.driftwood.core.sync.WANT_TTL

/** The real [SyncStore], over SQLite — reads return metadata not content, and every `IN (:ids)` is chunked to stay under SQLite's variable limit. */
internal class RoomSyncStore(
    private val database: DriftwoodDatabase,
    private val clock: Clock,
    private val config: StorageConfig,
) : SyncStore {

    private val messages get() = database.messages()
    private val wantList get() = database.wants()

    // ---- what we declare to a peer ---------------------------------------------------

    override suspend fun listenScope(): Set<AuthorId> = database.listen().authors().toSet()

    override suspend fun windowCutoff(nowMillis: Long): Long = nowMillis - config.windowMillis

    override suspend fun wants(): Set<MessageId> = wantList.all().toSet()

    // ---- what we hold ----------------------------------------------------------------

    override suspend fun heldBy(authors: Set<AuthorId>, since: Long?): List<HeldMessage> =
        chunked(authors) { batch ->
            if (since == null) messages.heldByChunk(batch) else messages.heldBySinceChunk(batch, since)
        }.map { it.toHeldMessage() }

    override suspend fun heldInThreads(roots: Set<MessageId>, since: Long): List<HeldMessage> =
        chunked(roots) { messages.heldInThreadsChunk(it, since) }.map { it.toHeldMessage() }

    override suspend fun heldWithIds(ids: Set<MessageId>): List<HeldMessage> =
        chunked(ids) { messages.heldWithIdsChunk(it) }.map { it.toHeldMessage() }

    override suspend fun newestHeld(limit: Int, excluding: Set<MessageId>): List<HeldMessage> {
        // Paged and filtered here rather than `NOT IN`, since chunking can't express that clause.
        val out = mutableListOf<HeldMessage>()
        var offset = 0
        while (out.size < limit) {
            val page = messages.newestPage(NEWEST_PAGE, offset)
            if (page.isEmpty()) break
            offset += page.size
            for (row in page) {
                if (row.id in excluding) continue
                out += row.toHeldMessage()
                if (out.size == limit) break
            }
        }
        return out
    }

    // ---- content ---------------------------------------------------------------------

    override suspend fun readMessages(ids: List<MessageId>): List<ByteArray> {
        val found = chunked(ids.toSet()) { messages.findChunk(it) }.associateBy { it.id }
        // Re-encoded from stored fields; strict decoding guarantees this reproduces the exact preimage.
        return ids.mapNotNull { found[it] }.map { MessageCodec.encode(it.toMessage()) }
    }

    override suspend fun readProfiles(authors: Set<AuthorId>): List<ByteArray> =
        chunked(authors) { database.directory().findChunk(it) }.map { it.record }

    // ---- local policy ----------------------------------------------------------------

    override suspend fun blocklist(): Blocklist = Blocklist(
        authors = database.blocklist().blockedAuthors().toSet(),
        roots = database.blocklist().blockedRoots().toSet(),
    )

    // ---- writing ---------------------------------------------------------------------

    override suspend fun apply(outcome: PhaseOutcome, receivedAtMillis: Long) {
        if (outcome.isEmpty) return

        // One transaction per batch, so a half-applied batch never leaves the want-list stale.
        database.withTransaction {
            val listen = database.listen().authors().toSet()

            outcome.accepted.forEach { message ->
                // effective_time clamps a forward-dated message to when it actually reached us.
                val held = message.toHeldMessage(receivedAtMillis)
                val tier = TierClassifier.classify(listOf(held), listen).getValue(message.id)
                // Arrived via sync just now, so it's unread regardless of who wrote it.
                messages.insert(message.toEntity(receivedAtMillis, tier, read = false))
                database.directory().touch(message.body.author, held.effectiveTime)
            }

            outcome.profiles.forEach { profile ->
                database.directory().upsert(profile.toDirectoryEntity(receivedAtMillis))
            }

            updateWants(outcome, receivedAtMillis)
        }
    }

    /** Satisfied wants are removed and newly orphaned parents added; ageing happens separately in [finishPhase]. */
    private suspend fun updateWants(outcome: PhaseOutcome, nowMillis: Long) {
        val arrived = outcome.accepted.mapTo(mutableSetOf()) { it.id }
        chunkedAction(arrived) { wantList.removeChunk(it) }

        val orphans = outcome.accepted
            .mapNotNull { it.body.parent }
            .filterNot { it in arrived }
            .distinct()
            .filter { messages.find(it) == null }
        wantList.add(orphans.map { WantEntity(it, nowMillis, unsatisfiedSyncs = 0) })
    }

    /** Ages every outstanding want by one fruitless sync and drops what hit [WANT_TTL]; called once per phase, not per batch. */
    override suspend fun finishPhase(nowMillis: Long) {
        wantList.ageAll()
        wantList.dropExpired(WANT_TTL)
    }

    override suspend fun pruneAfterSession(nowMillis: Long) {
        val plan = Pruner.plan(
            held = messages.all().map { it.toHeldMessage() },
            listen = database.listen().authors().toSet(),
            blocklist = blocklist(),
            favourites = Favourites(database.favourites().starredRoots().toSet()),
            budgets = config.budgets(),
            windowMillis = config.windowMillis,
            nowMillis = nowMillis,
        )
        database.withTransaction {
            chunkedAction(plan.evict) { messages.deleteChunk(it) }
            plan.tiers.forEach { (id, tier) -> if (id !in plan.evict) messages.setTier(id, tier) }
        }
    }

    private companion object {
        /** Rows per page when scanning newest-first for a gossip offer. */
        const val NEWEST_PAGE = 500
    }
}

// ---- mapping -----------------------------------------------------------------------------

private fun HeldRow.toHeldMessage() = HeldMessage(id, author, threadRoot, effectiveTime)

private fun com.jonoshields.driftwood.core.model.Profile.toDirectoryEntity(receivedAtMillis: Long) =
    DirectoryEntity(
        author = author,
        username = username,
        claimedAt = timestampMillis,
        firstReceived = receivedAtMillis,
        lastSeenPost = receivedAtMillis,
        record = ProfileCodec.encode(this),
    )
