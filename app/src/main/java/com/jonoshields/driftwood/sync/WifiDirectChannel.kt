package com.jonoshields.driftwood.sync

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The one [WifiP2pManager.Channel] discovery and transport share — separate channels interfere. */
@Singleton
class WifiDirectChannel @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val manager: WifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager

    @Volatile
    private var channel: WifiP2pManager.Channel? = null

    /** True while a connect negotiation is in flight, so discovery skips re-triggering scans. */
    @Volatile
    var connecting: Boolean = false

    /** Created once on first use and kept for the process's lifetime. */
    fun channel(): WifiP2pManager.Channel = channel ?: synchronized(this) {
        channel ?: manager.initialize(context, context.mainLooper, null).also { channel = it }
    }
}
