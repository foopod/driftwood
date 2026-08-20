package com.jonoshields.gossip.core.store

import com.jonoshields.gossip.core.model.Message
import com.jonoshields.gossip.core.model.MessageId
import com.jonoshields.gossip.core.model.OrderKey

/**
 * One message in a thread. [detached] means its stated parent is not held — the quiet
 * "replying to a message not carried here" marker, never an error.
 */
data class ThreadNode(
    val message: Message,
    val children: List<ThreadNode>,
    val detached: Boolean,
)

/**
 * A thread as this device can currently render it. [root] is null when the root message
 * itself is not held, which is a normal end state rather than damage: root ids outlive root
 * content (plan.md §3.2), and the UI shows a placeholder for the beginning of the
 * conversation.
 */
data class ThreadView(
    val rootId: MessageId,
    val root: Message?,
    val replies: List<ThreadNode>,
)

/**
 * Builds the tree for one thread out of whatever fragment of it is held (plan.md §3.2, §6).
 *
 * Fragmentation is the expected case, not the exceptional one, so every gap has a defined
 * rendering: a reply whose parent is missing flattens up to the root and is marked; a thread
 * whose root is missing still assembles under a placeholder. Nothing here fails.
 */
object ThreadAssembler {

    fun assemble(rootId: MessageId, messages: List<Message>): ThreadView {
        val inThread = messages.filter { it.threadRoot == rootId }
        val root = inThread.firstOrNull { it.isRoot && it.id == rootId }

        val replies = inThread.filter { it.id != root?.id }
        val heldIds = replies.mapTo(mutableSetOf()) { it.id }

        // A parent counts as held only if it is a *reply in this same thread*. Naming the
        // root, or naming nothing, both mean "attach at the top" — neither is a gap, because
        // in both cases we know exactly where the message belongs.
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

        // Content-addressed ids make a parent cycle impossible in honest data — a message's
        // id depends on its parent, so it cannot be its own ancestor. A corrupt store could
        // still present one, so the walk tracks visited ids rather than trusting that.
        val visited = mutableSetOf<MessageId>()

        fun build(message: Message): ThreadNode {
            visited += message.id
            val children = childrenByParent[message.id].orEmpty()
                .filter { it.id !in visited }
                .sortedBy { OrderKey(it.body.timestampMillis, it.id) }
                .map(::build)
            return ThreadNode(message, children, message.id in detached)
        }

        return ThreadView(
            rootId = rootId,
            root = root,
            replies = topLevel
                .sortedBy { OrderKey(it.body.timestampMillis, it.id) }
                .filter { it.id !in visited }
                .map(::build),
        )
    }
}
