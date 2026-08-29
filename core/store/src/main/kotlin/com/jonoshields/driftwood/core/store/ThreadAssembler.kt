package com.jonoshields.driftwood.core.store

import com.jonoshields.driftwood.core.model.Message
import com.jonoshields.driftwood.core.model.MessageId
import com.jonoshields.driftwood.core.model.OrderKey

/** One message in a thread; [detached] means its stated parent isn't held — never an error. [unsent] means it's still eligible for local deletion — see `MessageEntity.unsent`. */
data class ThreadNode(
    val message: Message,
    val children: List<ThreadNode>,
    val detached: Boolean,
    val unsent: Boolean = false,
)

/** A thread as currently held; [root] is null when the root message itself isn't held (a normal state, not damage). [rootUnsent] mirrors [ThreadNode.unsent] for the root, which isn't wrapped in one. */
data class ThreadView(
    val rootId: MessageId,
    val root: Message?,
    val replies: List<ThreadNode>,
    val rootUnsent: Boolean = false,
)

/** Builds a thread's tree from whatever fragment is held; every gap has a defined rendering, nothing here fails. */
object ThreadAssembler {

    /** [unsentIds] is local-only bookkeeping (never part of the signed [Message]/`MessageBody`), so it's passed in as a plain id set rather than any storage-specific type. */
    fun assemble(rootId: MessageId, messages: List<Message>, unsentIds: Set<MessageId> = emptySet()): ThreadView {
        val inThread = messages.filter { it.threadRoot == rootId }
        val root = inThread.firstOrNull { it.isRoot && it.id == rootId }

        val replies = inThread.filter { it.id != root?.id }
        val heldIds = replies.mapTo(mutableSetOf()) { it.id }

        // Naming the root, or naming nothing, both mean "attach at the top" — not a gap.
        val childrenByParent = mutableMapOf<MessageId, MutableList<Message>>()
        val topLevel = mutableListOf<Message>()
        val detached = mutableSetOf<MessageId>()

        replies.forEach { message ->
            val parent = message.body.parent
            when {
                parent == null || parent == rootId -> topLevel += message
                parent in heldIds -> childrenByParent.getOrPut(parent) { mutableListOf() } += message
                else -> {
                    topLevel += message
                    detached += message.id
                }
            }
        }

        // A cycle can't happen with honest data, but a corrupt store could, so track visited.
        val visited = mutableSetOf<MessageId>()

        fun build(message: Message): ThreadNode {
            visited += message.id
            val children = childrenByParent[message.id].orEmpty()
                .filter { it.id !in visited }
                .sortedBy { OrderKey(it.body.timestampMillis, it.id) }
                .map(::build)
            return ThreadNode(message, children, message.id in detached, unsent = message.id in unsentIds)
        }

        return ThreadView(
            rootId = rootId,
            root = root,
            replies = topLevel
                .sortedBy { OrderKey(it.body.timestampMillis, it.id) }
                .filter { it.id !in visited }
                .map(::build),
            rootUnsent = root != null && root.id in unsentIds,
        )
    }
}
