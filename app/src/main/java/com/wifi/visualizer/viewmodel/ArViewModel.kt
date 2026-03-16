package com.wifi.visualizer.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wifi.visualizer.WiFiVisualizerApp
import com.wifi.visualizer.data.model.SessionMetadata
import com.wifi.visualizer.data.model.SignalSample
import com.wifi.visualizer.data.model.WifiScanResult
import com.wifi.visualizer.data.repo.WifiScanRepository
import com.wifi.visualizer.utils.WifiScanner
import com.wifi.visualizer.utils.WifiSnapshot
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ViewModel for [ArMappingActivity]. Scoped to the AR session lifecycle.
 */
class ArViewModel(app: Application) : AndroidViewModel(app) {

    private val repo    = WifiScanRepository(app)
    private val scanner = WifiScanner(app)

    var currentSessionId: String = UUID.randomUUID().toString()
        private set

    private val _wifiSnapshot = MutableStateFlow<WifiSnapshot?>(null)
    val wifiSnapshot: StateFlow<WifiSnapshot?> = _wifiSnapshot.asStateFlow()

    private val _selectedSsid = MutableStateFlow<String?>(null)
    val selectedSsid: StateFlow<String?> = _selectedSsid.asStateFlow()

    init {
        viewModelScope.launch { scanner.observe().collect { _wifiSnapshot.value = it } }
        // Create session metadata
        viewModelScope.launch {
            repo.upsertSession(SessionMetadata(sessionId = currentSessionId))
        }
    }

    fun triggerScan() = scanner.triggerScan()

    fun saveScan(arX: Float, arY: Float, arZ: Float, lat: Double = 0.0, lng: Double = 0.0) {
        val snap = _wifiSnapshot.value ?: return
        viewModelScope.launch {
            val result = WifiScanResult(
                ssid = snap.connectedSsid, bssid = snap.connectedBssid,
                rssi = snap.rssi, frequency = snap.frequency, linkSpeed = snap.linkSpeed,
                latitude = lat, longitude = lng,
                arPosX = arX, arPosY = arY, arPosZ = arZ,
                sessionId = currentSessionId
            )
            val id = repo.insert(result)
            // Also record a rapid sample for trend/sparkline
            repo.insertSample(SignalSample(
                bssid = snap.connectedBssid, ssid = snap.connectedSsid,
                rssi = snap.rssi, frequency = snap.frequency, linkSpeed = snap.linkSpeed,
                sessionId = currentSessionId
            ))
        }
    }
}
