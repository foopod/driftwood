package com.jonoshields.gossip.core.sync

import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.model.EffectiveTime
import com.jonoshields.gossip.core.model.Message
import com.jonoshields.gossip.core.model.MessageCodec
import com.jonoshields.gossip.core.model.MessageId
import com.jonoshields.gossip.core.model.OrderKey
import com.jonoshields.gossip.core.model.Profile
import com.jonoshields.gossip.core.model.ProfileCodec
import com.jonoshields.gossip.core.store.Blocklist
import com.jonoshields.gossip.core.store.Favourites
import com.jonoshields.gossip.core.store.HeldMessage
import com.jonoshields.gossip.core.store.PartitionBudgets
import com.jonoshields.gossip.core.store.Pruner
import com.jonoshields.gossip.core.store.StorageDefaults

/**
 * A [SyncStore] backed by maps, for the convergence harness.
 *
 * A fake rather than a mock (`testing-setup` Step 4): it really stores, really prunes, and
 * really tracks a want-list, so a test that passes against it is testing behaviour and not a
 * script of expected calls. What it does not do is SQL — that is the Room implementation's
 * job, and its own instrumented tests.
 */
internal class InMemorySyncStore(
    private val listen: MutableSet<AuthorId> = mutableSetOf(),
    private var blocklist: Blocklist = Blocklist(emptySet(), emptySet()),
    private val windowMillis: Long = StorageDefaults.WINDOW_MILLIS,
    private val budgets: PartitionBudgets = PartitionBudgets(10_000, 10_000, 10_000),
) : SyncStore {

    private val messages = linkedMapOf<MessageId, Stored>()
    private val profiles = linkedMapOf<AuthorId, ByteArray>()
    private val wantList = mutableMapOf<MessageId, Int>()
    private val favourites = mutableSetOf<MessageId>()

    private data class Stored(val message: Message, val firstReceived: Long) {
        val effectiveTime: Long get() = EffectiveTime.of(message.body.timestampMillis, firstReceived)
        val held: HeldMessage
            get() = HeldMessage(message.id, message.body.author, message.threadRoot, effectiveTime)
    }

    // ---- setting up a scenario -------------------------------------------------------

    fun seed(message: Message, firstReceivedMillis: Long = message.body.timestampMillis) = apply {
        messages[message.id] = Stored(message, firstReceivedMillis)
    }

    fun seedProfile(profile: Profile) = apply {
        profiles[profile.author] = ProfileCodec.encode(profile)
    }

    fun listenTo(vararg authors: AuthorId) = apply { listen += authors }

    fun block(authors: Set<AuthorId> = emptySet(), roots: Set<MessageId> = emptySet()) = apply {
        blocklist = Blocklist(blocklist.authors + authors, blocklist.roots + roots)
    }

    fun want(vararg ids: MessageId) = apply { ids.forEach { wantList[it] = 0 } }

    fun star(root: MessageId) = apply { favourites += root }

    // ---- inspecting the result -------------------------------------------------------

    val ids: Set<MessageId> get() = messages.keys.toSet()

    fun holds(id: MessageId): Boolean = id in messages

    fun nicknameFor(author: AuthorId): String? =
        profiles[author]?.let { ProfileCodec.verify(it) }
            ?.let { it as? com.jonoshields.gossip.core.model.ProfileVerifyResult.Valid }
            ?.profile?.nickname

    val outstandingWants: Set<MessageId> get() = wantList.keys.toSet()

    fun unsatisfiedSyncs(id: MessageId): Int = wantList[id] ?: 0

    // ---- SyncStore -------------------------------------------------------------------

    override suspend fun listenScope(): Set<AuthorId> = listen.toSet()

    override suspend fun windowCutoff(nowMillis: Long): Long = nowMillis - windowMillis

    override suspend fun wants(): Set<MessageId> = wantList.keys.toSet()

    override suspend fun heldBy(authors: Set<AuthorId>, since: Long?): List<HeldMessage> =
        messages.values
            .filter { it.message.body.author in authors }
            .filter { since == null || it.effectiveTime >= since }
            .map { it.held }

    override suspend fun heldInThreads(roots: Set<MessageId>, since: Long): List<HeldMessage> =
        messages.values
            .filter { it.message.threadRoot in roots && it.effectiveTime >= since }
            .map { it.held }

    override suspend fun heldWithIds(ids: Set<MessageId>): List<HeldMessage> =
        ids.mapNotNull { messages[it]?.held }

    override suspend fun newestHeld(limit: Int, excluding: Set<MessageId>): List<HeldMessage> =
        messages.values
            .filterNot { it.message.id in excluding }
            .map { it.held }
            .sortedByDescending { OrderKey(it.effectiveTime, it.id) }
            .take(limit)

    override suspend fun readMessages(ids: List<MessageId>): List<ByteArray> =
        ids.mapNotNull { messages[it] }.map { MessageCodec.encode(it.message) }

    override suspend fun readProfiles(authors: Set<AuthorId>): List<ByteArray> =
        authors.mapNotNull { profiles[it] }

    override suspend fun blocklist(): Blocklist = blocklist

    override suspend fun apply(outcome: PhaseOutcome, receivedAtMillis: Long) {
        outcome.accepted.forEach { message ->
            // Content-addressed, so re-ingesting something we hold is a no-op rather than a
            // conflict — which is exactly what makes sending context blind affordable.
            if (message.id !in messages) {
                messages[message.id] = Stored(message, receivedAtMillis)
            }
        }
        outcome.profiles.forEach { profile ->
            profiles[profile.author] = ProfileCodec.encode(profile)
        }

        // Wants satisfied by what arrived, and parents newly discovered to be missing.
        wantList.keys.removeAll(outcome.accepted.mapTo(mutableSetOf()) { it.id })
        outcome.accepted.forEach { message ->
            val parent = message.body.parent
            if (parent != null && parent !in messages) wantList.putIfAbsent(parent, 0)
        }
    }

    /** Counts a fruitless sync against every want that went unfilled (`WANT_TTL`, §3.4). */
    fun ageWants(ttl: Int = WANT_TTL) {
        wantList.keys.toList().forEach { id ->
            val attempts = (wantList[id] ?: 0) + 1
            if (attempts >= ttl) wantList.remove(id) else wantList[id] = attempts
        }
    }

    override suspend fun pruneAfterSession(nowMillis: Long) {
        val plan = Pruner.plan(
            held = messages.values.map { it.held },
            listen = listen,
            blocklist = blocklist,
            favourites = Favourites(favourites.toSet()),
            budgets = budgets,
            windowMillis = windowMillis,
            nowMillis = nowMillis,
        )
        plan.evict.forEach { messages.remove(it) }
    }
}

/** plan.md §3.4: fruitless syncs before a want is dropped. */
const val WANT_TTL: Int = 10
