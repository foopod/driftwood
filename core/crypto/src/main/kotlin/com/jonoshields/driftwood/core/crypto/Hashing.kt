package com.jonoshields.driftwood.core.crypto

import java.security.MessageDigest

/** SHA-256; a fresh `MessageDigest` per call since instances aren't thread-safe. */
fun sha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

/** Length of a SHA-256 digest, and therefore of every message id. */
const val HASH_LENGTH: Int = 32
