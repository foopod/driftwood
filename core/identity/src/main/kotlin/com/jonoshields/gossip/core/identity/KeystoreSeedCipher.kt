package com.jonoshields.gossip.core.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals the seed with an AES-256-GCM key held in the Android Keystore.
 *
 * The Keystore key never leaves the device — on most hardware it never leaves the secure
 * element — so lifting the app's files gets you a blob and nothing else. What it explicitly
 * does **not** give you is portability: a Keystore key cannot be exported or migrated, so
 * the sealed record is permanently unreadable on any other phone. That is why the recovery
 * phrase is mandatory rather than a convenience (plan.md §3.1, §9).
 *
 * No user-authentication requirement is set on the key for MVP: the app has no lock screen
 * of its own, and requiring biometrics to read the identity would make the app unusable on
 * devices with no enrolled credential.
 */
class KeystoreSeedCipher(private val alias: String = DEFAULT_ALIAS) : SeedCipher {

    override fun seal(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        require(iv.size in 1..255) { "unexpected GCM IV length: ${iv.size}" }
        // Length-prefix the IV rather than assuming 12 bytes: the provider chooses it.
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plaintext)
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.isNotEmpty()) { "sealed record is empty" }
        val ivLength = sealed[0].toInt() and 0xFF
        require(sealed.size > 1 + ivLength) { "sealed record is truncated" }

        val iv = sealed.copyOfRange(1, 1 + ivLength)
        val ciphertext = sealed.copyOfRange(1 + ivLength, sealed.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS: String = "gossip.identity.v1"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}

/** The sealed identity record on disk, in the app's private storage. */
class FileSeedStorage(private val file: File) : SeedStorage {

    override fun read(): ByteArray? = if (file.exists()) file.readBytes() else null

    override fun write(record: ByteArray) {
        file.parentFile?.mkdirs()
        // Write beside and rename, so an interrupted write cannot leave a half-written
        // identity — which would be indistinguishable from a corrupt one.
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeBytes(record)
        check(temporary.renameTo(file)) { "could not replace ${file.name}" }
    }

    override fun clear() {
        file.delete()
    }
}
