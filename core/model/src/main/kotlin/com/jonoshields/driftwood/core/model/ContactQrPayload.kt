package com.jonoshields.driftwood.core.model

/** The "Add contact" QR payload — the key alone, never a username, since only a signed profile record can be trusted for that. */
object ContactQrPayload {
    private const val PREFIX = "gossip:"

    fun encode(author: AuthorId): String = PREFIX + author.toHex()

    /** Null for anything that isn't one of ours — a mis-scan or unrelated code, not an error. */
    fun decode(payload: String): AuthorId? {
        if (!payload.startsWith(PREFIX)) return null
        return runCatching { AuthorId.fromHex(payload.removePrefix(PREFIX)) }.getOrNull()
    }
}
