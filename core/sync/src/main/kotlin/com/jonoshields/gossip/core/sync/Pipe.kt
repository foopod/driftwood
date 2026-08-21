package com.jonoshields.gossip.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

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
            } catch (e: CancellationException) {
                // The channel was cancelled out from under us because the peer went away.
                // Only *our* cancellation is a real cancellation, so check before swallowing
                // it — converting a genuine one into a plain exception would make this
                // coroutine uncancellable.
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

        /**
         * Releases the link in both directions, the way closing a socket does.
         *
         * The two halves are deliberately different. Outgoing is closed *gracefully*, so
         * frames we already handed over still reach the peer before it sees the end — a
         * session that aborts should still manage to deliver its `ABORT`.
         *
         * Incoming is **cancelled**, not closed, and that asymmetry is the whole point.
         * `Channel.close()` is a signal to the receiver: it does not resume a sender already
         * suspended against a full buffer, so a peer flooding us would stay parked forever
         * and no amount of closing on our side would free it. `cancel()` does resume it —
         * which is what a peer writing to a socket we have closed actually experiences.
         */
        override fun close() {
            outgoing.close()
            incoming.cancel()
        }
    }
}
