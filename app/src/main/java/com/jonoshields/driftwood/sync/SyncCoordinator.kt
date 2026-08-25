package com.jonoshields.driftwood.sync

import android.content.Context
import com.jonoshields.driftwood.core.identity.IdentityStore
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.store.Clock
import com.jonoshields.driftwood.core.sync.Connection
import com.jonoshields.driftwood.core.sync.Role
import com.jonoshields.driftwood.core.sync.Session
import com.jonoshields.driftwood.core.sync.SyncStore
import com.jonoshields.driftwood.core.sync.TcpTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Runs sync sessions over [TcpTransport] or [WifiDirectTransport], whichever connects first. */
@Singleton
class SyncCoordinator @Inject constructor(
    private val syncStore: SyncStore,
    private val identity: IdentityStore,
    private val clock: Clock,
    private val discovery: NsdPeerDiscovery,
    private val wifiDirectDiscovery: WifiDirectPeerDiscovery,
    private val wifiDirectTransport: WifiDirectTransport,
    private val syncLog: SyncLog,
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<SyncUiState>(SyncUiState.Idle)
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    private var listener: TcpTransport.Listener? = null
    private var advertisement: AutoCloseable? = null
    private var wifiDirectAdvertisement: AutoCloseable? = null
    private var job: Job? = null
    private var pendingConfirmation: CompletableDeferred<Boolean>? = null

    /** Whether the current/last session ran over Wi-Fi Direct, so teardown knows what to clean up. */
    private var usedWifiDirect = false

    /** Advertises over NSD and, if usable, Wi-Fi Direct too; whichever connects first wins. No-op unless [SyncUiState.Idle]. */
    fun startListening() {
        if (_state.value !is SyncUiState.Idle) return
        syncLog.start()
        val opened = TcpTransport.Listener()
        listener = opened
        advertisement = discovery.advertise(opened.port)
        syncLog.event("LAN: advertising on port ${opened.port}")
        val wifiDirectEnabled = WifiDirectAvailability.isSupported(context)
        wifiDirectAdvertisement = if (wifiDirectEnabled) {
            syncLog.event("Wi-Fi Direct: advertising")
            wifiDirectDiscovery.advertise()
        } else {
            syncLog.event("Wi-Fi Direct: unavailable on this device, LAN only")
            null
        }
        _state.value = SyncUiState.Listening(opened.port)

        // Losing is enforced by closing the underlying socket/group, not coroutine cancellation.
        val winner = CompletableDeferred<ListenOutcome>()

        scope.launch {
            try {
                winner.complete(ListenOutcome.Lan(opened.accept()))
            } catch (e: IOException) {
                // Almost always our own teardown — Wi-Fi Direct won, or the user cancelled.
                syncLog.event("LAN: accept() ended — ${e.message}")
                winner.completeExceptionally(e)
            }
        }
        if (wifiDirectEnabled) {
            scope.launch {
                try {
                    val (connection, role) = wifiDirectTransport.awaitIncomingConnection()
                    syncLog.event("Wi-Fi Direct: incoming connection formed (role=$role)")
                    winner.complete(ListenOutcome.WifiDirect(connection, role))
                } catch (e: IOException) {
                    // A soft failure here must not end the listen — the LAN half may still be live.
                    syncLog.event("Wi-Fi Direct: incoming connection failed — ${e.message}")
                }
            }
        }

        job = scope.launch {
            val outcome = try {
                winner.await()
            } catch (e: IOException) {
                syncLog.event("listen: both transports failed — ${e.message}")
                stopAdvertising()
                wifiDirectTransport.cancel()
                _state.value = SyncUiState.Idle
                return@launch
            }
            stopAdvertising()
            // LAN naturally wins when both are viable — Wi-Fi Direct group formation is slower.
            if (outcome !is ListenOutcome.WifiDirect) wifiDirectTransport.cancel()
            usedWifiDirect = outcome is ListenOutcome.WifiDirect
            val (connection, role) = when (outcome) {
                is ListenOutcome.Lan -> outcome.connection to Role.RESPONDER
                is ListenOutcome.WifiDirect -> outcome.connection to outcome.role
            }
            syncLog.event("listen: connected over ${if (usedWifiDirect) "Wi-Fi Direct" else "LAN"} as $role")
            runSession(role, connection)
        }
    }

    /** Connects out to [ref]; [label] is shown on the connecting screen. No-op unless [SyncUiState.Idle]. */
    fun connectTo(ref: PeerRef, label: String) {
        if (_state.value !is SyncUiState.Idle) return
        syncLog.start()
        job = scope.launch {
            _state.value = SyncUiState.Connecting(label)
            usedWifiDirect = ref is PeerRef.WifiDirect
            syncLog.event("connectTo: ${if (usedWifiDirect) "Wi-Fi Direct" else "LAN"} target $label")
            val (connection, role) = try {
                when (ref) {
                    is PeerRef.Lan -> TcpTransport.connect(ref.host, ref.port) to Role.INITIATOR
                    is PeerRef.WifiDirect -> wifiDirectTransport.connect(ref.deviceAddress)
                }
            } catch (e: IOException) {
                syncLog.event("connectTo: failed — ${e.message}")
                if (ref is PeerRef.WifiDirect) wifiDirectTransport.cancel()
                _state.value = SyncUiState.Failed("Couldn't reach $label")
                return@launch
            }
            syncLog.event("connectTo: connected as $role")
            runSession(role, connection)
        }
    }

    /** A human looked at the confirmation screen and said yes. */
    fun confirmPeer() = pendingConfirmation?.complete(true)

    /** Gives up on whatever is happening — listening, connecting, or a running session. */
    fun cancel() {
        syncLog.event("cancelled by user (state=${_state.value})")
        job?.cancel()
        job = null
        // Closing unblocks a listener parked in accept(); a running Session reports PEER_CLOSED.
        listener?.close()
        listener = null
        stopAdvertising()
        wifiDirectTransport.cancel()
        pendingConfirmation?.cancel()
        pendingConfirmation = null
        _state.value = SyncUiState.Idle
    }

    private fun stopAdvertising() {
        advertisement?.close()
        advertisement = null
        wifiDirectAdvertisement?.close()
        wifiDirectAdvertisement = null
    }

    /** Back to idle after seeing a result or a failure, so the screen can start over. */
    fun reset() {
        _state.value = SyncUiState.Idle
    }

    private suspend fun runSession(role: Role, connection: Connection) {
        _state.value = SyncUiState.Running
        syncLog.event("session: starting as $role")
        try {
            val result = Session(syncStore, clock).run(role, connection, identity.publicKey()) { peer ->
                confirm(peer)
            }
            syncLog.event("session: $result")
            _state.value = SyncUiState.Finished(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // An IOException mid-session (peer walked away, radio dropped) would otherwise be an
            // unhandled exception on this coroutine — surface it as a normal failed sync instead.
            syncLog.event("session: crashed — ${e.message}")
            _state.value = SyncUiState.Failed("Sync failed: ${e.message ?: e::class.simpleName}")
        } finally {
            connection.close()
            listener?.close()
            listener = null
            // Only after the session finishes with the connection — removing it earlier would pull the socket out from under the session.
            if (usedWifiDirect) wifiDirectTransport.cancel()
        }
    }

    private suspend fun confirm(peer: AuthorId): Boolean {
        syncLog.event("session: waiting on confirmation for $peer")
        val deferred = CompletableDeferred<Boolean>()
        pendingConfirmation = deferred
        _state.value = SyncUiState.Confirming(peer)
        return deferred.await()
    }

    private sealed interface ListenOutcome {
        data class Lan(val connection: Connection) : ListenOutcome
        data class WifiDirect(val connection: Connection, val role: Role) : ListenOutcome
    }
}
