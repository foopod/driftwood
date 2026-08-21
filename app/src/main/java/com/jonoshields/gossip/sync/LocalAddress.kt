package com.jonoshields.gossip.sync

import java.net.Inet4Address
import java.net.NetworkInterface

/** The device's own LAN IPv4 address, for showing a person what to type on the other phone. */
object LocalAddress {
    fun current(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull()
            ?.hostAddress
    }.getOrNull()
}
