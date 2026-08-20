package com.jonoshields.gossip.core.identity

import com.jonoshields.gossip.core.crypto.Ed25519
import com.jonoshields.gossip.core.crypto.Mnemonic
import com.jonoshields.gossip.core.crypto.MnemonicResult
import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.model.Ed25519Signer
import com.jonoshields.gossip.core.model.Signer
import java.security.SecureRandom

sealed interface IdentityState {
    data object None : IdentityState

    /** An identity exists but has never been written down. The app stays gated here. */
    data class NeedsBackup(val author: AuthorId) : IdentityState

    data class Ready(val author: AuthorId) : IdentityState
}

sealed interface RestoreResult {
    data class Success(val author: AuthorId) : RestoreResult
    data class InvalidPhrase(val reason: MnemonicResult) : RestoreResult
    data object AlreadyExists : RestoreResult
}

/**
 * Owns the one secret in the system (plan.md §3.1).
 *
 * The public key **is** the identity — there is no handle registry and no account to
 * recover through, so losing the seed loses the identity permanently. That single fact
 * shapes this class:
 *
 *  - The seed is generated on device and only ever stored sealed by [SeedCipher].
 *  - It stays **exportable** as a recovery phrase, because the Keystore copy is worthless
 *    on a new phone — Keystore keys never migrate. The phrase is the only way across.
 *  - [create] and [restore] refuse to overwrite an existing identity. Silently replacing a
 *    key is irreversible and would look, to the user, exactly like the app forgetting them.
 */
class IdentityStore(
    private val cipher: SeedCipher,
    private val storage: SeedStorage,
    private val random: SecureRandom = SecureRandom(),
) {

    fun state(): IdentityState {
        val record = readRecord() ?: return IdentityState.None
        val author = AuthorId.of(Ed25519.publicKeyFromSeed(record.seed))
        return if (record.backedUp) IdentityState.Ready(author) else IdentityState.NeedsBackup(author)
    }

    fun create(): AuthorId {
        check(readRecord() == null) {
            "an identity already exists; creating another would destroy it irrecoverably"
        }
        val seed = ByteArray(Ed25519.SEED_LENGTH).also(random::nextBytes)
        writeRecord(Record(seed = seed, backedUp = false))
        return AuthorId.of(Ed25519.publicKeyFromSeed(seed))
    }

    fun restore(words: List<String>): RestoreResult {
        if (readRecord() != null) return RestoreResult.AlreadyExists
        return when (val decoded = Mnemonic.decode(words)) {
            is MnemonicResult.Success -> {
                // Someone restoring is holding the phrase by definition, so requiring them
                // to write it down again would be theatre.
                writeRecord(Record(seed = decoded.seed, backedUp = true))
                RestoreResult.Success(AuthorId.of(Ed25519.publicKeyFromSeed(decoded.seed)))
            }
            else -> RestoreResult.InvalidPhrase(decoded)
        }
    }

    /** The words to show during the forced backup step. */
    fun recoveryPhrase(): List<String> = Mnemonic.encode(requireRecord().seed)

    /** Called once the user has demonstrably written the phrase down. */
    fun confirmBackedUp() {
        val record = requireRecord()
        if (!record.backedUp) writeRecord(record.copy(backedUp = true))
    }

    fun publicKey(): AuthorId = AuthorId.of(Ed25519.publicKeyFromSeed(requireRecord().seed))

    fun signer(): Signer = Ed25519Signer(requireRecord().seed)

    private fun requireRecord(): Record =
        checkNotNull(readRecord()) { "no identity has been created yet" }

    private data class Record(val seed: ByteArray, val backedUp: Boolean)

    /**
     * On disk: `[version][backedUp][sealed seed…]`. The flag rides along with the seed so
     * there is exactly one file to keep consistent, and the version byte leaves room to
     * change the format without guessing at what an old record meant.
     */
    private fun readRecord(): Record? {
        val raw = storage.read() ?: return null

        // A record that exists but cannot be opened must never be reported as "no identity".
        // That would let create() cheerfully mint a replacement over the top of a key the
        // user still has the phrase for — the one irreversible mistake this class exists to
        // prevent. Failing loudly lets the UI say "restore from your recovery phrase"
        // instead of silently becoming a stranger.
        if (raw.size < 2 || raw[0] != RECORD_VERSION) {
            throw GeneralSecurityFailure("identity record is corrupt or from an unknown version")
        }
        val seed = try {
            cipher.open(raw.copyOfRange(2, raw.size))
        } catch (e: Exception) {
            throw GeneralSecurityFailure("the stored identity could not be unlocked", e)
        }
        if (seed.size != Ed25519.SEED_LENGTH) {
            throw GeneralSecurityFailure("unlocked identity is the wrong size: ${seed.size} bytes")
        }
        return Record(seed = seed, backedUp = raw[1] == 1.toByte())
    }

    private fun writeRecord(record: Record) {
        val sealed = cipher.seal(record.seed)
        storage.write(byteArrayOf(RECORD_VERSION, if (record.backedUp) 1 else 0) + sealed)
    }

    private companion object {
        const val RECORD_VERSION: Byte = 1
    }
}

/** Thrown when the sealed record cannot be opened — a tampered or key-less device. */
class GeneralSecurityFailure(message: String, cause: Throwable? = null) : Exception(message, cause)
