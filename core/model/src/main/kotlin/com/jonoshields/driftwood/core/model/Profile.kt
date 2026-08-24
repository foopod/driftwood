package com.jonoshields.driftwood.core.model

import com.jonoshields.driftwood.core.crypto.Ed25519
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Profile format version. */
const val PROFILE_FORMAT_VERSION: Int = 1

/** A signed claim ("this key calls itself this"), keyed by [author] and mutable — the latest claim wins. */
class Profile internal constructor(
    val author: AuthorId,
    val username: String,
    val timestampMillis: Long,
    signature: ByteArray,
    val version: Int = PROFILE_FORMAT_VERSION,
) {
    private val rawSignature = signature.copyOf()

    val signature: ByteArray get() = rawSignature.copyOf()

    internal fun unsafeSignature(): ByteArray = rawSignature

    override fun toString(): String = "Profile(${author.toHex().take(8)}… = \"$username\")"

    companion object {
        /** Assembles without checking the signature. For the verifier and for fixtures. */
        fun unverified(
            author: AuthorId,
            username: String,
            timestampMillis: Long,
            signature: ByteArray,
            version: Int = PROFILE_FORMAT_VERSION,
        ) = Profile(author, username, timestampMillis, signature, version)
    }
}

sealed interface ProfileVerifyResult {
    data class Valid(val profile: Profile) : ProfileVerifyResult
    data class Rejected(val reason: RejectionReason) : ProfileVerifyResult
}

/** Profile wire form: `sig || preimage` of `v, author, username, timestamp` — no id, since profiles are keyed by author. */
object ProfileCodec {

    private const val VERSION_FIELD_LENGTH = 1
    private const val TIMESTAMP_FIELD_LENGTH = 8
    const val SIGNATURE_OFFSET: Int = 0
    const val PREIMAGE_OFFSET: Int = Ed25519.SIGNATURE_LENGTH

    fun encodePreimage(
        author: AuthorId,
        username: String,
        timestampMillis: Long,
        version: Int = PROFILE_FORMAT_VERSION,
    ): ByteArray {
        val nameBytes = username.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(
            2 + VERSION_FIELD_LENGTH + 2 + ID_LENGTH + 2 + nameBytes.size + 2 + TIMESTAMP_FIELD_LENGTH
        )
        buffer.putShort(VERSION_FIELD_LENGTH.toShort())
        buffer.put(version.toByte())
        buffer.putShort(ID_LENGTH.toShort())
        buffer.put(author.unsafeBytes())
        buffer.putShort(nameBytes.size.toShort())
        buffer.put(nameBytes)
        buffer.putShort(TIMESTAMP_FIELD_LENGTH.toShort())
        buffer.putLong(timestampMillis)
        return buffer.array()
    }

    fun encode(profile: Profile): ByteArray =
        profile.unsafeSignature() + encodePreimage(
            profile.author, profile.username, profile.timestampMillis, profile.version
        )

    fun create(author: AuthorId, rawUsername: String, timestampMillis: Long, signer: Signer): Profile {
        val username = Username.validate(rawUsername).getOrThrow()
        require(timestampMillis >= 0) { "timestamp must not be negative" }
        val preimage = encodePreimage(author, username, timestampMillis)
        return Profile(author, username, timestampMillis, signer.sign(preimage))
    }

    /** Same order as §3.2: decode, structural checks, then the expensive signature check. */
    fun verify(wire: ByteArray): ProfileVerifyResult {
        if (wire.size < PREIMAGE_OFFSET) {
            return rejected(RejectionReason.Malformed("profile shorter than its signature"))
        }
        val signature = wire.copyOfRange(SIGNATURE_OFFSET, PREIMAGE_OFFSET)
        val preimage = wire.copyOfRange(PREIMAGE_OFFSET, wire.size)

        val reader = FieldReader(preimage)
        val version = reader.next(VERSION_FIELD_LENGTH) ?: return malformed("version field")
        val author = reader.next(ID_LENGTH) ?: return malformed("author field")
        val nameBytes = reader.next() ?: return malformed("username field")
        val timestamp = reader.next(TIMESTAMP_FIELD_LENGTH) ?: return malformed("timestamp field")
        if (reader.hasRemaining()) return malformed("${reader.remaining()} trailing bytes")

        val versionValue = version[0].toInt() and 0xFF
        if (versionValue != PROFILE_FORMAT_VERSION) return malformed("unknown version $versionValue")

        val timestampValue = ByteBuffer.wrap(timestamp).long
        if (timestampValue < 0) return malformed("negative timestamp")

        val name = try {
            decodeStrictUtf8(nameBytes)
        } catch (e: CharacterCodingException) {
            return malformed("username is not valid UTF-8")
        }
        // Repairing the name here would change the bytes the signature was made over.
        val validated = Username.validate(name)
        if (validated.isFailure || validated.getOrThrow() != name) {
            return malformed("username is not acceptable as sent")
        }

        val authorId = AuthorId.of(author)
        if (!Ed25519.verify(preimage, signature, author)) {
            return rejected(RejectionReason.BadSignature)
        }
        return ProfileVerifyResult.Valid(
            Profile.unverified(authorId, name, timestampValue, signature, versionValue)
        )
    }

    private fun malformed(reason: String) = rejected(RejectionReason.Malformed(reason))
    private fun rejected(reason: RejectionReason) = ProfileVerifyResult.Rejected(reason)

    private fun decodeStrictUtf8(bytes: ByteArray): String =
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    private class FieldReader(private val bytes: ByteArray) {
        private var offset = 0
        fun hasRemaining() = offset < bytes.size
        fun remaining() = bytes.size - offset
        fun next(): ByteArray? {
            if (remaining() < 2) return null
            val length = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
            offset += 2
            if (remaining() < length) return null
            return bytes.copyOfRange(offset, offset + length).also { offset += length }
        }
        fun next(exactly: Int): ByteArray? = next()?.takeIf { it.size == exactly }
    }
}
