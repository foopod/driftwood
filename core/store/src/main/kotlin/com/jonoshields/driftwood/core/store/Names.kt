package com.jonoshields.driftwood.core.store

import com.jonoshields.driftwood.core.model.AuthorId

/** How long a directory row survives without a post, deliberately longer than the message window. */
const val DIRECTORY_TTL_MILLIS: Long = 180L * 24 * 60 * 60 * 1000

/** How an identity should be shown; [verified] means confirmed by sync, QR, or naming — the only kind trusted without a fingerprint. */
data class DisplayName(
    val label: String?,
    val fingerprint: String,
    val verified: Boolean,
    /** Chip hue for a claimed (unverified) name; meaningless once [verified]. */
    val hue: Float,
) {
    /** A claimed name never appears without its fingerprint, since two keys claiming the same name is expected. */
    val text: String
        get() = when {
            label == null -> fingerprint
            verified -> label
            else -> "$label · $fingerprint"
        }
}

object NameResolver {

    /** Short, stable, and drawn from the key itself — nothing about it is claimable. */
    fun fingerprint(author: AuthorId): String {
        val hex = author.toHex()
        return "${hex.take(4)}…${hex.takeLast(4)}"
    }

    /** [nickname] wins over [username]; [confirmed] defaults to `nickname != null` unless a caller knows better. */
    fun resolve(
        author: AuthorId,
        nickname: String?,
        username: String?,
        confirmed: Boolean = nickname != null,
    ): DisplayName {
        val fingerprint = fingerprint(author)
        val hue = hue(author)
        val label = nickname ?: username
        return DisplayName(label, fingerprint, verified = confirmed, hue = hue)
    }

    /** An at-a-glance colour, not a defence — a few dozen throwaway keypairs can match any hue. */
    fun hue(author: AuthorId): Float {
        val bytes = author.toByteArray()
        val value = ((bytes[8].toInt() and 0xFF) shl 8) or (bytes[9].toInt() and 0xFF)
        return value * 360f / 65_536f
    }
}

/** One row of the name directory — a cache of other people's claims. */
data class DirectoryEntry(
    val author: AuthorId,
    val username: String,
    val lastSeenPost: Long,
)

/** Ages names out of the directory; kept past the messages that introduced them, so pruned threads still read as people. */
object DirectoryPruner {

    fun plan(
        entries: List<DirectoryEntry>,
        listen: Set<AuthorId>,
        contacts: Set<AuthorId>,
        authorsHeld: Set<AuthorId>,
        blockedAuthors: Set<AuthorId>,
        ttlMillis: Long = DIRECTORY_TTL_MILLIS,
        nowMillis: Long,
    ): Set<AuthorId> = entries.filter { entry ->
        when {
            // Blocked wins over every reason to keep a name, as it does for content.
            entry.author in blockedAuthors -> true
            // A name you deliberately follow is the last one you would want to lose.
            entry.author in listen -> false
            entry.author in contacts -> false
            entry.author in authorsHeld -> false
            else -> entry.lastSeenPost < nowMillis - ttlMillis
        }
    }.mapTo(mutableSetOf()) { it.author }
}
