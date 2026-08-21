package com.jonoshields.gossip.core.sync

import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.model.Ed25519Signer
import com.jonoshields.gossip.core.model.MessageFactory
import com.jonoshields.gossip.core.model.ProfileCodec

/**
 * A synthetic peer for proving a real session end to end from the app, with no second
 * device and no radio (M2 plan, step 6).
 *
 * Everything here is invented but genuine: real Ed25519 keypairs, real signatures, real
 * canonical encoding. The only thing that isn't real is that no person is behind the keys.
 * Running a session against the store this builds is how Settings' debug-sync action
 * exercises the actual path — framing, reconciliation, verification, ingest — against the
 * same [InMemorySyncStore] the convergence suite is written against, rather than a mock of
 * it.
 */
object DebugPeer {

    /**
     * What the debug peer's own `Session` sends as its `HELLO` identity — a device identity,
     * distinct from any of the invented people whose content it holds, the same way a real
     * phone's own key is not any particular author it happens to be carrying.
     */
    val device: AuthorId = Ed25519Signer(ByteArray(32) { 0xDE.toByte() }).publicKey

    /** One root and a reply in its thread, plus an unrelated root, from three invented people. */
    fun build(nowMillis: Long): InMemorySyncStore {
        val nyx = identity(seed = 41, name = "Nyx")
        val otis = identity(seed = 89, name = "Otis")
        val petra = identity(seed = 137, name = "Petra")

        val root = MessageFactory.createRoot(
            nyx.key, "Does anyone else's radio drop mid-sync?", nowMillis - 40_000, nyx.signer,
        )
        val reply = MessageFactory.createReply(
            otis.key, root.id, root.id, "Only on the first hop, then it settles.",
            nowMillis - 20_000, otis.signer,
        )
        val aside = MessageFactory.createRoot(
            petra.key, "Unrelated: found a great spot for the meetup.", nowMillis - 5_000, petra.signer,
        )

        return InMemorySyncStore()
            .seed(root)
            .seed(reply)
            .seed(aside)
            .seedProfile(nyx.profile(nowMillis - 40_000))
            .seedProfile(otis.profile(nowMillis - 20_000))
            .seedProfile(petra.profile(nowMillis - 5_000))
    }

    private class Identity(val signer: Ed25519Signer, private val name: String) {
        val key = signer.publicKey
        fun profile(atMillis: Long) = ProfileCodec.create(key, name, atMillis, signer)
    }

    private fun identity(seed: Int, name: String) =
        Identity(Ed25519Signer(ByteArray(32) { i -> (i + seed).toByte() }), name)
}
