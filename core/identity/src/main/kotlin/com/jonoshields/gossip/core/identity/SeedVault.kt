package com.jonoshields.gossip.core.identity

/**
 * Encrypts the seed at rest.
 *
 * An interface so the identity lifecycle can be tested on the JVM: the real implementation
 * is bound to the Android Keystore, which does not exist off-device.
 */
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
