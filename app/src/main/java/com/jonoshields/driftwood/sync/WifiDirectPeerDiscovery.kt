package com.jonoshields.driftwood.sync

import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One peer found advertising driftwood's Wi-Fi Direct service; [deviceAddress] is the P2P MAC. */
data class WifiDirectPeer(val name: String, val deviceAddress: String)

internal const val WIFI_DIRECT_SERVICE_TYPE = "_gossip._tcp"

/** Advertise-and-discover over Wi-Fi Direct DNS-SD, the P2P analogue of [NsdPeerDiscovery]. */
@Singleton
class WifiDirectPeerDiscovery @Inject constructor(
    private val wifiDirectChannel: WifiDirectChannel,
    private val syncLog: SyncLog,
) {
    private val manager get() = wifiDirectChannel.manager

    /** Advertises this device; unregisters on close. Sweeps any stale group left over first. */
    fun advertise(): AutoCloseable {
        val channel = wifiDirectChannel.channel()
        removeStaleGroup(manager, channel)

        val instanceName = "driftwood (${Build.MODEL})".take(63)
        Log.d("WifiDirectDiscovery", "advertise() instanceName=$instanceName serviceType=$WIFI_DIRECT_SERVICE_TYPE")
        syncLog.event("Wi-Fi Direct: advertising as $instanceName")
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(instanceName, WIFI_DIRECT_SERVICE_TYPE, emptyMap())
        manager.addLocalService(channel, serviceInfo, noopActionListener())

        return AutoCloseable {
            runCatching { manager.removeLocalService(channel, serviceInfo, noopActionListener()) }
        }
    }

    /** Peers currently visible; dropped again if they stop responding (no "service lost" callback exists). */
    fun discover(): Flow<List<WifiDirectPeer>> = callbackFlow {
        val channel = wifiDirectChannel.channel()
        removeStaleGroup(manager, channel)

        val peers = mutableMapOf<String, WifiDirectPeer>()
        val lastSeenAt = mutableMapOf<String, Long>()

        manager.setDnsSdResponseListeners(
            channel,
            { instanceName, registrationType, device ->
                if (registrationType.startsWith(WIFI_DIRECT_SERVICE_TYPE)) {
                    peers[device.deviceAddress] = WifiDirectPeer(instanceName, device.deviceAddress)
                    lastSeenAt[device.deviceAddress] = System.currentTimeMillis()
                    trySend(peers.values.toList())
                }
            },
            { _, _, _ -> },
        )

        val serviceRequest = WifiP2pDnsSdServiceRequest.newInstance()
        manager.addServiceRequest(channel, serviceRequest, noopActionListener())

        // discoverServices() is one scan burst, not continuous — re-trigger and prune periodically.
        val refreshJob = launch {
            while (isActive) {
                if (!wifiDirectChannel.connecting) manager.discoverServices(channel, noopActionListener())
                delay(DISCOVERY_REFRESH_INTERVAL_MILLIS)
                val cutoff = System.currentTimeMillis() - STALE_PEER_TIMEOUT_MILLIS
                val stale = lastSeenAt.filterValues { it < cutoff }.keys.toList()
                if (stale.isNotEmpty()) {
                    stale.forEach { peers.remove(it); lastSeenAt.remove(it) }
                    trySend(peers.values.toList())
                }
            }
        }

        awaitClose {
            refreshJob.cancel()
            runCatching { manager.removeServiceRequest(channel, serviceRequest, noopActionListener()) }
            runCatching { manager.stopPeerDiscovery(channel, noopActionListener()) }
        }
    }
}

private const val DISCOVERY_REFRESH_INTERVAL_MILLIS = 10_000L

// A couple of missed cycles' worth of slack so timing jitter doesn't flicker a peer out.
private const val STALE_PEER_TIMEOUT_MILLIS = 25_000L

/** Best-effort: "no group to remove" is the expected, harmless outcome most of the time. */
internal fun removeStaleGroup(manager: WifiP2pManager, channel: WifiP2pManager.Channel) {
    runCatching {
        manager.requestGroupInfo(channel) { group ->
            if (group != null) manager.removeGroup(channel, noopActionListener())
        }
    }
}

/** [WifiP2pManager.ActionListener] only reports request-accepted; real outcomes arrive via broadcasts. */
internal fun noopActionListener() = object : WifiP2pManager.ActionListener {
    override fun onSuccess() = Unit
    override fun onFailure(reason: Int) = Unit
}
