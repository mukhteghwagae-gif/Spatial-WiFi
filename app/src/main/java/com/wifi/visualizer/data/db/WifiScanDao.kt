package com.wifi.visualizer.data.db

import androidx.room.*
import com.wifi.visualizer.data.model.WifiScanResult
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [WifiScanResult].
 *
 * All read operations return [Flow] so that the UI layer can observe live
 * updates reactively without polling the database.
 */
@Dao
interface WifiScanDao {

    // ── Write operations ────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: WifiScanResult): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(results: List<WifiScanResult>)

    @Delete
    suspend fun delete(result: WifiScanResult)

    @Query("DELETE FROM wifi_scan_results WHERE session_id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("DELETE FROM wifi_scan_results")
    suspend fun deleteAll()

    // ── Read operations ─────────────────────────────────────────────────────

    /** All results, newest first — used for the history list. */
    @Query("SELECT * FROM wifi_scan_results ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<WifiScanResult>>

    /** All results for a specific mapping session. */
    @Query("SELECT * FROM wifi_scan_results WHERE session_id = :sessionId ORDER BY timestamp ASC")
    fun observeSession(sessionId: String): Flow<List<WifiScanResult>>

    /** Synchronous read — only use from a background thread (e.g., CSV export). */
    @Query("SELECT * FROM wifi_scan_results WHERE session_id = :sessionId ORDER BY timestamp ASC")
    suspend fun getSession(sessionId: String): List<WifiScanResult>

    /** Synchronous read of all records for CSV export. */
    @Query("SELECT * FROM wifi_scan_results ORDER BY timestamp ASC")
    suspend fun getAll(): List<WifiScanResult>

    /** Distinct session IDs ordered by the earliest scan in each session. */
    @Query(
        """
        SELECT session_id, MIN(timestamp) as earliest
        FROM wifi_scan_results
        GROUP BY session_id
        ORDER BY earliest DESC
        """
    )
    fun observeSessionIds(): Flow<List<SessionSummary>>

    /** Results filtered by SSID (case-insensitive). */
    @Query(
        """
        SELECT * FROM wifi_scan_results
        WHERE ssid LIKE '%' || :ssid || '%'
        ORDER BY timestamp DESC
        """
    )
    fun observeBySsid(ssid: String): Flow<List<WifiScanResult>>

    /** Latest scan per SSID — useful for building the network list. */
    @Query(
        """
        SELECT * FROM wifi_scan_results
        WHERE id IN (
            SELECT MAX(id) FROM wifi_scan_results GROUP BY bssid
        )
        ORDER BY rssi DESC
        """
    )
    fun observeLatestPerNetwork(): Flow<List<WifiScanResult>>

    // ── Aggregates ───────────────────────────────────────────────────────────

    @Query("SELECT COUNT(*) FROM wifi_scan_results")
    fun observeCount(): Flow<Int>

    @Query("SELECT MAX(rssi) FROM wifi_scan_results WHERE session_id = :sessionId")
    suspend fun maxRssi(sessionId: String): Int?

    @Query("SELECT MIN(rssi) FROM wifi_scan_results WHERE session_id = :sessionId")
    suspend fun minRssi(sessionId: String): Int?
}

/**
 * Lightweight projection used for the session list screen.
 */
data class SessionSummary(
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "earliest")   val earliest: Long
)
