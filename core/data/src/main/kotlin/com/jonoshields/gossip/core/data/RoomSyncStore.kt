package com.jonoshields.gossip.core.data

import androidx.room.withTransaction
import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.model.EffectiveTime
import com.jonoshields.gossip.core.model.Message
import com.jonoshields.gossip.core.model.MessageCodec
import com.jonoshields.gossip.core.model.MessageId
import com.jonoshields.gossip.core.model.ProfileCodec
import com.jonoshields.gossip.core.store.Blocklist
import com.jonoshields.gossip.core.store.Clock
import com.jonoshields.gossip.core.store.Favourites
import com.jonoshields.gossip.core.store.HeldMessage
import com.jonoshields.gossip.core.store.Pruner
import com.jonoshields.gossip.core.store.StorageConfig
import com.jonoshields.gossip.core.store.TierClassifier
import com.jonoshields.gossip.core.sync.PhaseOutcome
import com.jonoshields.gossip.core.sync.SyncStore
import com.jonoshields.gossip.core.sync.WANT_TTL

/**
 * The real [SyncStore], over SQLite.
 *
 * `:core:sync` defines the port and never sees this class; the protocol was proven against an
 * in-memory implementation of the same interface before any of it existed. What is left here
 * is genuinely only storage, which is the point of having drawn the seam there.
 *
 * Two rules the queries follow throughout:
 *
 *  - **Reads return metadata, not content.** A session decides what to send from ids, authors,
 *    threads and times; message text is fetched only for what is actually going on the wire.
 *  - **Every `IN (:ids)` is chunked.** See [SQL_VARIABLE_LIMIT] — this store fills to a cap by
 *    design, so batches larger than SQLite's variable limit are the normal case here.
 */
internal class RoomSyncStore(
    private val database: GossipDatabase,
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
        // Paged and filtered here rather than with `NOT IN`, because [excluding] can hold the
        // peer's entire hash-list and that is the one clause chunking cannot express.
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
        // Re-encoded from stored fields rather than kept as raw bytes. Safe because strict
        // decoding means every encoding we ever accepted was the canonical one, so a
        // re-encode reproduces the exact preimage the id was hashed over — proven by the
        // decode-then-encode property test in :core:model rather than assumed here, since a
        // violation would only ever surface as a rejection two devices downstream.
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

        // One transaction per phase. A half-applied phase would leave the want-list claiming
        // ids the store does not hold, and the next session would ask for them all over again.
        database.withTransaction {
            val listen = database.listen().authors().toSet()

            outcome.accepted.forEach { message ->
                // first_received is now, so effective_time clamps a forward-dated message to
                // when it actually reached us (plan.md §3.2). Backdating is left alone: a
                // message claiming to be older than it is only disadvantages itself.
                val held = message.toHeldMessage(receivedAtMillis)
                val tier = TierClassifier.classify(listOf(held), listen).getValue(message.id)
                messages.insert(message.toEntity(receivedAtMillis, tier))
                database.directory().touch(message.body.author, held.effectiveTime)
            }

            outcome.profiles.forEach { profile ->
                database.directory().upsert(profile.toDirectoryEntity(receivedAtMillis))
            }

            updateWants(outcome, receivedAtMillis)
        }
    }

    /**
     * Satisfied wants go, newly orphaned parents arrive, and everything still outstanding
     * ages by one fruitless sync.
     *
     * The ageing is what stops the list growing forever. A parent nobody we meet happens to
     * hold is not chased; after [WANT_TTL] attempts it is simply forgotten, because content
     * that has aged out of the network everywhere is gone, and the design treats that as an
     * ordinary outcome rather than a failure.
     */
    private suspend fun updateWants(outcome: PhaseOutcome, nowMillis: Long) {
        val arrived = outcome.accepted.mapTo(mutableSetOf()) { it.id }
        chunkedAction(arrived) { wantList.removeChunk(it) }

        val orphans = outcome.accepted
            .mapNotNull { it.body.parent }
            .filterNot { it in arrived }
            .distinct()
            .filter { messages.find(it) == null }
        wantList.add(orphans.map { WantEntity(it, nowMillis, unsatisfiedSyncs = 0) })

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

private fun com.jonoshields.gossip.core.model.Profile.toDirectoryEntity(receivedAtMillis: Long) =
    DirectoryEntity(
        author = author,
        nickname = nickname,
        claimedAt = timestampMillis,
        firstReceived = receivedAtMillis,
        lastSeenPost = receivedAtMillis,
        record = ProfileCodec.encode(this),
    )
