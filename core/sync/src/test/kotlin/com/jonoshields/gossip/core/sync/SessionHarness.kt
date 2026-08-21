package com.jonoshields.gossip.core.sync

import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.model.Ed25519Signer
import com.jonoshields.gossip.core.model.Message
import com.jonoshields.gossip.core.model.MessageFactory
import com.jonoshields.gossip.core.model.MessageId
import com.jonoshields.gossip.core.model.Profile
import com.jonoshields.gossip.core.model.ProfileCodec
import com.jonoshields.gossip.core.store.Clock
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** A person with a real keypair, so everything they "write" genuinely verifies. */
internal class Person(seed: Int, val name: String) {
    private val signer = Ed25519Signer(ByteArray(32) { (it + seed).toByte() })
    val key: AuthorId = signer.publicKey

    fun root(text: String, at: Long): Message = MessageFactory.createRoot(key, text, at, signer)

    fun reply(root: MessageId, parent: MessageId?, text: String, at: Long): Message =
        MessageFactory.createReply(key, root, parent, text, at, signer)

    fun profile(at: Long): Profile = ProfileCodec.create(key, name, at, signer)
}

internal val alice = Person(1, "alice")
internal val bob = Person(60, "bob")
internal val carol = Person(120, "carol")
internal val dave = Person(180, "dave")

internal const val NOW = 1_700_000_000_000L

/** A device's own identity in a session — distinct from any author of the content it holds. */
internal val initiatorDevice = author(250)
internal val responderDevice = author(251)

/** Identity a hand-scripted peer sends in its own `HELLO`, in the tests that write one. */
internal val scriptedPeerDevice = author(252)

/** A connection that remembers every frame, so claims about the wire can be checked. */
internal class Recording(private val inner: Connection) : Connection {
    val sent = mutableListOf<Record>()

    /** The bytes as well as the records, so a fuzzer can replay a real session and corrupt it. */
    val rawSent = mutableListOf<ByteArray>()

    override suspend fun send(frame: ByteArray) {
        rawSent += frame
        (FrameCodec.decode(frame) as? FrameResult.Ok)?.let { sent += it.record }
        inner.send(frame)
    }

    override suspend fun receive(): ByteArray? = inner.receive()
    override fun close() = inner.close()

    /** Ids of the messages this side actually put on the wire. */
    fun messageIdsSent(): List<MessageId> = sent
        .filterIsInstance<Record.Message>()
        .mapNotNull { (com.jonoshields.gossip.core.model.MessageVerifier.verify(it.wire)
            as? com.jonoshields.gossip.core.model.VerifyResult.Valid)?.message?.id }
}

internal data class SyncRun(
    val initiator: SessionResult,
    val responder: SessionResult,
    val initiatorWire: Recording,
    val responderWire: Recording,
)

/**
 * Runs one full session between two stores, both sides concurrently over a bounded pipe.
 *
 * Bounded on purpose: an unlimited buffer would hide a protocol that deadlocks under
 * back-pressure until it reached real hardware.
 */
internal suspend fun sync(
    initiatorStore: SyncStore,
    responderStore: SyncStore,
    now: Long = NOW,
    confirm: suspend (AuthorId) -> Boolean = { true },
): SyncRun = coroutineScope {
    val (aEnd, bEnd) = Pipe.open()
    val a = Recording(aEnd)
    val b = Recording(bEnd)
    val clock = Clock.fixed(now)

    val initiator = async { Session(initiatorStore, clock).run(Role.INITIATOR, a, initiatorDevice, confirm) }
    val responder = async { Session(responderStore, clock).run(Role.RESPONDER, b, responderDevice, confirm) }
    val results = SyncRun(initiator.await(), responder.await(), a, b)
    aEnd.close()
    bEnd.close()
    results
}

internal fun SessionResult.summary(): SyncSummary = when (this) {
    is SessionResult.Completed -> summary
    is SessionResult.Aborted -> summary
}

/**
 * Runs our session against a peer we script frame by frame, for the cases where the peer is
 * misbehaving in a way a real implementation never would.
 */
internal suspend fun againstScriptedPeer(
    store: SyncStore,
    now: Long = NOW,
    confirm: suspend (AuthorId) -> Boolean = { true },
    script: suspend (Connection) -> Unit,
): SessionResult = coroutineScope {
    val (peerEnd, ourEnd) = Pipe.open()
    val peer = async { runCatching { script(peerEnd) } }
    val ours = async { Session(store, Clock.fixed(now)).run(Role.RESPONDER, ourEnd, responderDevice, confirm) }
    val result = ours.await()
    // Both ends: a Pipe end closes only its outgoing direction, so closing one still leaves
    // the other blocked reading. The session deliberately does not close the connection —
    // the caller owns it, since a transport may want to reuse or report on it.
    ourEnd.close()
    peerEnd.close()
    peer.await()
    result
}

/**
 * A peer that follows the protocol exactly, holds nothing, and delivers whatever it is told
 * to. Used to feed our session content it would otherwise never see — tampered bytes,
 * floods — without a second real store on the other end.
 *
 * It plays against a **responder**, so the ordering here is the mirror of [Session]'s: it
 * leads every exchange, and in each delivery half it speaks before it listens.
 */
internal suspend fun peerDeliveringMessages(connection: Connection, messages: List<ByteArray>) {
    suspend fun send(record: Record) = connection.send(FrameCodec.encode(record))

    send(Record.Hello(PROTOCOL_VERSION, scriptedPeerDevice))
    connection.receive()
    send(Record.Scope(ScopeDeclaration(emptySet(), 0, emptySet())))
    connection.receive()
    send(Record.HashList(emptySet()))
    connection.receive()

    // Priority phase: we deliver, they deliver back.
    messages.forEach { send(Record.Message(it)) }
    send(Record.PhaseDone)
    drainUntilPhaseDone(connection)

    // Gossip: they receive first, so we offer first. We hold nothing, so both offers are
    // empty and both requests come back empty.
    send(Record.GossipOffer(emptyList()))
    connection.receive()                       // their GOSSIP_REQUEST, necessarily empty
    send(Record.PhaseDone)
    connection.receive()                       // their GOSSIP_OFFER
    send(Record.GossipRequest(emptyList()))
    drainUntilPhaseDone(connection)

    send(Record.SessionDone)
    runCatching { while (connection.receive() != null) Unit }
}

private suspend fun drainUntilPhaseDone(connection: Connection) {
    while (true) {
        val frame = connection.receive() ?: return
        val record = (FrameCodec.decode(frame) as? FrameResult.Ok)?.record ?: return
        if (record == Record.PhaseDone) return
    }
}
