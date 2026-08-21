package com.jonoshields.gossip.sync

import com.jonoshields.gossip.core.identity.IdentityStore
import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.store.Clock
import com.jonoshields.gossip.core.sync.Connection
import com.jonoshields.gossip.core.sync.Role
import com.jonoshields.gossip.core.sync.Session
import com.jonoshields.gossip.core.sync.SyncStore
import com.jonoshields.gossip.core.sync.TcpTransport
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Runs sync sessions over [TcpTransport] (M3a, plan.md §7).
 *
 * Application-scoped rather than owned by a screen or a ViewModel: leaving the Sync screen
 * must not abort a session two people are mid-way through. Not a `Service`, on purpose — it
 * ends when the app does, per plan.md §2's "no background execution"; the M2 plan already
 * settled this call.
 *
 * At most one thing happens at a time — listening, connecting, or a running session — which
 * keeps this from ever driving two [Session]s against the same [SyncStore] concurrently.
 */
@Singleton
class SyncCoordinator @Inject constructor(
    private val syncStore: SyncStore,
    private val identity: IdentityStore,
    private val clock: Clock,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<SyncUiState>(SyncUiState.Idle)
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    private var listener: TcpTransport.Listener? = null
    private var job: Job? = null
    private var pendingConfirmation: CompletableDeferred<Boolean>? = null

    /** Binds a port and waits for one incoming connection. No-op unless currently [SyncUiState.Idle]. */
    fun startListening() {
        if (_state.value !is SyncUiState.Idle) return
        val opened = TcpTransport.Listener()
        listener = opened
        _state.value = SyncUiState.Listening(opened.port)
        job = scope.launch {
            val connection = try {
                opened.accept()
            } catch (e: IOException) {
                // Almost always our own listener.close() unblocking accept() — see cancel().
                _state.value = SyncUiState.Idle
                return@launch
            }
            runSession(Role.RESPONDER, connection)
        }
    }

    /** Connects out to a peer's manually entered address. No-op unless currently [SyncUiState.Idle]. */
    fun connectTo(host: String, port: Int) {
        if (_state.value !is SyncUiState.Idle) return
        job = scope.launch {
            _state.value = SyncUiState.Connecting(host, port)
            val connection = try {
                TcpTransport.connect(host, port)
            } catch (e: IOException) {
                _state.value = SyncUiState.Failed("Couldn't reach $host:$port")
                return@launch
            }
            runSession(Role.INITIATOR, connection)
        }
    }

    /** A human looked at the confirmation screen and said yes. */
    fun confirmPeer() = pendingConfirmation?.complete(true)

    /** A human looked at the confirmation screen and said no. */
    fun declinePeer() = pendingConfirmation?.complete(false)

    /** Gives up on whatever is happening — listening, connecting, or a running session. */
    fun cancel() {
        job?.cancel()
        job = null
        // Closing unblocks a listener parked in accept(); a running Session sees its
        // Connection close and reports PEER_CLOSED rather than hanging.
        listener?.close()
        listener = null
        pendingConfirmation?.cancel()
        pendingConfirmation = null
        _state.value = SyncUiState.Idle
    }

    /** Back to idle after seeing a result or a failure, so the screen can start over. */
    fun reset() {
        _state.value = SyncUiState.Idle
    }

    private suspend fun runSession(role: Role, connection: Connection) {
        _state.value = SyncUiState.Running
        try {
            val result = Session(syncStore, clock).run(role, connection, identity.publicKey()) { peer ->
                confirm(peer)
            }
            _state.value = SyncUiState.Finished(result)
        } finally {
            connection.close()
            // A session claims the listener's one slot whether we listened or connected out;
            // once it starts, there is nothing left to accept.
            listener?.close()
            listener = null
        }
    }

    private suspend fun confirm(peer: AuthorId): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingConfirmation = deferred
        _state.value = SyncUiState.Confirming(peer)
        return deferred.await()
    }
}
