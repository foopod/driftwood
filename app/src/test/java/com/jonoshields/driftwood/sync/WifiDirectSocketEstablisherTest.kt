package com.jonoshields.driftwood.sync

import com.jonoshields.driftwood.core.sync.FrameCodec
import com.jonoshields.driftwood.core.sync.FrameResult
import com.jonoshields.driftwood.core.sync.Record
import java.io.IOException
import java.net.InetAddress
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real localhost sockets testing the group-owner/non-owner handshake and retry/backoff; uses [runBlocking] since the retry timing races real I/O against real delays. */
class WifiDirectSocketEstablisherTest {

    private val loopback = InetAddress.getByName("127.0.0.1")

    // Generous but finite bound so a hung read fails the test instead of hanging indefinitely.
    @Test(timeout = 15_000)
    fun `a group owner listener and a connecting client exchange frames over a real socket`() = runBlocking {
        coroutineScope {
            val listener = WifiDirectSocketEstablisher.GroupOwnerListener(port = 0)
            try {
                val server = async { listener.accept() }
                val client = WifiDirectSocketEstablisher.connectToGroupOwner(loopback, listener.port)
                val serverConnection = server.await()

                try {
                    // A real record round trip proves the handshake produced a working Connection.
                    client.send(FrameCodec.encode(Record.SessionDone))
                    val received = serverConnection.receive()
                    assertTrue(received != null)
                    assertEquals(Record.SessionDone, (FrameCodec.decode(received!!) as FrameResult.Ok).record)

                    serverConnection.send(FrameCodec.encode(Record.SessionDone))
                    val reply = client.receive()
                    assertTrue(reply != null)
                    assertEquals(Record.SessionDone, (FrameCodec.decode(reply!!) as FrameResult.Ok).record)
                } finally {
                    client.close()
                    serverConnection.close()
                }
            } finally {
                listener.close()
            }
        }
    }

    @Test(timeout = 15_000)
    fun `the client retries through the settling window before the group owner has bound`() = runBlocking {
        // Fixed port (not ephemeral 0) since the client must know it before the listener binds late.
        val port = 47_988
        coroutineScope {
            val server = async {
                delay(1_200)
                val listener = WifiDirectSocketEstablisher.GroupOwnerListener(port)
                try {
                    listener.accept()
                } finally {
                    listener.close()
                }
            }

            val client = WifiDirectSocketEstablisher.connectToGroupOwner(loopback, port)
            client.close()
            server.await().close()
        }
    }

    @Test(expected = IOException::class, timeout = 15_000)
    fun `the client gives up after exhausting its retries against nothing listening`(): Unit = runBlocking {
        // Loopback connections to a closed port fail almost immediately (ECONNREFUSED), so
        // this exercises every retry attempt without taking seconds of wall-clock time.
        WifiDirectSocketEstablisher.connectToGroupOwner(loopback, port = 47_989)
    }
}
