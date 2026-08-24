package com.jonoshields.driftwood.core.identity

import com.jonoshields.driftwood.core.crypto.Ed25519
import com.jonoshields.driftwood.core.crypto.Mnemonic
import com.jonoshields.driftwood.core.crypto.MnemonicResult
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.Ed25519Signer
import com.jonoshields.driftwood.core.model.Signer
import java.security.SecureRandom

sealed interface IdentityState {
    data object None : IdentityState

    /** An identity exists but has never been written down. The app stays gated here. */
    data class NeedsBackup(val author: AuthorId) : IdentityState

    data class Ready(val author: AuthorId) : IdentityState
}

/** Why a typed phrase was refused — specific enough to tell someone which word to re-check, not just "checksum failed". */
sealed interface PhraseProblem {
    data class WrongWordCount(val actual: Int, val expected: Int) : PhraseProblem

    /** [position] is zero-based. */
    data class UnknownWord(val position: Int, val word: String) : PhraseProblem

    /** Every word is real, but they do not belong together — usually one is wrong or misordered. */
    data object DoesNotCheckOut : PhraseProblem
}

sealed interface RestoreResult {
    data class Success(val author: AuthorId) : RestoreResult
    data class InvalidPhrase(val problem: PhraseProblem) : RestoreResult
    data object AlreadyExists : RestoreResult
}

/** Owns the one secret in the system — the public key is the identity, so losing the seed loses it permanently. */
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

    /** Restores from whatever the user typed; whitespace and case are not their problem. */
    fun restore(typed: String): RestoreResult = restore(Mnemonic.normalize(typed))

    fun restore(words: List<String>): RestoreResult {
        if (readRecord() != null) return RestoreResult.AlreadyExists
        return when (val decoded = Mnemonic.decode(words)) {
            is MnemonicResult.Success -> {
                // Already holding the phrase by definition, so re-confirming it would be theatre.
                writeRecord(Record(seed = decoded.seed, backedUp = true))
                RestoreResult.Success(AuthorId.of(Ed25519.publicKeyFromSeed(decoded.seed)))
            }
            is MnemonicResult.WrongWordCount ->
                RestoreResult.InvalidPhrase(
                    PhraseProblem.WrongWordCount(decoded.actual, Mnemonic.WORD_COUNT)
                )
            is MnemonicResult.UnknownWord ->
                RestoreResult.InvalidPhrase(PhraseProblem.UnknownWord(decoded.position, decoded.word))
            MnemonicResult.ChecksumMismatch ->
                RestoreResult.InvalidPhrase(PhraseProblem.DoesNotCheckOut)
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

    /** On disk: `[version][backedUp][sealed seed…]`, one file so the flag stays consistent with the seed. */
    private fun readRecord(): Record? {
        val raw = storage.read() ?: return null

        // Must throw, not report "no identity" — that would let create() silently overwrite it.
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
