package com.wifi.visualizer.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Fine-grained signal sample recorded every few seconds for a specific BSSID.
 * Used to compute signal volatility, trends, and time-series charts.
 *
 * Unlike [WifiScanResult] (which is anchored to an AR position), [SignalSample]
 * records rapid temporal samples at a single location — useful for understanding
 * how much a signal fluctuates over time.
 */
@Entity(
    tableName = "signal_samples",
    indices   = [
        Index(value = ["bssid", "timestamp"]),
        Index(value = ["session_id"])
    ]
)
data class SignalSample(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "bssid")
    val bssid: String,

    @ColumnInfo(name = "ssid")
    val ssid: String,

    @ColumnInfo(name = "rssi")
    val rssi: Int,

    @ColumnInfo(name = "frequency")
    val frequency: Int,

    @ColumnInfo(name = "link_speed")
    val linkSpeed: Int = -1,

    @ColumnInfo(name = "session_id")
    val sessionId: String = ""
)

/**
 * Session metadata — one row per mapping session.
 */
@Entity(
    tableName = "sessions",
    indices   = [Index(value = ["session_id"], unique = true)]
)
data class SessionMetadata(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "label")
    val label: String = "",

    @ColumnInfo(name = "floor_number")
    val floorNumber: Int = 0,

    @ColumnInfo(name = "building_name")
    val buildingName: String = "",

    @ColumnInfo(name = "environment_type")
    val environmentType: String = "AUTO_DETECT",

    @ColumnInfo(name = "notes")
    val notes: String = "",

    @ColumnInfo(name = "scan_count")
    val scanCount: Int = 0,

    @ColumnInfo(name = "coverage_score")
    val coverageScore: Int = 0,

    @ColumnInfo(name = "duration_seconds")
    val durationSeconds: Int = 0
) {
    val displayName: String get() = label.ifBlank {
        val date = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(createdAt))
        "Session — $date"
    }
}
