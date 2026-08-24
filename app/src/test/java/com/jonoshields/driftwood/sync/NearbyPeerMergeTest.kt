package com.jonoshields.driftwood.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class NearbyPeerMergeTest {

    @Test
    fun `distinct peers from both transports all appear`() {
        val lan = listOf(DiscoveredPeer("Alice's Pixel", "192.168.1.23", 5000))
        val wifiDirect = listOf(WifiDirectPeer("Bob's Phone", "aa:bb:cc:dd:ee:ff"))

        val merged = mergeNearbyPeers(lan, wifiDirect)

        assertEquals(
            listOf(
                NearbyPeer("Alice's Pixel", PeerRef.Lan("192.168.1.23", 5000)),
                NearbyPeer("Bob's Phone", PeerRef.WifiDirect("aa:bb:cc:dd:ee:ff")),
            ),
            merged,
        )
    }

    @Test
    fun `a peer visible over both transports appears once, as the LAN entry`() {
        val lan = listOf(DiscoveredPeer("Alice's Pixel", "192.168.1.23", 5000))
        val wifiDirect = listOf(WifiDirectPeer("Alice's Pixel", "aa:bb:cc:dd:ee:ff"))

        val merged = mergeNearbyPeers(lan, wifiDirect)

        assertEquals(listOf(NearbyPeer("Alice's Pixel", PeerRef.Lan("192.168.1.23", 5000))), merged)
    }

    @Test
    fun `empty discovery on both sides yields an empty list`() {
        assertEquals(emptyList<NearbyPeer>(), mergeNearbyPeers(emptyList(), emptyList()))
    }

    @Test
    fun `wifi direct only peers appear when nothing is visible over LAN`() {
        val wifiDirect = listOf(WifiDirectPeer("Bob's Phone", "aa:bb:cc:dd:ee:ff"))

        val merged = mergeNearbyPeers(emptyList(), wifiDirect)

        assertEquals(listOf(NearbyPeer("Bob's Phone", PeerRef.WifiDirect("aa:bb:cc:dd:ee:ff"))), merged)
    }
}
