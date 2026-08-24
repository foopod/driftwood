package com.jonoshields.driftwood.core.sync

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** An open link carrying whole frames; the sync engine assumes nothing beyond a reliable, ordered, bidirectional byte stream. */
interface Connection : AutoCloseable {
    suspend fun send(frame: ByteArray)

    /** The next whole frame, or null once the peer has closed. */
    suspend fun receive(): ByteArray?
}

/** The peer hung up, or sent something that cannot be read as a frame. */
class ConnectionClosed(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Turns a byte stream into a frame stream; normalises `IOException` into [ConnectionClosed] so [Session] stays transport-agnostic. */
class FramedConnection(
    private val input: InputStream,
    private val output: OutputStream,
    private val closer: () -> Unit = {},
) : Connection {

    // The session is turn-based anyway; this just makes a stray concurrent send fail loudly rather than corrupt the stream.
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
