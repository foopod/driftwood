package com.jonoshields.driftwood.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat

/** A snapshot check, not a guarantee — whether it's worth starting Wi-Fi Direct discovery at all. */
object WifiDirectAvailability {

    fun isSupported(context: Context): Boolean {
        val packageManager = context.packageManager
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) return false

        val permissionGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        ) == PackageManager.PERMISSION_GRANTED
        if (!permissionGranted) return false

        // No synchronous "is P2P enabled" query exists, so the Wi-Fi radio state is the best proxy.
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        return wifiManager?.isWifiEnabled == true
    }
}
