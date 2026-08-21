package com.jonoshields.gossip.core.store

import com.jonoshields.gossip.core.model.AuthorId

/** plan.md §3.4: `DIRECTORY_TTL`. Longer than the message window on purpose. */
const val DIRECTORY_TTL_MILLIS: Long = 180L * 24 * 60 * 60 * 1000

/**
 * How an identity should be shown (plan.md §3.1, §6).
 *
 * [verified] means *you* bound this name to this key — a petname from your contacts. It is
 * the only kind of name that can be trusted, and the only kind shown without a fingerprint.
 */
data class DisplayName(
    val label: String?,
    val fingerprint: String,
    val verified: Boolean,
) {
    /**
     * What actually goes on screen. A claimed name never appears without its fingerprint,
     * because two keys claiming the same name is the expected case, not an anomaly — the
     * fingerprint is what keeps that merely confusing rather than dangerous.
     */
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

    /**
     * [petname] is what you called them; [claimed] is what they call themselves. A petname
     * always wins, and where there is neither, the key stands for itself.
     */
    fun resolve(author: AuthorId, petname: String?, claimed: String?): DisplayName {
        val fingerprint = fingerprint(author)
        return when {
            petname != null -> DisplayName(petname, fingerprint, verified = true)
            claimed != null -> DisplayName(claimed, fingerprint, verified = false)
            else -> DisplayName(null, fingerprint, verified = false)
        }
    }
}

/** One row of the name directory — a cache of other people's claims (plan.md §3.3). */
data class DirectoryEntry(
    val author: AuthorId,
    val nickname: String,
    val lastSeenPost: Long,
)

/**
 * Ages names out of the directory (plan.md §4).
 *
 * Not fair-share: the directory is a cache, and losing a row costs readability rather than
 * integrity. Names are deliberately kept *past* the messages that introduced them, so a
 * partly-pruned thread still reads as people talking rather than as hex.
 */
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
