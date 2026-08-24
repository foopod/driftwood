package com.jonoshields.driftwood.sync

/** How to reach a peer shown in the merged "Nearby" list. */
sealed interface PeerRef {
    data class Lan(val host: String, val port: Int) : PeerRef
    data class WifiDirect(val deviceAddress: String) : PeerRef
}

/** One entry in the merged "Nearby" list — LAN and Wi-Fi Direct discoveries shown together. */
data class NearbyPeer(val name: String, val ref: PeerRef)

/** Merges both discovery sources by name; the LAN entry wins when a peer appears in both. */
fun mergeNearbyPeers(lan: List<DiscoveredPeer>, wifiDirect: List<WifiDirectPeer>): List<NearbyPeer> {
    val lanByName = lan.associateBy { it.name }
    val wifiDirectOnly = wifiDirect.filterNot { it.name in lanByName }
    return lan.map { NearbyPeer(it.name, PeerRef.Lan(it.host, it.port)) } +
        wifiDirectOnly.map { NearbyPeer(it.name, PeerRef.WifiDirect(it.deviceAddress)) }
}
