package com.jonoshields.gossip.core.model

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Outcome of decoding a canonical preimage. */
sealed interface BodyDecodeResult {
    data class Success(val body: MessageBody) : BodyDecodeResult
    data class Malformed(val reason: String) : BodyDecodeResult
}

/**
 * The canonical preimage encoding (plan.md §3.2) — the foundation everything else rests
 * on. Two independent implementations must agree byte-for-byte, so the format is fixed:
 *
 *   fields in order: v, author, root, parent, timestamp, text
 *   each field: uint16 big-endian byte length, then exactly that many bytes
 *   v is one byte; timestamp is eight bytes big-endian; absent root/parent are length 0
 *   text is NFC-normalised UTF-8
 *   id and sig are not present
 *
 * Decoding is strict: it rejects rather than repairs. A decoder is an attack surface, and
 * permissiveness here is what turns a format bug into a security bug.
 */
object CanonicalCodec {

    private const val VERSION_FIELD_LENGTH = 1
    private const val TIMESTAMP_FIELD_LENGTH = 8
    private const val MAX_FIELD_LENGTH = 0xFFFF

    fun encode(body: MessageBody): ByteArray {
        val textBytes = body.text.toByteArray(StandardCharsets.UTF_8)
        val rootBytes = body.root?.unsafeBytes() ?: EMPTY
        val parentBytes = body.parent?.unsafeBytes() ?: EMPTY

        val totalLength = 2 + VERSION_FIELD_LENGTH +
            2 + ID_LENGTH +
            2 + rootBytes.size +
            2 + parentBytes.size +
            2 + TIMESTAMP_FIELD_LENGTH +
            2 + textBytes.size

        val buffer = ByteBuffer.allocate(totalLength) // ByteBuffer is big-endian by default

        buffer.putShort(VERSION_FIELD_LENGTH.toShort())
        buffer.put(body.version.toByte())

        buffer.putShort(ID_LENGTH.toShort())
        buffer.put(body.author.unsafeBytes())

        buffer.putShort(rootBytes.size.toShort())
        buffer.put(rootBytes)

        buffer.putShort(parentBytes.size.toShort())
        buffer.put(parentBytes)

        buffer.putShort(TIMESTAMP_FIELD_LENGTH.toShort())
        buffer.putLong(body.timestampMillis)

        require(textBytes.size <= MAX_FIELD_LENGTH) { "text too long to encode" }
        buffer.putShort(textBytes.size.toShort())
        buffer.put(textBytes)

        return buffer.array()
    }

    fun decode(bytes: ByteArray): BodyDecodeResult {
        val reader = FieldReader(bytes)

        val version = reader.nextExactly(VERSION_FIELD_LENGTH)
            ?: return malformed("version field")
        val author = reader.nextExactly(ID_LENGTH)
            ?: return malformed("author field")
        val root = reader.nextEmptyOr(ID_LENGTH)
            ?: return malformed("root field")
        val parent = reader.nextEmptyOr(ID_LENGTH)
            ?: return malformed("parent field")
        val timestamp = reader.nextExactly(TIMESTAMP_FIELD_LENGTH)
            ?: return malformed("timestamp field")
        val textBytes = reader.next()
            ?: return malformed("text field")

        if (reader.hasRemaining()) {
            return malformed("${reader.remaining()} trailing bytes after text")
        }

        val text = try {
            decodeStrictUtf8(textBytes)
        } catch (e: CharacterCodingException) {
            return malformed("text is not valid UTF-8")
        }

        // MessageBody enforces the remaining structural rules — version, non-negative
        // timestamp, well-formed and normalised text, length cap — and throws if any
        // fails. Translate that into a rejection rather than letting it escape: this runs
        // on bytes from an untrusted peer, where malformed input is expected, not
        // exceptional.
        return try {
            BodyDecodeResult.Success(
                MessageBody(
                    version = version[0].toInt() and 0xFF,
                    author = AuthorId.of(author),
                    root = if (root.isEmpty()) null else MessageId.of(root),
                    parent = if (parent.isEmpty()) null else MessageId.of(parent),
                    timestampMillis = ByteBuffer.wrap(timestamp).long,
                    text = text,
                )
            )
        } catch (e: IllegalArgumentException) {
            malformed(e.message ?: "failed validation")
        }
    }

    private fun malformed(reason: String) = BodyDecodeResult.Malformed(reason)

    private val EMPTY = ByteArray(0)

    private fun decodeStrictUtf8(bytes: ByteArray): String =
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    /**
     * Walks length-prefixed fields, returning null the moment anything does not fit.
     * Every read is bounds-checked against what is actually there, never against what a
     * length prefix claims.
     */
    private class FieldReader(private val bytes: ByteArray) {
        private var offset = 0

        fun hasRemaining(): Boolean = offset < bytes.size

        fun remaining(): Int = bytes.size - offset

        fun next(): ByteArray? {
            if (remaining() < 2) return null
            val length = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
            offset += 2
            if (remaining() < length) return null
            val field = bytes.copyOfRange(offset, offset + length)
            offset += length
            return field
        }

        /** A field that must be exactly this wide — v, author, timestamp. */
        fun nextExactly(width: Int): ByteArray? = next()?.takeIf { it.size == width }

        /** A field that is either absent or a full id — root, parent. */
        fun nextEmptyOr(width: Int): ByteArray? =
            next()?.takeIf { it.isEmpty() || it.size == width }
    }
}
