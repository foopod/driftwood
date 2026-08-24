package com.jonoshields.driftwood.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** One peer found advertising [SERVICE_TYPE] nearby, resolved to an address we can connect to. */
data class DiscoveredPeer(val name: String, val host: String, val port: Int)

private const val SERVICE_TYPE = "_gossip._tcp."

/** Advertise-and-discover over mDNS/NSD, so devices on the same Wi-Fi network find each other. */
@Singleton
class NsdPeerDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val executor = ContextCompat.getMainExecutor(context)

    /** Advertises [port] under this device's name. Unregisters when the handle is closed. */
    fun advertise(port: Int): AutoCloseable {
        val info = NsdServiceInfo().apply {
            serviceName = "driftwood (${Build.MODEL})".take(63)
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, executor, listener)
        return AutoCloseable { runCatching { nsdManager.unregisterService(listener) } }
    }

    /** Peers currently visible, updated live; holds a multicast lock for as long as it's collected. */
    fun discover(): Flow<List<DiscoveredPeer>> = callbackFlow {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val multicastLock = wifiManager.createMulticastLock("gossip-nsd-discovery").apply {
            setReferenceCounted(true)
            acquire()
        }

        val peers = mutableMapOf<String, DiscoveredPeer>()

        val resolveListener = { requestedName: String ->
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host?.hostAddress ?: return
                    peers[requestedName] = DiscoveredPeer(requestedName, host, serviceInfo.port)
                    trySend(peers.values.toList())
                }
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsdManager.resolveService(serviceInfo, executor, resolveListener(serviceInfo.serviceName))
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                peers.remove(serviceInfo.serviceName)
                trySend(peers.values.toList())
            }
        }

        // No Executor-based discoverServices overload exists without a Network to scope it to.
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

        awaitClose {
            runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
            runCatching { multicastLock.release() }
        }
    }
}
