package com.wifi.visualizer.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a single Wi-Fi scan snapshot anchored to a real-world position.
 *
 * @param id          Auto-generated primary key.
 * @param timestamp   Unix epoch in milliseconds when the scan was captured.
 * @param ssid        Network name (may be "<unknown>" on API 29+ without permission).
 * @param bssid       Access-point MAC address.
 * @param rssi        Received Signal Strength Indicator in dBm (negative value).
 * @param frequency   Channel frequency in MHz (e.g. 2412, 5180).
 * @param linkSpeed   Current link speed in Mbps (-1 if not connected).
 * @param latitude    GPS latitude at capture time (0.0 if unavailable).
 * @param longitude   GPS longitude at capture time (0.0 if unavailable).
 * @param arPosX      ARCore world-space X in metres.
 * @param arPosY      ARCore world-space Y in metres.
 * @param arPosZ      ARCore world-space Z in metres.
 * @param sessionId   UUID string grouping scans from a single mapping session.
 */
@Entity(tableName = "wifi_scan_results")
data class WifiScanResult(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "ssid")
    val ssid: String,

    @ColumnInfo(name = "bssid")
    val bssid: String,

    @ColumnInfo(name = "rssi")
    val rssi: Int,

    @ColumnInfo(name = "frequency")
    val frequency: Int,

    @ColumnInfo(name = "link_speed")
    val linkSpeed: Int = -1,

    @ColumnInfo(name = "latitude")
    val latitude: Double = 0.0,

    @ColumnInfo(name = "longitude")
    val longitude: Double = 0.0,

    @ColumnInfo(name = "ar_pos_x")
    val arPosX: Float = 0f,

    @ColumnInfo(name = "ar_pos_y")
    val arPosY: Float = 0f,

    @ColumnInfo(name = "ar_pos_z")
    val arPosZ: Float = 0f,

    @ColumnInfo(name = "session_id")
    val sessionId: String = ""
) {
    /** Human-readable band label derived from frequency. */
    val band: String
        get() = when {
            frequency < 3000 -> "2.4 GHz"
            frequency < 6000 -> "5 GHz"
            else             -> "6 GHz"
        }

    /** Signal quality category used for colour-coding AR pillars. */
    val signalQuality: SignalQuality
        get() = SignalQuality.from(rssi)
}

/**
 * Colour-coded RSSI ranges that drive pillar colour and height in AR.
 */
enum class SignalQuality(val label: String, val colorHex: String) {
    EXCELLENT("Excellent", "#4CAF50"),   // green  > -50 dBm
    GOOD     ("Good",      "#FFC107"),   // yellow -50 to -70 dBm
    FAIR     ("Fair",      "#FF9800"),   // orange -70 to -80 dBm
    POOR     ("Poor",      "#F44336");   // red    < -80 dBm

    companion object {
        fun from(rssi: Int): SignalQuality = when {
            rssi > -50 -> EXCELLENT
            rssi > -70 -> GOOD
            rssi > -80 -> FAIR
            else       -> POOR
        }
    }
}
