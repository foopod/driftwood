package com.jonoshields.driftwood.core.store

import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.MessageId

/** Ids built so [author] `n` sorts ascending in `n`, matching the order the fair-share remainder is handed out in. */
internal fun author(n: Int): AuthorId =
    AuthorId.of(ByteArray(32).also { it[0] = n.toByte() })

internal fun msgId(n: Int): MessageId =
    MessageId.of(ByteArray(32).also { it[0] = (n ushr 8).toByte(); it[1] = n.toByte() })

private var nextId = 1

internal fun held(
    author: AuthorId,
    effectiveTime: Long,
    threadRoot: MessageId = msgId(0),
    id: MessageId = msgId(nextId++),
): HeldMessage = HeldMessage(
    id = id,
    author = author,
    threadRoot = threadRoot,
    effectiveTime = effectiveTime,
)

/** [count] messages by one author, with effective times 1, 2, 3 … so "oldest" is obvious. */
internal fun messagesBy(
    author: AuthorId,
    count: Int,
    threadRoot: MessageId = msgId(0),
    startingAt: Long = 1,
): List<HeldMessage> = (0 until count).map {
    held(author = author, effectiveTime = startingAt + it, threadRoot = threadRoot)
}

/** Budgets big enough that fair share never bites, for tests aimed at something else. */
internal val UNLIMITED = PartitionBudgets(follow = 10_000, context = 10_000, gossip = 10_000)

internal fun budgets(follow: Int, context: Int, gossip: Int) =
    PartitionBudgets(follow = follow, context = context, gossip = gossip)

internal fun noBlocks() = Blocklist(authors = emptySet(), roots = emptySet())

internal fun pinned(vararg roots: MessageId) = PinnedRoots(roots.toSet())
