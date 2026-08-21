package com.jonoshields.gossip.core.sync

import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.random.Random
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipeTest {

    @Test
    fun `frames arrive whole and in order`() = runTest {
        val (a, b) = Pipe.open(capacity = 8)
        val sent = (1..5).map { ByteArray(it) { byte -> byte.toByte() } }

        sent.forEach { a.send(it) }
        sent.forEach { assertArrayEquals(it, b.receive()) }
    }

    @Test
    fun `closing one end ends the other's reads`() = runTest {
        val (a, b) = Pipe.open()
        a.send(byteArrayOf(1))
        a.close()

        assertArrayEquals(byteArrayOf(1), b.receive())
        assertNull("a closed pipe reads as end-of-stream, not an error", b.receive())
    }

    @Test
    fun `a bounded pipe applies back-pressure, as a socket would`() = runTest {
        // The whole reason not to use an unlimited channel: a protocol that deadlocks under
        // back-pressure must fail here rather than first on real hardware.
        val (a, b) = Pipe.open(capacity = 1)
        var sentCount = 0

        val writer = launch {
            repeat(5) { a.send(byteArrayOf(it.toByte())); sentCount++ }
        }

        // The writer cannot get far ahead of the reader.
        assertTrue("writer ran away with an unbounded buffer: $sentCount", sentCount <= 2)
        repeat(5) { b.receive() }
        writer.join()
        assertEquals(5, sentCount)
    }

    @Test(timeout = 5_000)
    fun `both sides writing before either reads will deadlock, as on a real socket`() {
        // This is the failure the session's turn-taking exists to prevent, and the reason the
        // pipe is bounded: an unlimited channel would let this pass here and fail first on
        // real hardware in M3a.
        val (a, b) = Pipe.open(capacity = 1)
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)

        val thread = Thread {
            runBlocking {
                launch { repeat(10) { a.send(byteArrayOf(1)) } }
                launch { repeat(10) { b.send(byteArrayOf(2)) } }
            }
            finished.set(true)
        }
        thread.isDaemon = true
        thread.start()
        thread.join(1_000)

        assertTrue("both sides writing without reading must block", !finished.get())
        thread.interrupt()
    }

    @Test
    fun `both directions are independent`() = runTest {
        val (a, b) = Pipe.open(capacity = 4)
        a.send(byteArrayOf(1))
        b.send(byteArrayOf(2))

        assertArrayEquals(byteArrayOf(1), b.receive())
        assertArrayEquals(byteArrayOf(2), a.receive())
    }
}

/**
 * These do real blocking I/O on real dispatchers, so they use `runBlocking` and a wall-clock
 * JUnit timeout rather than `runTest`. Under `runTest` the virtual clock jumps the moment the
 * read suspends onto `Dispatchers.IO`, so any `withTimeout` fires instantly regardless of
 * whether the read would have succeeded — the test-clock-versus-wall-clock trap.
 */
class FramedConnectionTest {

    /** Wires a FramedConnection to itself through a real byte pipe, so deframing is exercised. */
    private fun loopback(): FramedConnection {
        val input = PipedInputStream(1 shl 16)
        val output = PipedOutputStream(input)
        return FramedConnection(input, output)
    }

    @Test(timeout = 5_000)
    fun `a frame written as bytes reads back whole`() = runBlocking {
        val connection = loopback()
        val frame = FrameCodec.encode(Record.Hello(1))

        connection.send(frame)

        assertArrayEquals(frame, connection.receive())
    }

    @Test(timeout = 5_000)
    fun `frames are reassembled from a stream that does not respect boundaries`() = runBlocking {
        // A socket delivers bytes, not messages: a read can return half a frame or two.
        val connection = loopback()
        val first = FrameCodec.encode(Record.Hello(1))
        val second = FrameCodec.encode(Record.HashList((1..40).mapTo(mutableSetOf()) { msgId(it) }))

        connection.send(first + second)

        assertArrayEquals(first, connection.receive())
        assertArrayEquals(second, connection.receive())
    }

    @Test(timeout = 5_000)
    fun `an unusable declared length closes the link instead of allocating`() = runBlocking {
        val connection = loopback()
        // 0xFFFFFFFF reads as -1 in a signed int and would otherwise ask for a negative array.
        connection.send(byteArrayOf(0x03, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))

        val thrown = runCatching { connection.receive() }.exceptionOrNull()
        assertTrue("$thrown", thrown is ConnectionClosed)
    }

    @Test(timeout = 5_000)
    fun `an oversized declared length closes the link`() = runBlocking {
        val connection = loopback()
        val tooBig = java.nio.ByteBuffer.allocate(4).putInt(MAX_FRAME_BYTES + 1).array()
        connection.send(byteArrayOf(0x03) + tooBig)

        val thrown = runCatching { connection.receive() }.exceptionOrNull()
        assertTrue("$thrown", thrown is ConnectionClosed)
    }

    @Test(timeout = 5_000)
    fun `a stream that stops mid-frame is an error, not a silent short read`() = runBlocking {
        val input = PipedInputStream(1 shl 16)
        val output = PipedOutputStream(input)
        val connection = FramedConnection(input, output)

        // A header promising 64 bytes, followed by 10 and then end-of-stream.
        connection.send(byteArrayOf(0x04) + java.nio.ByteBuffer.allocate(4).putInt(64).array())
        connection.send(ByteArray(10))
        output.close()

        val thrown = runCatching { connection.receive() }.exceptionOrNull()
        assertTrue("$thrown", thrown is ConnectionClosed)
    }

    @Test(timeout = 5_000)
    fun `a clean end of stream reads as null`() = runBlocking {
        val input = PipedInputStream(1 shl 16)
        val output = PipedOutputStream(input)
        val connection = FramedConnection(input, output)
        output.close()

        assertNull(connection.receive())
    }

    @Test(timeout = 5_000)
    fun `arbitrary frames survive the byte stream unchanged`() = runBlocking {
        val connection = loopback()
        val random = Random(20260821)
        repeat(200) {
            val payload = ByteArray(random.nextInt(1, 500)) { random.nextInt(256).toByte() }
            val frame = FrameCodec.encode(Record.Message(payload))
            connection.send(frame)
            assertArrayEquals(frame, connection.receive())
        }
    }
}
