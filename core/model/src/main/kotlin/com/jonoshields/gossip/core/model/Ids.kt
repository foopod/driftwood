package com.jonoshields.gossip.core.model

import com.jonoshields.gossip.core.crypto.Hex

/** Length of both a message id (a SHA-256 digest) and an author id (an Ed25519 public key). */
const val ID_LENGTH: Int = 32

/**
 * Unsigned lexicographic comparison, as specified for the ordering tiebreak in
 * plan.md §3.2. Kotlin's [Byte] is signed, so comparing bytes directly would sort
 * 0x80..0xFF *below* 0x00 and two devices could disagree on order.
 */
internal fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
    val shared = minOf(a.size, b.size)
    for (i in 0 until shared) {
        val difference = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
        if (difference != 0) return difference
    }
    return a.size - b.size
}

/**
 * The content hash of a message, and the identifier a thread is known by.
 *
 * A wrapper rather than a raw [ByteArray] because array equality in Kotlin is *identity*:
 * `data class Message(val id: ByteArray)` silently gets an equals that is wrong for a
 * content-addressed system, where ids are set members, map keys and diff units.
 */
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

/**
 * An identity: an Ed25519 public key. There is no handle registry — the key *is* the
 * author (plan.md §3.1). A local display name is cosmetic and lives elsewhere.
 */
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
