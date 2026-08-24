package com.jonoshields.driftwood.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/** Two connected [Connection] ends in one process, with bounded buffering so it deadlocks under back-pressure the way a real socket would. */
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
            } catch (e: CancellationException) {
                // Only re-throw if this coroutine's own cancellation, not the peer's channel closing.
                if (currentCoroutineContext().isActive) {
                    throw ConnectionClosed("peer dropped the link", e)
                }
                throw e
            }
        }

        override suspend fun receive(): ByteArray? =
            try {
                incoming.receive()
            } catch (e: ClosedReceiveChannelException) {
                null
            }

        /** Outgoing closes gracefully so queued frames still reach the peer; incoming cancels so a blocked sender unblocks. */
        override fun close() {
            outgoing.close()
            incoming.cancel()
        }
    }
}
