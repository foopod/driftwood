package com.jonoshields.gossip.core.sync

import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.model.MessageId
import com.jonoshields.gossip.core.store.Blocklist
import com.jonoshields.gossip.core.store.HeldMessage

internal fun author(n: Int): AuthorId = AuthorId.of(ByteArray(32).also { it[0] = n.toByte() })

internal fun msgId(n: Int): MessageId =
    MessageId.of(ByteArray(32).also { it[0] = (n ushr 8).toByte(); it[1] = n.toByte() })

private var nextId = 1

internal fun held(
    author: AuthorId,
    effectiveTime: Long,
    threadRoot: MessageId = msgId(0),
    id: MessageId = msgId(nextId++),
): HeldMessage = HeldMessage(id, author, threadRoot, effectiveTime)

internal fun noBlocks() = Blocklist(emptySet(), emptySet())

internal fun scope(
    listen: Set<AuthorId> = emptySet(),
    windowCutoff: Long = 0,
    wants: Set<MessageId> = emptySet(),
) = ScopeDeclaration(listen, windowCutoff, wants)
