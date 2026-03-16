package com.wifi.visualizer.utils

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "WifiScanner"

/**
 * Thin wrapper around [WifiManager] that exposes a reactive [Flow] of
 * [WifiSnapshot] values.
 *
 * Usage:
 * ```
 * WifiScanner(context).observe().collect { snapshot -> ... }
 * ```
 *
 * The flow emits a new snapshot whenever the system delivers a Wi-Fi scan
 * result broadcast **or** when [triggerScan] is called explicitly.
 *
 * On Android 9+ active scanning is throttled by the OS to 4 scans per 2 min
 * in the foreground. The receiver still fires on background scans so data
 * keeps flowing even without manual triggers.
 */
class WifiScanner(private val context: Context) {

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /**
     * Returns a cold [Flow] that emits [WifiSnapshot]s reactively.
     * Collect it from a coroutine scope tied to the Activity/Fragment lifecycle.
     */
    fun observe(): Flow<WifiSnapshot> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val snapshot = buildSnapshot() ?: return
                trySend(snapshot)
            }
        }

        val filter = IntentFilter().apply {
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(WifiManager.RSSI_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }

        // Android 13+ requires explicit exported flag for dynamic receivers
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        // Emit immediately with current state
        buildSnapshot()?.let { trySend(it) }

        // Kick off first active scan
        @Suppress("DEPRECATION")
        wifiManager.startScan()

        awaitClose { context.unregisterReceiver(receiver) }
    }

    /** Manually request a new scan. Subject to OS throttling on API 28+. */
    @Suppress("DEPRECATION")
    fun triggerScan() {
        if (hasLocationPermission()) {
            wifiManager.startScan()
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun buildSnapshot(): WifiSnapshot? {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission not granted – cannot read Wi-Fi info")
            return null
        }
        return try {
            val info: WifiInfo = wifiManager.connectionInfo
            @Suppress("DEPRECATION")
            val rawSsid = info.ssid ?: "<unknown>"
            // WifiInfo.getSSID() wraps the name in quotes on API 17+
            val ssid = rawSsid.removeSurrounding("\"")

            // Gather nearby networks from the last scan
            @Suppress("DEPRECATION")
            val scanResults = if (hasLocationPermission())
                wifiManager.scanResults ?: emptyList()
            else
                emptyList()

            WifiSnapshot(
                connectedSsid   = ssid,
                connectedBssid  = info.bssid ?: "",
                rssi            = info.rssi,
                frequency       = info.frequency,
                linkSpeed       = info.linkSpeed,
                nearbyNetworks  = scanResults.map { sr ->
                    NearbyNetwork(
                        ssid      = sr.SSID.ifEmpty { "<hidden>" },
                        bssid     = sr.BSSID,
                        rssi      = sr.level,
                        frequency = sr.frequency
                    )
                }
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException reading Wi-Fi: ${e.message}")
            null
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
}

/**
 * Immutable snapshot of Wi-Fi state at a single point in time.
 */
data class WifiSnapshot(
    val connectedSsid  : String,
    val connectedBssid : String,
    val rssi           : Int,
    val frequency      : Int,
    val linkSpeed      : Int,
    val nearbyNetworks : List<NearbyNetwork>
) {
    /** True when RSSI is a plausible value (not the disconnected sentinel -127). */
    val isConnected: Boolean get() = rssi > -127 && connectedBssid.isNotBlank()

    /** Signal strength in 0–4 bars (mirrors Android's WifiManager.calculateSignalLevel). */
    val signalBars: Int get() = WifiManager.calculateSignalLevel(rssi, 5)
}

data class NearbyNetwork(
    val ssid      : String,
    val bssid     : String,
    val rssi      : Int,
    val frequency : Int
)
