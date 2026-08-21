package com.jonoshields.gossip.core.sync

import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * How two devices reach each other. Discovery and connection differ per radio; everything
 * above this line does not (plan.md §7).
 *
 * The sync engine assumes nothing beyond a reliable, ordered, bidirectional byte stream — no
 * latency, no bandwidth, no promise the link survives. BLE and LoRa are slow and flaky, and
 * the protocol is already shaped for that: the priority phase is independently valid, so a
 * link that dies early still did something useful.
 */
interface Transport {
    suspend fun connect(): Connection
}

/** An open link, carrying whole frames. Closing it mid-session is an expected outcome. */
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
        sending.withLock {
            output.write(frame)
            output.flush()
        }
    }

    override suspend fun receive(): ByteArray? = withContext(Dispatchers.IO) {
        val header = readFully(FrameCodec.HEADER_BYTES) ?: return@withContext null
        val length = FrameCodec.payloadLength(header)
            ?: throw ConnectionClosed("peer declared an unusable frame length")
        val payload = readFully(length)
            ?: throw ConnectionClosed("stream ended $length bytes into a frame")
        header + payload
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
