package com.wifi.visualizer.data.repo

import android.content.Context
import com.wifi.visualizer.data.db.AppDatabase
import com.wifi.visualizer.data.db.RssiBucket
import com.wifi.visualizer.data.db.SessionSummary
import com.wifi.visualizer.data.model.SessionMetadata
import com.wifi.visualizer.data.model.SignalSample
import com.wifi.visualizer.data.model.WifiScanResult
import com.wifi.visualizer.intelligence.SessionAnalysis
import com.wifi.visualizer.intelligence.SignalIntelligenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Repository: single source of truth between the DAO layer and ViewModels.
 * Integrates [SignalIntelligenceEngine] for AI-powered analysis.
 */
class WifiScanRepository(context: Context) {

    private val db          = AppDatabase.getInstance(context)
    private val scanDao     = db.wifiScanDao()
    private val sampleDao   = db.signalSampleDao()
    private val sessionDao  = db.sessionMetadataDao()
    private val intelligence = SignalIntelligenceEngine()
    private val appContext  = context.applicationContext

    // ── Write ────────────────────────────────────────────────────────────────

    suspend fun insert(result: WifiScanResult): Long =
        withContext(Dispatchers.IO) { scanDao.insert(result) }

    suspend fun insertSample(sample: SignalSample): Long =
        withContext(Dispatchers.IO) { sampleDao.insert(sample) }

    suspend fun upsertSession(meta: SessionMetadata): Long =
        withContext(Dispatchers.IO) { sessionDao.upsert(meta) }

    suspend fun updateSessionLabel(sessionId: String, label: String) =
        withContext(Dispatchers.IO) {
            val meta = sessionDao.getBySessionId(sessionId) ?: return@withContext
            sessionDao.update(meta.copy(label = label))
        }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        scanDao.deleteAll()
        sampleDao.deleteAll()
        sessionDao.deleteAll()
    }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        scanDao.deleteSession(sessionId)
        sampleDao.deleteBySession(sessionId)
        val meta = sessionDao.getBySessionId(sessionId)
        meta?.let { sessionDao.delete(it) }
    }

    // ── Observe ──────────────────────────────────────────────────────────────

    fun observeAll(): Flow<List<WifiScanResult>>         = scanDao.observeAll()
    fun observeSession(sid: String)                       = scanDao.observeSession(sid)
    fun observeLatestPerNetwork()                         = scanDao.observeLatestPerNetwork()
    fun observeSessionIds(): Flow<List<SessionSummary>>  = scanDao.observeSessionIds()
    fun observeCount(): Flow<Int>                         = scanDao.observeCount()
    fun observeSessions()                                 = sessionDao.observeAll()
    fun observeSamples(sid: String)                       = sampleDao.observeBySession(sid)

    // ── Reads ────────────────────────────────────────────────────────────────

    suspend fun getSession(sid: String): List<WifiScanResult> =
        withContext(Dispatchers.IO) { scanDao.getSession(sid) }

    suspend fun getAll(): List<WifiScanResult> =
        withContext(Dispatchers.IO) { scanDao.getAll() }

    suspend fun getRssiByMinute(sid: String): List<RssiBucket> =
        withContext(Dispatchers.IO) { sampleDao.getRssiByMinute(sid) }

    suspend fun getRecentRssiList(sid: String, limit: Int = 120): List<Int> =
        withContext(Dispatchers.IO) { sampleDao.getRecentRssi(sid, limit) }

    // ── Intelligence ──────────────────────────────────────────────────────────

    /**
     * Run the full AI analysis pipeline on a session.
     * Returns a [SessionAnalysis] and also persists the coverage score.
     */
    suspend fun analyzeSession(sessionId: String): SessionAnalysis =
        withContext(Dispatchers.IO) {
            val scans = scanDao.getSession(sessionId)
            val analysis = intelligence.analyzeSession(scans)
            // Persist coverage score back to session metadata
            sessionDao.updateCoverageScore(sessionId, analysis.coverageScore)
            analysis
        }

    // ── Exports ───────────────────────────────────────────────────────────────

    suspend fun exportCsv(sessionId: String? = null): File =
        withContext(Dispatchers.IO) {
            val records = if (sessionId != null) scanDao.getSession(sessionId)
                          else scanDao.getAll()
            ExportManager.exportCsv(appContext, records, sessionId)
        }

    suspend fun exportJson(sessionId: String? = null): File =
        withContext(Dispatchers.IO) {
            val records = if (sessionId != null) scanDao.getSession(sessionId)
                          else scanDao.getAll()
            ExportManager.exportJson(appContext, records)
        }

    suspend fun exportKml(sessionId: String? = null): File =
        withContext(Dispatchers.IO) {
            val records = if (sessionId != null) scanDao.getSession(sessionId)
                          else scanDao.getAll()
            ExportManager.exportKml(appContext, records)
        }

    suspend fun exportHtmlReport(sessionId: String): File =
        withContext(Dispatchers.IO) {
            val records  = scanDao.getSession(sessionId)
            val analysis = intelligence.analyzeSession(records)
            ExportManager.exportHtmlReport(appContext, records, analysis)
        }
}

