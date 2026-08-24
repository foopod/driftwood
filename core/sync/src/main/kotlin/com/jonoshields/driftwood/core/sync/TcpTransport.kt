package com.jonoshields.driftwood.core.sync

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A plain TCP socket transport over a shared Wi-Fi LAN — Wi-Fi Direct also terminates in a plain socket, so it reuses this unchanged. */
object TcpTransport {

    /** Opens an outgoing connection. The other side must already be listening. */
    suspend fun connect(host: String, port: Int): Connection = withContext(Dispatchers.IO) {
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port))
        socket.toConnection()
    }

    /** Accepts one incoming connection at a time; the caller loops on [accept] between sessions to keep listening. */
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
        // Small, strictly-alternating frames are exactly what Nagle's algorithm delays waiting to coalesce.
        tcpNoDelay = true
        return FramedConnection(getInputStream(), getOutputStream(), closer = { runCatching { close() } })
    }
}
