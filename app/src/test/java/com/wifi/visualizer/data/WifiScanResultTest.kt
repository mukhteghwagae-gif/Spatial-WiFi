package com.wifi.visualizer.data

import com.wifi.visualizer.data.model.SignalQuality
import com.wifi.visualizer.data.model.WifiScanResult
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [WifiScanResult] and [SignalQuality].
 */
class WifiScanResultTest {

    // ── SignalQuality.from() ──────────────────────────────────────────────────

    @Test fun `from returns EXCELLENT for rssi above -50`() =
        assertEquals(SignalQuality.EXCELLENT, SignalQuality.from(-49))

    @Test fun `from returns EXCELLENT at exactly -50 boundary`() =
        assertEquals(SignalQuality.EXCELLENT, SignalQuality.from(-50))

    @Test fun `from returns GOOD for rssi between -51 and -70`() {
        assertEquals(SignalQuality.GOOD, SignalQuality.from(-51))
        assertEquals(SignalQuality.GOOD, SignalQuality.from(-65))
        assertEquals(SignalQuality.GOOD, SignalQuality.from(-70))
    }

    @Test fun `from returns FAIR for rssi between -71 and -80`() {
        assertEquals(SignalQuality.FAIR, SignalQuality.from(-71))
        assertEquals(SignalQuality.FAIR, SignalQuality.from(-75))
        assertEquals(SignalQuality.FAIR, SignalQuality.from(-80))
    }

    @Test fun `from returns POOR for rssi below -80`() {
        assertEquals(SignalQuality.POOR, SignalQuality.from(-81))
        assertEquals(SignalQuality.POOR, SignalQuality.from(-100))
    }

    @Test fun `from handles extreme values without throwing`() {
        assertNotNull(SignalQuality.from(0))
        assertNotNull(SignalQuality.from(-127))
        assertNotNull(SignalQuality.from(Int.MIN_VALUE))
        assertNotNull(SignalQuality.from(Int.MAX_VALUE))
    }

    // ── WifiScanResult.band ───────────────────────────────────────────────────

    @Test fun `band is 2_4 GHz for 2412 MHz`() =
        assertEquals("2.4 GHz", makeScan(frequency = 2412).band)

    @Test fun `band is 2_4 GHz for 2484 MHz`() =
        assertEquals("2.4 GHz", makeScan(frequency = 2484).band)

    @Test fun `band is 5 GHz for 5180 MHz`() =
        assertEquals("5 GHz", makeScan(frequency = 5180).band)

    @Test fun `band is 5 GHz for 5825 MHz`() =
        assertEquals("5 GHz", makeScan(frequency = 5825).band)

    @Test fun `band is 6 GHz for 5955 MHz`() =
        assertEquals("6 GHz", makeScan(frequency = 5955).band)

    // ── WifiScanResult.signalQuality ──────────────────────────────────────────

    @Test fun `signalQuality delegates to SignalQuality from`() {
        val scan = makeScan(rssi = -55)
        assertEquals(SignalQuality.from(-55), scan.signalQuality)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun makeScan(rssi: Int = -65, frequency: Int = 2412) = WifiScanResult(
        ssid      = "TestNet",
        bssid     = "AA:BB:CC:DD:EE:FF",
        rssi      = rssi,
        frequency = frequency
    )
}
