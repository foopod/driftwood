package com.jonoshields.gossip.core.data

import android.database.SQLException
import com.jonoshields.gossip.core.identity.IdentityStore
import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.model.Message
import com.jonoshields.gossip.core.model.MessageFactory
import com.jonoshields.gossip.core.model.MessageId
import com.jonoshields.gossip.core.store.Blocklist
import com.jonoshields.gossip.core.store.Clock
import com.jonoshields.gossip.core.model.EffectiveTime
import com.jonoshields.gossip.core.store.Favourites
import com.jonoshields.gossip.core.store.HeldMessage
import com.jonoshields.gossip.core.store.PruningPlan
import com.jonoshields.gossip.core.store.Pruner
import com.jonoshields.gossip.core.store.StorageConfig
import com.jonoshields.gossip.core.store.ThreadAssembler
import com.jonoshields.gossip.core.store.ThreadView
import com.jonoshields.gossip.core.store.Tier
import com.jonoshields.gossip.core.store.TierClassifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Errors that cross the repository boundary. Platform exceptions never do. */
sealed class DataError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Local(cause: Throwable) : DataError("Local storage error", cause)
    class InvalidMessage(cause: Throwable) : DataError("Message could not be created", cause)
    class NoIdentity : DataError("No identity has been created yet")
}

interface MessageRepository {
    /** Newest first. */
    fun observeAll(): Flow<List<Message>>

    fun observeThread(rootId: MessageId): Flow<ThreadView>

    suspend fun post(text: String): Result<Message>

    suspend fun reply(rootId: MessageId, parent: MessageId?, text: String): Result<Message>

    /** One message, for showing what a reply is replying to. */
    suspend fun message(id: MessageId): Result<Message?>

    /**
     * Stars or unstars a whole thread, exempting everything in it from the caps.
     *
     * Keyed on the root id rather than the root message, so a thread can be kept even when
     * its opening message is long gone.
     */
    suspend fun setThreadFavourite(rootId: MessageId, favourite: Boolean): Result<Unit>

    fun observeThreadFavourite(rootId: MessageId): Flow<Boolean>

    fun observeFavouriteRoots(): Flow<Set<MessageId>>

    /** Drops their messages and the threads they started, immediately (plan.md §4). */
    suspend fun block(author: AuthorId): Result<Unit>

    /** Runs the prune pass and applies it. Returns what it did. */
    suspend fun prune(): Result<PruningPlan>
}

/**
 * The **error boundary** (`android-data-layer`): `SQLException` and friends are caught here
 * and mapped to [DataError], so no platform exception ever reaches a ViewModel. There is no
 * domain layer, so `Result` lives at the repository.
 */
