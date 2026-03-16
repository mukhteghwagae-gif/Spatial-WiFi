package com.wifi.visualizer.data.db

import androidx.room.*
import com.wifi.visualizer.data.model.SessionMetadata
import com.wifi.visualizer.data.model.SignalSample
import kotlinx.coroutines.flow.Flow

@Dao
interface SignalSampleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sample: SignalSample): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(samples: List<SignalSample>)

    @Query("SELECT * FROM signal_samples WHERE session_id = :sid ORDER BY timestamp ASC")
    fun observeBySession(sid: String): Flow<List<SignalSample>>

    @Query("SELECT * FROM signal_samples WHERE bssid = :bssid ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentByBssid(bssid: String, limit: Int = 60): List<SignalSample>

    /** Sliding window of RSSI values for sparkline chart. */
    @Query("""
        SELECT rssi FROM signal_samples
        WHERE session_id = :sid
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    suspend fun getRecentRssi(sid: String, limit: Int = 120): List<Int>

    /** Average RSSI grouped by minute for time-series chart. */
    @Query("""
        SELECT (timestamp / 60000) * 60000 AS bucket, AVG(rssi) AS avg_rssi
        FROM signal_samples
        WHERE session_id = :sid
        GROUP BY bucket
        ORDER BY bucket ASC
    """)
    suspend fun getRssiByMinute(sid: String): List<RssiBucket>

    @Query("DELETE FROM signal_samples WHERE session_id = :sid")
    suspend fun deleteBySession(sid: String)

    @Query("DELETE FROM signal_samples")
    suspend fun deleteAll()
}

data class RssiBucket(
    @ColumnInfo(name = "bucket")    val bucket: Long,
    @ColumnInfo(name = "avg_rssi") val avgRssi: Float
)

@Dao
interface SessionMetadataDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: SessionMetadata): Long

    @Update
    suspend fun update(meta: SessionMetadata)

    @Delete
    suspend fun delete(meta: SessionMetadata)

    @Query("SELECT * FROM sessions ORDER BY created_at DESC")
    fun observeAll(): Flow<List<SessionMetadata>>

    @Query("SELECT * FROM sessions WHERE session_id = :sid LIMIT 1")
    suspend fun getBySessionId(sid: String): SessionMetadata?

    @Query("UPDATE sessions SET scan_count = :count WHERE session_id = :sid")
    suspend fun updateScanCount(sid: String, count: Int)

    @Query("UPDATE sessions SET coverage_score = :score WHERE session_id = :sid")
    suspend fun updateCoverageScore(sid: String, score: Int)

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()
}
