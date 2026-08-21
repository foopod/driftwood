package com.jonoshields.gossip.core.sync

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The first real transport (M3a, plan.md §7): a plain TCP socket over a shared Wi-Fi LAN.
 *
 * Touches nothing Android-specific — only `java.net` — so the whole transport is provable
 * with plain JVM tests over real `localhost` sockets, one layer more real than the [Pipe]
 * mock: actual bytes over an actual stream, with a real kernel free to deliver them in
 * whatever chunks it likes, rather than the whole-frame delivery an in-memory channel gives
 * for free. Wi-Fi Direct (M3b) also terminates in a plain socket, so it reuses this
 * unchanged; only discovery and connect differ there.
 */
object TcpTransport {

    /** Opens an outgoing connection. The other side must already be listening. */
    suspend fun connect(host: String, port: Int): Connection = withContext(Dispatchers.IO) {
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port))
        socket.toConnection()
    }

    /**
     * Accepts one incoming connection at a time. A sync session is one purposeful exchange
     * between two present people, not a server fielding concurrent clients — the caller loops
     * on [accept] between sessions if it wants to keep listening.
     */
    class Listener(port: Int = 0) : AutoCloseable {
        private val serverSocket = ServerSocket(port)

        /** The bound port — meaningful when [port] was `0`, an OS-assigned ephemeral port. */
        val port: Int get() = serverSocket.localPort

        suspend fun accept(): Connection = withContext(Dispatchers.IO) {
            serverSocket.accept().toConnection()
        }

        override fun close() = serverSocket.close()
    }

    private fun Socket.toConnection(): Connection {
        // Frames are small and the protocol strictly alternates a reply for every send, which
        // is exactly the pattern Nagle's algorithm was built to punish — it would sit on a
        // small write hoping to coalesce it with one that is never coming.
        tcpNoDelay = true
        return FramedConnection(getInputStream(), getOutputStream(), closer = { runCatching { close() } })
    }
}
