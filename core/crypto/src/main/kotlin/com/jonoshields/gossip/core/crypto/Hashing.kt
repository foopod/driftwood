package com.jonoshields.gossip.core.crypto

import java.security.MessageDigest

/**
 * SHA-256 over a canonical preimage produces a message id (plan.md §3.2).
 *
 * MessageDigest instances are stateful and not thread-safe, so a fresh one is taken per
 * call rather than cached in a field.
 */
fun sha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

/** Length of a SHA-256 digest, and therefore of every message id. */
const val HASH_LENGTH: Int = 32
