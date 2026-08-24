package com.jonoshields.driftwood.sync

import com.jonoshields.driftwood.core.sync.Connection
import com.jonoshields.driftwood.core.sync.FramedConnection
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Turns a formed Wi-Fi Direct group into a [Connection] — pure `java.net`, testable with real sockets. */
object WifiDirectSocketEstablisher {

    const val PORT: Int = 8988

    // Covers known real-hardware races: dialling before the GO's socket finishes binding, etc.
    private const val CONNECT_ATTEMPTS = 8
    private const val CONNECT_RETRY_DELAY_MILLIS = 500L

    // A per-attempt bound, or one slow dial could collapse the whole retry window into one hang.
    private const val CONNECT_TIMEOUT_MILLIS = 1_000

    /** The group-owner side — mirrors `TcpTransport.Listener`: bind immediately, accept once, closeable. */
    class GroupOwnerListener(port: Int = PORT) : AutoCloseable {
        private val serverSocket = ServerSocket(port)

        /** The bound port — meaningful when [port] was `0`, an OS-assigned ephemeral port. */
        val port: Int get() = serverSocket.localPort

        suspend fun accept(): Connection = withContext(Dispatchers.IO) {
            serverSocket.accept().toConnection()
        }

        override fun close() = serverSocket.close()
    }

    /** The non-owner side — retries through the settling window real devices exhibit after group formation. */
    suspend fun connectToGroupOwner(address: InetAddress, port: Int = PORT): Connection {
        var lastError: IOException? = null
        repeat(CONNECT_ATTEMPTS) { attempt ->
            try {
                return withContext(Dispatchers.IO) {
                    val socket = Socket()
                    socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS)
                    socket.toConnection()
                }
            } catch (e: IOException) {
                lastError = e
            }
            if (attempt < CONNECT_ATTEMPTS - 1) delay(CONNECT_RETRY_DELAY_MILLIS)
        }
        throw lastError ?: IOException("couldn't reach the group owner")
    }

    private fun Socket.toConnection(): Connection {
        // Small, strictly-alternating frames are exactly what Nagle's algorithm punishes.
        tcpNoDelay = true
        return FramedConnection(getInputStream(), getOutputStream(), closer = { runCatching { close() } })
    }
}
