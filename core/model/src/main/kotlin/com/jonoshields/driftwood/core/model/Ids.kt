package com.jonoshields.driftwood.core.model

import com.jonoshields.driftwood.core.crypto.Hex

/** Length of both a message id (a SHA-256 digest) and an author id (an Ed25519 public key). */
const val ID_LENGTH: Int = 32

/** Unsigned byte comparison — Kotlin's signed [Byte] would sort 0x80..0xFF below 0x00. */
internal fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
    val shared = minOf(a.size, b.size)
    for (i in 0 until shared) {
        val difference = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
        if (difference != 0) return difference
    }
    return a.size - b.size
}

/** The content hash of a message; wraps [ByteArray] since raw-array equality is identity, not content, in Kotlin. */
class MessageId private constructor(private val raw: ByteArray) : Comparable<MessageId> {

    fun toByteArray(): ByteArray = raw.copyOf()

    fun toHex(): String = Hex.encode(raw)

    internal fun unsafeBytes(): ByteArray = raw

    override fun compareTo(other: MessageId): Int = compareUnsigned(raw, other.raw)

    override fun equals(other: Any?): Boolean =
        this === other || (other is MessageId && raw.contentEquals(other.raw))

    override fun hashCode(): Int = raw.contentHashCode()

    /** Short form: enough to recognise in a log line, not enough to mistake for the id. */
    override fun toString(): String = "MessageId(${toHex().take(12)}…)"

    companion object {
        fun of(bytes: ByteArray): MessageId {
            require(bytes.size == ID_LENGTH) {
                "message id must be $ID_LENGTH bytes, was ${bytes.size}"
            }
            return MessageId(bytes.copyOf())
        }

        fun fromHex(hex: String): MessageId = of(Hex.decode(hex))
    }
}

/** An identity: an Ed25519 public key. There is no handle registry — the key is the author. */
class AuthorId private constructor(private val raw: ByteArray) : Comparable<AuthorId> {

    fun toByteArray(): ByteArray = raw.copyOf()

    fun toHex(): String = Hex.encode(raw)

    internal fun unsafeBytes(): ByteArray = raw

    override fun compareTo(other: AuthorId): Int = compareUnsigned(raw, other.raw)

    override fun equals(other: Any?): Boolean =
        this === other || (other is AuthorId && raw.contentEquals(other.raw))

    override fun hashCode(): Int = raw.contentHashCode()

    override fun toString(): String = "AuthorId(${toHex().take(12)}…)"

    companion object {
        fun of(bytes: ByteArray): AuthorId {
            require(bytes.size == ID_LENGTH) {
                "author id must be $ID_LENGTH bytes, was ${bytes.size}"
            }
            return AuthorId(bytes.copyOf())
        }

        fun fromHex(hex: String): AuthorId = of(Hex.decode(hex))
    }
}
