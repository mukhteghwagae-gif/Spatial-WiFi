package com.wifi.visualizer.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wifi.visualizer.data.repo.WifiScanRepository
import com.wifi.visualizer.intelligence.SessionAnalysis
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

class AnalyticsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = WifiScanRepository(app)

    private val _analysis  = MutableStateFlow<SessionAnalysis?>(null)
    val analysis: StateFlow<SessionAnalysis?> = _analysis

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    /** Pair(timestamp_ms, rssi_dbm) */
    private val _rssiTimeSeries = MutableStateFlow<List<Pair<Long, Float>>>(emptyList())
    val rssiTimeSeries: StateFlow<List<Pair<Long, Float>>> = _rssiTimeSeries

    fun loadSession(sessionId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            runCatching {
                val analysis = repo.analyzeSession(sessionId)
                _analysis.value = analysis

                // Build time series from signal samples
                val buckets = repo.getRssiByMinute(sessionId)
                _rssiTimeSeries.value = buckets.map { it.bucket to it.avgRssi }
            }
            _isLoading.value = false
        }
    }

    suspend fun export(sessionId: String, format: String): File = when (format) {
        "csv"  -> repo.exportCsv(sessionId)
        "json" -> repo.exportJson(sessionId)
        "kml"  -> repo.exportKml(sessionId)
        else   -> repo.exportHtmlReport(sessionId)
    }
}
