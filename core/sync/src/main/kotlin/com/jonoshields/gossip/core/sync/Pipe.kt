package com.jonoshields.gossip.core.sync

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException

/**
 * Two connected ends in one process, for proving the protocol before any radio exists.
 *
 * **Buffering is bounded on purpose.** An unlimited channel would let both sides write their
 * whole delta without either reading, which a real socket will not — its buffer fills and the
 * writer blocks. A protocol that deadlocks under back-pressure would then pass every test here
 * and fail first on real hardware in M3a, which is exactly the confusion this milestone exists
 * to avoid. A small buffer makes the mock pessimistic in the same way a socket is.
 */
object Pipe {

    /** Frames each direction can hold before a sender has to wait for a reader. */
    const val DEFAULT_CAPACITY: Int = 2

    fun open(capacity: Int = DEFAULT_CAPACITY): Pair<Connection, Connection> {
        val aToB = Channel<ByteArray>(capacity)
        val bToA = Channel<ByteArray>(capacity)
        return End(outgoing = aToB, incoming = bToA) to End(outgoing = bToA, incoming = aToB)
    }

    private class End(
        private val outgoing: Channel<ByteArray>,
        private val incoming: Channel<ByteArray>,
    ) : Connection {

        override suspend fun send(frame: ByteArray) {
            try {
                outgoing.send(frame)
            } catch (e: ClosedSendChannelException) {
                throw ConnectionClosed("peer closed the link", e)
            }
        }

        override suspend fun receive(): ByteArray? =
            try {
                incoming.receive()
            } catch (e: ClosedReceiveChannelException) {
                null
            }

        override fun close() {
            outgoing.close()
        }
    }
}
