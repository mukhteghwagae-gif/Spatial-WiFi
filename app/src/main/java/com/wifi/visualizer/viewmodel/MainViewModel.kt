package com.wifi.visualizer.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wifi.visualizer.data.model.WifiScanResult
import com.wifi.visualizer.data.repo.WifiScanRepository
import com.wifi.visualizer.utils.WifiScanner
import com.wifi.visualizer.utils.WifiSnapshot
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Shared ViewModel for MainActivity and session management.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo    = WifiScanRepository(app)
    private val scanner = WifiScanner(app)

    var currentSessionId: String = UUID.randomUUID().toString()
        private set

    fun startNewSession() { currentSessionId = UUID.randomUUID().toString() }

    private val _wifiSnapshot = MutableStateFlow<WifiSnapshot?>(null)
    val wifiSnapshot: StateFlow<WifiSnapshot?> = _wifiSnapshot.asStateFlow()

    private val _selectedSsid = MutableStateFlow<String?>(null)
    val selectedSsid: StateFlow<String?> = _selectedSsid.asStateFlow()

    fun selectSsid(ssid: String?) { _selectedSsid.value = ssid }

    val allScans       = repo.observeAll()
    val latestNetworks = repo.observeLatestPerNetwork()
    val sessionIds     = repo.observeSessionIds()
    val scanCount      = repo.observeCount()

    private val _exportResult = MutableSharedFlow<Result<File>>()
    val exportResult: SharedFlow<Result<File>> = _exportResult

    fun startScanning() {
        viewModelScope.launch { scanner.observe().collect { _wifiSnapshot.value = it } }
    }

    fun triggerScan() = scanner.triggerScan()

    fun saveScan(arX: Float, arY: Float, arZ: Float, lat: Double = 0.0, lng: Double = 0.0) {
        val snap = _wifiSnapshot.value ?: return
        val ssid = _selectedSsid.value ?: snap.connectedSsid
        val result = WifiScanResult(
            ssid = ssid, bssid = snap.connectedBssid, rssi = snap.rssi,
            frequency = snap.frequency, linkSpeed = snap.linkSpeed,
            latitude = lat, longitude = lng, arPosX = arX, arPosY = arY, arPosZ = arZ,
            sessionId = currentSessionId
        )
        viewModelScope.launch { repo.insert(result) }
    }

    fun exportAllCsv() {
        viewModelScope.launch {
            runCatching { repo.exportCsv() }.let { _exportResult.emit(it) }
        }
    }

    fun exportSessionCsv(sessionId: String) {
        viewModelScope.launch {
            runCatching { repo.exportCsv(sessionId) }.let { _exportResult.emit(it) }
        }
    }

    fun deleteAllData() { viewModelScope.launch { repo.deleteAll() } }
    fun deleteSession(sessionId: String) { viewModelScope.launch { repo.deleteSession(sessionId) } }
    fun observeSession(sessionId: String) = repo.observeSession(sessionId)
}
