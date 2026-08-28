package com.jonoshields.driftwood.core.sync

import com.jonoshields.driftwood.core.store.Clock
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The protocol over real localhost sockets rather than the [Pipe] mock's in-memory channels — one layer more real, and needs no device. */
class TcpTransportTest {

    @Test
    fun `a full session converges over real localhost sockets`() = runTest {
        val hers = alice.root("hello over a real socket", NOW - 1000)
        val aliceStore = InMemorySyncStore().seed(hers)
        val bobStore = InMemorySyncStore().follow(alice.key)
        val clock = Clock.fixed(NOW)

        val listener = TcpTransport.Listener()
        try {
            coroutineScope {
                val responder = async {
                    val connection = listener.accept()
                    try {
                        Session(bobStore, clock).run(Role.RESPONDER, connection, responderDevice)
                    } finally {
                        connection.close()
                    }
                }
                val initiator = async {
                    val connection = TcpTransport.connect("127.0.0.1", listener.port)
                    try {
                        Session(aliceStore, clock).run(Role.INITIATOR, connection, initiatorDevice)
                    } finally {
                        connection.close()
                    }
                }

                val initiatorResult = initiator.await()
                val responderResult = responder.await()

                assertTrue("$initiatorResult", initiatorResult is SessionResult.Completed)
                assertTrue("$responderResult", responderResult is SessionResult.Completed)
            }
        } finally {
            listener.close()
        }

        assertTrue("bob should hold alice's message after a real-socket sync", bobStore.holds(hers.id))
    }

    @Test
    fun `a connection reset mid-session surfaces as PEER_CLOSED, not a crash`() = runTest {
        val listener = TcpTransport.Listener()
        try {
            coroutineScope {
                // What a dropped radio or a killed app looks like on a real socket: an
                // abortive reset (RST) rather than the orderly close (FIN) every other test
                // in this suite exercises via Pipe or TcpTransport's own close(). SO_LINGER 0
                // is what turns close() from a FIN into an RST.
                val rawPeer = async(Dispatchers.IO) {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", listener.port))
                        val connection = FramedConnection(socket.getInputStream(), socket.getOutputStream())
                        connection.send(FrameCodec.encode(Record.Hello(PROTOCOL_VERSION, scriptedPeerDevice)))
                        connection.receive()
                        socket.setSoLinger(true, 0)
                    }
                }

                val result = Session(InMemorySyncStore(), Clock.fixed(NOW))
                    .run(Role.RESPONDER, listener.accept(), responderDevice)

                rawPeer.await()
                assertTrue("$result", result is SessionResult.Aborted)
                assertEquals(AbortReason.PEER_CLOSED, (result as SessionResult.Aborted).reason)
            }
        } finally {
            listener.close()
        }
    }
}
