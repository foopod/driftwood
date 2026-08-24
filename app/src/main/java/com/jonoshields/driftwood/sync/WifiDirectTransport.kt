package com.jonoshields.driftwood.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import androidx.core.content.ContextCompat
import com.jonoshields.driftwood.core.sync.Connection
import com.jonoshields.driftwood.core.sync.Role
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** Slightly past Android's own internal ~60s group-creation timeout. */
private const val GROUP_FORMATION_TIMEOUT_MILLIS = 65_000L

/** Drives Wi-Fi Direct group formation and hands back a plain socket [Connection] once a group exists. */
@Singleton
class WifiDirectTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wifiDirectChannel: WifiDirectChannel,
) {
    private val manager get() = wifiDirectChannel.manager

    private var activeChannel: WifiP2pManager.Channel? = null
    private var activeListener: WifiDirectSocketEstablisher.GroupOwnerListener? = null

    /** Calls out to the peer at [deviceAddress]; the returned [Role] is decided by GO negotiation, not by who called this. */
    suspend fun connect(deviceAddress: String): Pair<Connection, Role> {
        val channel = wifiDirectChannel.channel()
        awaitStaleGroupRemoved(channel)
        return awaitGroupConnection(channel) {
            val config = WifiP2pConfig().apply { this.deviceAddress = deviceAddress }
            manager.connect(channel, config, noopActionListener())
        }
    }

    /** Waits to be connected *to* — no local trigger, the peer's [connect] call starts negotiation. */
    suspend fun awaitIncomingConnection(): Pair<Connection, Role> {
        val channel = wifiDirectChannel.channel()
        awaitStaleGroupRemoved(channel)
        return awaitGroupConnection(channel) { }
    }

    /** Unlike [removeStaleGroup], waits for stale-group removal to finish — a race here can kill a fresh connect(). */
    private suspend fun awaitStaleGroupRemoved(channel: WifiP2pManager.Channel) {
        val group = suspendCancellableCoroutine<WifiP2pGroup?> { cont ->
            runCatching {
                manager.requestGroupInfo(channel) { group -> if (cont.isActive) cont.resume(group) }
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
        if (group != null) {
            suspendCancellableCoroutine<Unit> { cont ->
                manager.removeGroup(
                    channel,
                    object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onFailure(reason: Int) {
                            if (cont.isActive) cont.resume(Unit)
                        }
                    },
                )
            }
        }
    }

    /** Tears down whatever's in flight (connect/await/listening socket); safe to call when idle. */
    fun cancel() {
        activeListener?.close()
        activeListener = null
        activeChannel?.let { channel ->
            runCatching { manager.cancelConnect(channel, noopActionListener()) }
            runCatching { manager.removeGroup(channel, noopActionListener()) }
        }
        activeChannel = null
    }

    /** Doesn't clean up [activeChannel]/[activeListener] itself — only [cancel] does, on the caller's schedule. */
    private suspend fun awaitGroupConnection(
        channel: WifiP2pManager.Channel,
        onReady: () -> Unit,
    ): Pair<Connection, Role> {
        activeChannel = channel
        wifiDirectChannel.connecting = true
        return try {
            // Backstop for OEMs that never send a failure broadcast — not the primary failure path.
            val info = try {
                withTimeout(GROUP_FORMATION_TIMEOUT_MILLIS) { awaitConnectionInfo(channel, onReady) }
            } catch (e: TimeoutCancellationException) {
                throw IOException("timed out waiting for the Wi-Fi Direct connection", e)
            }
            if (info.isGroupOwner) {
                val listener = WifiDirectSocketEstablisher.GroupOwnerListener()
                activeListener = listener
                listener.accept() to Role.RESPONDER
            } else {
                val address = info.groupOwnerAddress
                    ?: throw IOException("group formed but no group-owner address was resolved")
                WifiDirectSocketEstablisher.connectToGroupOwner(address) to Role.INITIATOR
            }
        } finally {
            wifiDirectChannel.connecting = false
        }
    }

    /** Suspends until a group actually forms (not just "request accepted"), or fails fast if Wi-Fi Direct is off. */
    private suspend fun awaitConnectionInfo(
        channel: WifiP2pManager.Channel,
        onReady: () -> Unit,
    ): WifiP2pInfo = suspendCancellableCoroutine { cont ->
        lateinit var receiver: BroadcastReceiver
        fun finish(deliver: () -> Unit) {
            runCatching { context.unregisterReceiver(receiver) }
            deliver()
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION") // The only extra Wi-Fi Direct's own broadcast offers.
                        val networkInfo = intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_NETWORK_INFO,
                            NetworkInfo::class.java,
                        )
                        // isConnected == false fires transiently mid-negotiation; the timeout backstop catches real failures.
                        if (networkInfo?.isConnected == true) {
                            manager.requestConnectionInfo(channel) { info ->
                                if (info.groupFormed && cont.isActive) finish { cont.resume(info) }
                            }
                        }
                    }
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        if (state == WifiP2pManager.WIFI_P2P_STATE_DISABLED && cont.isActive) {
                            finish { cont.resumeWithException(IOException("Wi-Fi Direct was turned off")) }
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }

        onReady()
    }
}
