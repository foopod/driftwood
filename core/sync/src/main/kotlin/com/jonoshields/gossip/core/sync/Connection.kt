package com.jonoshields.gossip.core.sync

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * An open link, carrying whole frames. Closing it mid-session is an expected outcome.
 *
 * How two devices reach each other differs per radio; the sync engine talks only to this and
 * assumes nothing beyond a reliable, ordered, bidirectional byte stream — no latency, no
 * bandwidth, no promise the link survives (plan.md §7). BLE and LoRa are slow and flaky, and
 * the protocol is already shaped for that: the priority phase is independently valid, so a
 * link that dies early still did something useful.
 */
interface Connection : AutoCloseable {
    suspend fun send(frame: ByteArray)

    /** The next whole frame, or null once the peer has closed. */
    suspend fun receive(): ByteArray?
}

/** The peer hung up, or sent something that cannot be read as a frame. */
class ConnectionClosed(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Turns a byte stream into a frame stream.
 *
 * Wi-Fi Direct and a shared-LAN socket both end in exactly this (plan.md §7), so the
 * deframing is written once here rather than twice later. The only radio-specific work left
 * for M3a and M3b is discovery and connect.
 *
 * Reads are strict about length before they are generous with memory: the header is checked
 * through [FrameCodec.payloadLength], which refuses anything above the cap *or below zero*,
 * so a peer never gets to choose the size of an allocation.
 *
 * A real socket fails loudly — `IOException` ("connection reset", "broken pipe") — where the
 * [Pipe] mock only ever closes quietly. Both `send` and `receive` normalise that into
 * [ConnectionClosed], the same outcome an orderly close already produces, so [Session] stays
 * ignorant of the transport underneath it and does not need a socket-specific catch clause.
 */
class FramedConnection(
    private val input: InputStream,
    private val output: OutputStream,
    private val closer: () -> Unit = {},
) : Connection {

    // A frame must not interleave with another, and the session is turn-based anyway; the
    // lock is here so a stray concurrent send corrupts a test rather than a real link.
    private val sending = Mutex()

    override suspend fun send(frame: ByteArray) = withContext(Dispatchers.IO) {
        try {
            sending.withLock {
                output.write(frame)
                output.flush()
            }
        } catch (e: IOException) {
            throw ConnectionClosed("write failed", e)
        }
    }

    override suspend fun receive(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val header = readFully(FrameCodec.HEADER_BYTES) ?: return@withContext null
            val length = FrameCodec.payloadLength(header)
                ?: throw ConnectionClosed("peer declared an unusable frame length")
            val payload = readFully(length)
                ?: throw ConnectionClosed("stream ended $length bytes into a frame")
            header + payload
        } catch (e: IOException) {
            throw ConnectionClosed("read failed", e)
        }
    }

    override fun close() = closer()

    private fun readFully(count: Int): ByteArray? {
        if (count == 0) return ByteArray(0)
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(buffer, read, count - read)
            if (n < 0) return if (read == 0) null else throw ConnectionClosed("truncated frame")
            read += n
        }
        return buffer
    }
}
