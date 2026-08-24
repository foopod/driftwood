package com.jonoshields.driftwood.core.data

import android.database.SQLException
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.jonoshields.driftwood.core.identity.IdentityStore
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.Message
import com.jonoshields.driftwood.core.model.MessageFactory
import com.jonoshields.driftwood.core.model.MessageId
import com.jonoshields.driftwood.core.store.Blocklist
import com.jonoshields.driftwood.core.store.Clock
import com.jonoshields.driftwood.core.model.EffectiveTime
import com.jonoshields.driftwood.core.store.Favourites
import com.jonoshields.driftwood.core.store.HeldMessage
import com.jonoshields.driftwood.core.store.PruningPlan
import com.jonoshields.driftwood.core.store.Pruner
import com.jonoshields.driftwood.core.store.StorageConfig
import com.jonoshields.driftwood.core.store.ThreadAssembler
import com.jonoshields.driftwood.core.store.ThreadView
import com.jonoshields.driftwood.core.store.Tier
import com.jonoshields.driftwood.core.store.TierClassifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One thread as it appears in the paginated list: the root, plus the latest reply from someone in scope, plus per-thread flags. */
data class ThreadSummary(
    val rootId: MessageId,
    val rootAuthor: AuthorId?,
    val rootText: String?,
    val rootTimestamp: Long?,
    val latestListenedAuthor: AuthorId?,
    val latestListenedText: String?,
    val latestListenedTimestamp: Long?,
    val messageCount: Int,
    val hasUnread: Boolean,
    val isFavourite: Boolean,
)

/** Errors that cross the repository boundary. Platform exceptions never do. */
sealed class DataError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Local(cause: Throwable) : DataError("Local storage error", cause)
    class InvalidMessage(cause: Throwable) : DataError("Message could not be created", cause)
    class NoIdentity : DataError("No identity has been created yet")
}

interface MessageRepository {
    /** Newest first. */
    fun observeAll(): Flow<List<Message>>

    /** Cheap existence check behind the first-run empty state. */
    fun observeHasAnyMessage(): Flow<Boolean>

    /** The paginated thread list for one tab; [wantListening] selects "My Circle" vs "Other", [authorFilter]/[textQuery] are the search box. */
    fun pagedThreads(
        wantListening: Boolean,
        unreadOnly: Boolean,
        authorFilter: AuthorId? = null,
        textQuery: String? = null,
    ): Flow<PagingData<ThreadSummary>>

    fun observeThread(rootId: MessageId): Flow<ThreadView>

    suspend fun post(text: String): Result<Message>

    suspend fun reply(rootId: MessageId, parent: MessageId?, text: String): Result<Message>

    /** One message, for showing what a reply is replying to. */
    suspend fun message(id: MessageId): Result<Message?>

    /** Stars or unstars a whole thread, keyed on the root id so it survives the root itself being pruned. */
    suspend fun setThreadFavourite(rootId: MessageId, favourite: Boolean): Result<Unit>

    fun observeThreadFavourite(rootId: MessageId): Flow<Boolean>

    /** Marks every message in a thread read. Opening a thread is what calls this. */
    suspend fun markThreadRead(rootId: MessageId): Result<Unit>

    /** Drops their messages and the threads they started, immediately. */
    suspend fun block(author: AuthorId): Result<Unit>

    /** Reverses the block; content already deleted at block time stays gone. */
    suspend fun unblock(author: AuthorId): Result<Unit>

    fun observeBlockedAuthors(): Flow<Set<AuthorId>>

    /** Runs the prune pass and applies it. Returns what it did. */
    suspend fun prune(): Result<PruningPlan>
}

/** The error boundary: `SQLException` and friends are caught here and mapped to [DataError] before reaching a ViewModel. */
class RoomMessageRepository internal constructor(
    private val database: DriftwoodDatabase,
    private val identity: IdentityStore,
    private val clock: Clock,
    private val config: StorageConfig,
) : MessageRepository {

    private val messages get() = database.messages()

    override fun observeAll(): Flow<List<Message>> =
        messages.observeAll().map { entities -> entities.map { it.toMessage() } }

    override fun observeHasAnyMessage(): Flow<Boolean> = messages.observeHasAnyMessage()

    override fun pagedThreads(
        wantListening: Boolean,
        unreadOnly: Boolean,
        authorFilter: AuthorId?,
        textQuery: String?,
    ): Flow<PagingData<ThreadSummary>> {
        val myAuthor = runCatching { identity.publicKey() }.getOrNull()
        return Pager(PagingConfig(pageSize = THREAD_PAGE_SIZE, prefetchDistance = THREAD_PREFETCH, enablePlaceholders = false)) {
            messages.pagedThreads(myAuthor, wantListening, unreadOnly, authorFilter, textQuery)
        }.flow.map { page -> page.map { it.toThreadSummary() } }
    }

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

    private suspend fun create(build: (com.jonoshields.driftwood.core.model.Signer, AuthorId) -> Message): Result<Message> =
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
            // This device authored it, so effective_time is simply its own timestamp.
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

    override suspend fun markThreadRead(rootId: MessageId): Result<Unit> =
        runCatching { messages.markThreadRead(rootId) }.mapLocalErrors()

    override suspend fun block(author: AuthorId): Result<Unit> = runCatching {
        val now = clock.nowMillis()
        database.blocklist().blockAuthor(BlockedAuthorEntity(author, now))

        // Capture their threads before deleting the roots that identify them.
        val roots = messages.rootsAuthoredBy(author)
        database.blocklist().blockRoots(roots.map { BlockedRootEntity(it, now) })

        // Blocking prunes immediately rather than waiting for the next sync.
        applyPlan(planPrune())
    }.mapLocalErrors()

    override suspend fun unblock(author: AuthorId): Result<Unit> = runCatching {
        database.blocklist().unblockAuthor(author)
    }.mapLocalErrors()

    override fun observeBlockedAuthors(): Flow<Set<AuthorId>> =
        database.blocklist().observeBlockedAuthors().map { it.toSet() }

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
        // Composed here, on this device — you have obviously already read your own message.
        messages.insert(message.toEntity(firstReceivedTime, tier, read = true))
    }
}

// ---- mapping ---------------------------------------------------------------------------

internal fun MessageEntity.toMessage(): Message = Message.unverified(
    id = id,
    signature = signature,
    body = com.jonoshields.driftwood.core.model.MessageBody(
        version = version,
        author = author,
        root = root,
        parent = parent,
        timestampMillis = timestampMillis,
        text = text,
    ),
)

internal fun ThreadSummaryRow.toThreadSummary() = ThreadSummary(
    rootId = rootId,
    rootAuthor = rootAuthor,
    rootText = rootText,
    rootTimestamp = rootTimestamp,
    latestListenedAuthor = latestListenedAuthor,
    latestListenedText = latestListenedText,
    latestListenedTimestamp = latestListenedTimestamp,
    messageCount = messageCount,
    hasUnread = hasUnread,
    isFavourite = isFavourite,
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

internal fun Message.toEntity(firstReceivedTime: Long, tier: Tier, read: Boolean) = MessageEntity(
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
    read = read,
    tier = tier,
)

private const val THREAD_PAGE_SIZE = 30
private const val THREAD_PREFETCH = 10

/** Keeps platform storage exceptions from escaping; [DataError]s pass through unchanged. */
private fun <T> Result<T>.mapLocalErrors(): Result<T> = recoverCatching { cause ->
    throw when (cause) {
        is DataError -> cause
        is SQLException -> DataError.Local(cause)
        is IllegalStateException -> DataError.Local(cause)
        else -> cause
    }
}