class RoomMessageRepository internal constructor(
    private val database: GossipDatabase,
    private val identity: IdentityStore,
    private val clock: Clock,
    private val config: StorageConfig,
) : MessageRepository {

    private val messages get() = database.messages()

    override fun observeAll(): Flow<List<Message>> =
        messages.observeAll().map { entities -> entities.map { it.toMessage() } }

    override fun observeThread(rootId: MessageId): Flow<ThreadView> =
        messages.observeThread(rootId).map { entities ->
            ThreadAssembler.assemble(rootId, entities.map { it.toMessage() })
        }

    override suspend fun post(text: String): Result<Message> =
        create { signer, author -> MessageFactory.createRoot(author, text, clock.nowMillis(), signer) }

    override suspend fun reply(rootId: MessageId, parent: MessageId?, text: String): Result<Message> =
        create { signer, author ->
            MessageFactory.createReply(author, rootId, parent, text, clock.nowMillis(), signer)
        }

    private suspend fun create(build: (com.jonoshields.gossip.core.model.Signer, AuthorId) -> Message): Result<Message> =
        runCatching {
            val author = try {
                identity.publicKey()
            } catch (e: IllegalStateException) {
                throw DataError.NoIdentity()
            }
            val message = try {
                build(identity.signer(), author)
            } catch (e: IllegalArgumentException) {
                // Over-length or malformed text: a user mistake, not a storage failure.
                throw DataError.InvalidMessage(e)
            }
            // A message this device authored was, by definition, first seen at creation
            // (plan.md §4), so effective_time is simply its own timestamp.
            insert(message, firstReceivedTime = message.body.timestampMillis)
            message
        }.mapLocalErrors()

    override suspend fun message(id: MessageId): Result<Message?> =
        runCatching { messages.find(id)?.toMessage() }.mapLocalErrors()

    override suspend fun setThreadFavourite(rootId: MessageId, favourite: Boolean): Result<Unit> =
        runCatching {
            if (favourite) {
                database.favourites().star(FavouriteRootEntity(rootId, clock.nowMillis()))
            } else {
                database.favourites().unstar(rootId)
            }
        }.mapLocalErrors()

    override fun observeThreadFavourite(rootId: MessageId): Flow<Boolean> =
        database.favourites().observeIsStarred(rootId)

    override fun observeFavouriteRoots(): Flow<Set<MessageId>> =
        database.favourites().observeStarredRoots().map { it.toSet() }

    override suspend fun block(author: AuthorId): Result<Unit> = runCatching {
        val now = clock.nowMillis()
        database.blocklist().blockAuthor(BlockedAuthorEntity(author, now))

        // Remember their threads before deleting the roots that identify them — after this,
        // the roots are gone and there is nothing left to derive the association from.
        val roots = messages.rootsAuthoredBy(author)
        database.blocklist().blockRoots(roots.map { BlockedRootEntity(it, now) })

        // Blocking is the documented exception to prune-at-sync: "never show me this person
        // again" landing three days later is not acceptable behaviour.
        applyPlan(planPrune())
    }.mapLocalErrors()

    override suspend fun prune(): Result<PruningPlan> = runCatching {
        planPrune().also { applyPlan(it) }
    }.mapLocalErrors()

    private suspend fun planPrune(): PruningPlan {
        val held = messages.all()
        return Pruner.plan(
            held = held.map { it.toHeldMessage() },
            listen = database.listen().authors().toSet(),
            blocklist = Blocklist(
                authors = database.blocklist().blockedAuthors().toSet(),
                roots = database.blocklist().blockedRoots().toSet(),
            ),
            favourites = Favourites(database.favourites().starredRoots().toSet()),
            budgets = config.budgets(),
            windowMillis = config.windowMillis,
            nowMillis = clock.nowMillis(),
        )
    }

    private suspend fun applyPlan(plan: PruningPlan) {
        chunkedAction(plan.evict) { messages.deleteChunk(it) }
        // Tiers are recomputed by the same pass, so persist the survivors' new tiers.
        plan.tiers.forEach { (id, tier) ->
            if (id !in plan.evict) messages.setTier(id, tier)
        }
    }

    private suspend fun insert(message: Message, firstReceivedTime: Long) {
        val listen = database.listen().authors().toSet()
        val tier = TierClassifier
            .classify(listOf(message.toHeldMessage(firstReceivedTime)), listen)
            .getValue(message.id)
        messages.insert(message.toEntity(firstReceivedTime, tier))
    }
}

// ---- mapping ---------------------------------------------------------------------------

internal fun MessageEntity.toMessage(): Message = Message.unverified(
    id = id,
    signature = signature,
    body = com.jonoshields.gossip.core.model.MessageBody(
        version = version,
        author = author,
        root = root,
        parent = parent,
        timestampMillis = timestampMillis,
        text = text,
    ),
)

internal fun MessageEntity.toHeldMessage() = HeldMessage(
    id = id,
    author = author,
    threadRoot = threadRoot,
    effectiveTime = effectiveTime,
)

internal fun Message.toHeldMessage(firstReceivedTime: Long) = HeldMessage(
    id = id,
    author = body.author,
    threadRoot = threadRoot,
    effectiveTime = EffectiveTime.of(body.timestampMillis, firstReceivedTime),
)

internal fun Message.toEntity(firstReceivedTime: Long, tier: Tier) = MessageEntity(
    id = id,
    version = body.version,
    author = body.author,
    root = body.root,
    parent = body.parent,
    threadRoot = threadRoot,
    timestampMillis = body.timestampMillis,
    text = body.text,
    signature = signature,
    firstReceivedTime = firstReceivedTime,
    effectiveTime = EffectiveTime.of(body.timestampMillis, firstReceivedTime),
    read = true, // your own message, and you have obviously read it
    tier = tier,
)

/** Keeps platform storage exceptions from escaping; [DataError]s pass through unchanged. */
private fun <T> Result<T>.mapLocalErrors(): Result<T> = recoverCatching { cause ->
    throw when (cause) {
        is DataError -> cause
        is SQLException -> DataError.Local(cause)
        is IllegalStateException -> DataError.Local(cause)
        else -> cause
    }
}
