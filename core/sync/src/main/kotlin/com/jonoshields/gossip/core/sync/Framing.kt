package com.jonoshields.gossip.core.sync

import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.model.ID_LENGTH
import com.jonoshields.gossip.core.model.MessageId
import java.nio.ByteBuffer

/**
 * Largest payload a single frame may carry.
 *
 * Sized by the worst case that has to fit: a hash-list covering a full listen partition is
 * 65,536 ids at 32 bytes each, or 2 MiB. Four leaves headroom for the whole store at the
 * default budget without chunking, and a single bounded allocation of that size is nothing on
 * a device running Android 13.
 *
 * The number matters less than the check. A peer that declares a length is not trusted with
 * it: allocating what it claims before validating is how one small frame becomes an
 * out-of-memory.
 */
const val MAX_FRAME_BYTES: Int = 4 * 1024 * 1024

/** Why a session ended early. Sent so the other side can say something useful to its user. */
enum class AbortReason(val code: Int) {
    VERSION_MISMATCH(1),
    MALFORMED_FRAME(2),
    OUT_OF_PHASE(3),
    FRAME_TOO_LARGE(4),
    TOO_MANY_REJECTIONS(5),
    ;

    companion object {
        fun fromCode(code: Int): AbortReason? = entries.firstOrNull { it.code == code }
    }
}

/** One record on the wire (sync-spec.md §3). */
sealed interface Record {
    data class Hello(val protocolVersion: Int) : Record

    data class Scope(val declaration: ScopeDeclaration) : Record

    data class HashList(val ids: Set<MessageId>) : Record

    /**
     * A message in its §3.2 wire form, carried **opaquely** — never decoded and re-encoded in
     * transit. The receiver must hash the bytes as they arrived; re-serialising would quietly
     * repair a hostile encoding and normalise away the tampering the hash exists to catch.
     */
    class Message(wire: ByteArray) : Record {
        val wire: ByteArray = wire.copyOf()
        override fun equals(other: Any?) = other is Message && wire.contentEquals(other.wire)
        override fun hashCode() = wire.contentHashCode()
        override fun toString() = "Message(${wire.size} bytes)"
    }

    /** A profile record (§3.5), carried opaquely for the same reason. */
    class Profile(wire: ByteArray) : Record {
        val wire: ByteArray = wire.copyOf()
        override fun equals(other: Any?) = other is Profile && wire.contentEquals(other.wire)
        override fun hashCode() = wire.contentHashCode()
        override fun toString() = "Profile(${wire.size} bytes)"
    }

    data object PhaseDone : Record

    data class GossipOffer(val ids: List<MessageId>) : Record

    data class GossipRequest(val ids: List<MessageId>) : Record

    data object SessionDone : Record

    data class Abort(val reason: AbortReason) : Record
}

sealed interface FrameResult {
    data class Ok(val record: Record) : FrameResult

    /** Everything else. A peer sending this gets a session abort, never a repair attempt. */
    data class Malformed(val why: String) : FrameResult
}

/**
 * Encodes and decodes frames: `[type u8][length u32 big-endian][payload]`.
 *
 * The framing layer is shared by every transport — Wi-Fi Direct and TCP both terminate in a
 * plain socket (plan.md §7), so only discovery and connect differ later.
 *
 * Decoding is strict in the same way §3.2 is strict, and for the same reason: this is the
 * surface a hostile peer touches first. Nothing is repaired, guessed at, or partially
 * accepted.
 *
 * Id lists are carried as bare concatenated 32-byte ids with no inner count — the frame
 * length already says how many there are, and a second source of truth is a second thing that
 * can disagree.
 */
object FrameCodec {

    const val HEADER_BYTES: Int = 5

    fun encode(record: Record): ByteArray {
        val payload = encodePayload(record)
        require(payload.size <= MAX_FRAME_BYTES) {
            "frame payload ${payload.size} exceeds $MAX_FRAME_BYTES"
        }
        return ByteBuffer.allocate(HEADER_BYTES + payload.size)
            .put(typeOf(record).toByte())
            .putInt(payload.size)
            .put(payload)
            .array()
    }

    /**
     * How many payload bytes follow this 5-byte header, for a reader pulling from a stream.
     * Returns null when the header is unusable — the caller must abort rather than read on.
     */
    fun payloadLength(header: ByteArray): Int? {
        if (header.size < HEADER_BYTES) return null
        val length = ByteBuffer.wrap(header, 1, 4).int
        // Signed int: a peer sending 0xFFFFFFFF would otherwise arrive as -1 and sail past a
        // naive upper-bound check straight into a negative-size allocation.
        if (length < 0 || length > MAX_FRAME_BYTES) return null
        return length
    }

    /** Decodes a complete frame, header included. */
    fun decode(frame: ByteArray): FrameResult {
        if (frame.size < HEADER_BYTES) return malformed("frame shorter than its header")
        val declared = payloadLength(frame) ?: return malformed("declared length is unusable")
        val payload = frame.copyOfRange(HEADER_BYTES, frame.size)
        if (payload.size != declared) {
            return malformed("declared $declared payload bytes, carried ${payload.size}")
        }
        return decodePayload(frame[0].toInt() and 0xFF, payload)
    }

    // ---- payloads --------------------------------------------------------------------

