package com.jonoshields.driftwood.core.identity

/** Encrypts the seed at rest; an interface so the identity lifecycle can be tested on the JVM without a real Keystore. */
interface SeedCipher {
    /** Returns a self-contained blob — everything needed to [open] it again except the key. */
    fun seal(plaintext: ByteArray): ByteArray

    fun open(sealed: ByteArray): ByteArray
}

/** Where the sealed identity record lives. */
interface SeedStorage {
    fun read(): ByteArray?
    fun write(record: ByteArray)
    fun clear()
}