    private fun encodePayload(record: Record): ByteArray = when (record) {
        is Record.Hello -> byteArrayOf(record.protocolVersion.toByte())

        is Record.Scope -> {
            val listen = concat(record.declaration.listen.map { it.toByteArray() })
            val wants = concat(record.declaration.wants.map { it.toByteArray() })
            ByteBuffer.allocate(4 + listen.size + 8 + 4 + wants.size)
                .putInt(listen.size).put(listen)
                .putLong(record.declaration.windowCutoff)
                .putInt(wants.size).put(wants)
                .array()
        }

        is Record.HashList -> concat(record.ids.map { it.toByteArray() })
        is Record.Message -> record.wire
        is Record.Profile -> record.wire
        Record.PhaseDone -> ByteArray(0)
        is Record.GossipOffer -> concat(record.ids.map { it.toByteArray() })
        is Record.GossipRequest -> concat(record.ids.map { it.toByteArray() })
        Record.SessionDone -> ByteArray(0)
        is Record.Abort -> byteArrayOf(record.reason.code.toByte())
    }

    private fun decodePayload(type: Int, payload: ByteArray): FrameResult = when (type) {
        TYPE_HELLO -> exactly(payload, 1) { FrameResult.Ok(Record.Hello(it[0].toInt() and 0xFF)) }

        TYPE_SCOPE -> decodeScope(payload)

        TYPE_HASHLIST -> ids(payload)?.let { FrameResult.Ok(Record.HashList(it.toSet())) }
            ?: malformed("hash-list is not a whole number of ids")

        // Opaque: verified later, by the code that owns the rules for it.
        TYPE_MESSAGE -> if (payload.isEmpty()) malformed("empty message") else
            FrameResult.Ok(Record.Message(payload))

        TYPE_PROFILE -> if (payload.isEmpty()) malformed("empty profile") else
            FrameResult.Ok(Record.Profile(payload))

        TYPE_PHASE_DONE -> exactly(payload, 0) { FrameResult.Ok(Record.PhaseDone) }

        TYPE_GOSSIP_OFFER -> ids(payload)?.let { FrameResult.Ok(Record.GossipOffer(it)) }
            ?: malformed("gossip offer is not a whole number of ids")

        TYPE_GOSSIP_REQUEST -> ids(payload)?.let { FrameResult.Ok(Record.GossipRequest(it)) }
            ?: malformed("gossip request is not a whole number of ids")

        TYPE_SESSION_DONE -> exactly(payload, 0) { FrameResult.Ok(Record.SessionDone) }

        TYPE_ABORT -> exactly(payload, 1) {
            AbortReason.fromCode(it[0].toInt() and 0xFF)
                ?.let { reason -> FrameResult.Ok(Record.Abort(reason)) }
                ?: malformed("unknown abort reason")
        }

        else -> malformed("unknown record type $type")
    }

    private fun decodeScope(payload: ByteArray): FrameResult {
        if (payload.size < 16) return malformed("scope too short")
        val buffer = ByteBuffer.wrap(payload)

        val listenBytes = buffer.int
        if (listenBytes < 0 || buffer.remaining() < listenBytes) return malformed("scope listen length")
        val listen = ByteArray(listenBytes).also(buffer::get)

        if (buffer.remaining() < 12) return malformed("scope truncated after listen")
        val cutoff = buffer.long
        if (cutoff < 0) return malformed("negative window cutoff")

        val wantBytes = buffer.int
        if (wantBytes < 0 || buffer.remaining() != wantBytes) return malformed("scope wants length")
        val wants = ByteArray(wantBytes).also(buffer::get)

        val listenIds = ids(listen) ?: return malformed("listen set is not a whole number of ids")
        val wantIds = ids(wants) ?: return malformed("want list is not a whole number of ids")

        return FrameResult.Ok(
            Record.Scope(
                ScopeDeclaration(
                    listen = listenIds.mapTo(mutableSetOf()) { AuthorId.of(it.toByteArray()) },
                    windowCutoff = cutoff,
                    wants = wantIds.toSet(),
                )
            )
        )
    }

    private fun ids(bytes: ByteArray): List<MessageId>? {
        if (bytes.size % ID_LENGTH != 0) return null
        return (bytes.indices step ID_LENGTH).map {
            MessageId.of(bytes.copyOfRange(it, it + ID_LENGTH))
        }
    }

    private fun concat(parts: List<ByteArray>): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var offset = 0
        parts.forEach { it.copyInto(out, offset); offset += it.size }
        return out
    }

    private inline fun exactly(payload: ByteArray, size: Int, build: (ByteArray) -> FrameResult) =
        if (payload.size == size) build(payload) else malformed("expected $size payload bytes, got ${payload.size}")

    private fun malformed(why: String) = FrameResult.Malformed(why)

    private fun typeOf(record: Record): Int = when (record) {
        is Record.Hello -> TYPE_HELLO
        is Record.Scope -> TYPE_SCOPE
        is Record.HashList -> TYPE_HASHLIST
        is Record.Message -> TYPE_MESSAGE
        is Record.Profile -> TYPE_PROFILE
        Record.PhaseDone -> TYPE_PHASE_DONE
        is Record.GossipOffer -> TYPE_GOSSIP_OFFER
        is Record.GossipRequest -> TYPE_GOSSIP_REQUEST
        Record.SessionDone -> TYPE_SESSION_DONE
        is Record.Abort -> TYPE_ABORT
    }

    private const val TYPE_HELLO = 0x01
    private const val TYPE_SCOPE = 0x02
    private const val TYPE_HASHLIST = 0x03
    private const val TYPE_MESSAGE = 0x04
    private const val TYPE_PROFILE = 0x05
    private const val TYPE_PHASE_DONE = 0x06
    private const val TYPE_GOSSIP_OFFER = 0x07
    private const val TYPE_GOSSIP_REQUEST = 0x08
    private const val TYPE_SESSION_DONE = 0x09
    private const val TYPE_ABORT = 0x0A
}
