package com.wifi.visualizer.intelligence

import com.wifi.visualizer.data.model.WifiScanResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SignalIntelligenceEngine].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignalIntelligenceEngineTest {

    private lateinit var engine: SignalIntelligenceEngine

    @Before
    fun setUp() {
        engine = SignalIntelligenceEngine()
    }

    // ── analyzeSession ────────────────────────────────────────────────────────

    @Test
    fun `analyzeSession with empty list returns empty analysis`() = runTest {
        val result = engine.analyzeSession(emptyList())
        assertEquals(0, result.sampleCount)
        assertEquals(0, result.coverageScore)
        assertTrue(result.insights.isEmpty())
        assertTrue(result.anomalies.isEmpty())
    }

    @Test
    fun `analyzeSession returns non-null result for minimal dataset`() = runTest {
        val scans = buildUniformGrid(rssi = -65)
        val result = engine.analyzeSession(scans)
        assertEquals(scans.size, result.sampleCount)
        assertTrue(result.coverageScore in 0..100)
    }

    @Test
    fun `analyzeSession coverage score is higher for strong signals`() = runTest {
        val strongScans = buildUniformGrid(rssi = -45)  // Excellent
        val weakScans   = buildUniformGrid(rssi = -90)  // Poor

        val strongAnalysis = engine.analyzeSession(strongScans)
        val weakAnalysis   = engine.analyzeSession(weakScans)

        assertTrue(
            "Strong signal should have higher coverage score. Strong=${strongAnalysis.coverageScore}, Weak=${weakAnalysis.coverageScore}",
            strongAnalysis.coverageScore > weakAnalysis.coverageScore
        )
    }

    @Test
    fun `analyzeSession signal stats are consistent`() = runTest {
        val rssis = listOf(-50, -60, -70, -80, -90)
        val scans = rssis.mapIndexed { i, r -> makeScan(x = i.toFloat(), z = 0f, rssi = r) }
        val result = engine.analyzeSession(scans)
        val stats  = result.signalStats

        assertEquals(rssis.min(), stats.min)
        assertEquals(rssis.max(), stats.max)
        assertEquals(rssis.average().toFloat(), stats.mean, 1.0f)
        assertTrue(stats.stdDev >= 0f)
    }

    @Test
    fun `analyzeSession detects anomalies for outlier RSSI values`() = runTest {
        // 9 normal readings + 1 extreme outlier
        val scans = (1..9).map { i -> makeScan(x = i.toFloat(), z = 0f, rssi = -65) } +
                    listOf(makeScan(x = 10f, z = 0f, rssi = -20))  // extreme outlier
        val result = engine.analyzeSession(scans)
        assertTrue("Expected at least one anomaly", result.anomalies.isNotEmpty())
    }

    @Test
    fun `analyzeSession trend is IMPROVING when later values are stronger`() = runTest {
        val scans = (1..10).map { i ->
            // RSSI improves from -90 to -50 as time progresses (id acts as time proxy via order)
            makeScan(x = i.toFloat(), z = 0f, rssi = -90 + i * 4,
                     timestampMs = System.currentTimeMillis() + i * 1000L)
        }
        val result = engine.analyzeSession(scans)
        assertEquals(SignalTrend.IMPROVING, result.signalTrend)
    }

    @Test
    fun `analyzeSession trend is DEGRADING when later values are weaker`() = runTest {
        val base = System.currentTimeMillis()
        val scans = (1..10).map { i ->
            makeScan(x = i.toFloat(), z = 0f, rssi = -40 - i * 5, timestampMs = base + i * 1000L)
        }
        val result = engine.analyzeSession(scans)
        assertEquals(SignalTrend.DEGRADING, result.signalTrend)
    }

    @Test
    fun `analyzeSession direction map has eight compass entries`() = runTest {
        val scans = buildCircularSamples()
        val result = engine.analyzeSession(scans)
        assertEquals(8, result.directionStrengths.size)
        val keys = result.directionStrengths.keys
        assertTrue(keys.containsAll(listOf("N","NE","E","SE","S","SW","W","NW")))
    }

    @Test
    fun `analyzeSession interpolationGrid is non-null for sufficient data`() = runTest {
        val scans = buildUniformGrid(rssi = -65)
        val result = engine.analyzeSession(scans)
        assertNotNull("Grid should be computed for ${scans.size} scan points", result.interpolationGrid)
    }

    // ── predictAt ─────────────────────────────────────────────────────────────

    @Test
    fun `predictAt returns null for empty samples`() {
        assertNull(engine.predictAt(0f, 0f, emptyList()))
    }

    @Test
    fun `predictAt returns value in dBm range for valid samples`() {
        val samples = buildUniformGrid(-60)
        val result  = engine.predictAt(1.5f, 1.5f, samples)
        assertNotNull(result)
        assertTrue("Predicted RSSI should be negative dBm, got $result", result!! < 0f)
        assertTrue("Predicted RSSI should be > -120, got $result", result > -120f)
    }

    // ── strongestSignalDirection ──────────────────────────────────────────────

    @Test
    fun `strongestSignalDirection returns null for empty samples`() {
        assertNull(engine.strongestSignalDirection(0f, 0f, emptyList()))
    }

    @Test
    fun `strongestSignalDirection returns value in 0-360 range`() {
        val samples = listOf(
            makeScan(0f, 0f, -50),
            makeScan(3f, 0f, -70),
            makeScan(0f, 3f, -80)
        )
        val dir = engine.strongestSignalDirection(0f, 0f, samples)
        assertNotNull(dir)
        assertTrue("Angle should be 0-360, got $dir", dir!! in 0f..360f)
    }

    // ── PathLossModel calibration ─────────────────────────────────────────────

    @Test
    fun `PathLossModel calibrate returns valid n exponent from realistic data`() {
        // Simulate measurements: RSSI drops with distance (realistic log-distance)
        val measurements = listOf(
            1f to -40, 2f to -46, 3f to -50, 5f to -54, 8f to -59, 12f to -63
        )
        val model = PathLossModel.calibrate(measurements)
        assertTrue("n should be between 1.5 and 5.0, got ${model.n}", model.n in 1.5f..5.0f)
    }

    @Test
    fun `PathLossModel predict decreases with distance`() {
        val model = PathLossModel(rssiAtOneMetre = -40f, n = 2.8f)
        val rssi1m  = model.predict(1f)
        val rssi5m  = model.predict(5f)
        val rssi10m = model.predict(10f)
        assertTrue("RSSI at 1m should be > at 5m",  rssi1m  > rssi5m)
        assertTrue("RSSI at 5m should be > at 10m", rssi5m  > rssi10m)
    }

    @Test
    fun `PathLossModel distanceFrom is inverse of predict`() {
        val model = PathLossModel(rssiAtOneMetre = -40f, n = 2.8f)
        val expectedDist = 4f
        val rssi = model.predict(expectedDist)
        val calcDist = model.distanceFrom(rssi.toInt())
        assertEquals(expectedDist, calcDist, 0.5f)
    }

    // ── ChannelAnalyzer ───────────────────────────────────────────────────────

    @Test
    fun `ChannelAnalyzer freqToChannel24 maps 2412 to channel 1`() {
        val analyzer = ChannelAnalyzer()
        assertEquals(1, analyzer.freqToChannel24(2412))
    }

    @Test
    fun `ChannelAnalyzer freqToChannel24 maps 2437 to channel 6`() {
        val analyzer = ChannelAnalyzer()
        assertEquals(6, analyzer.freqToChannel24(2437))
    }

    @Test
    fun `ChannelAnalyzer freqToChannel24 maps 2462 to channel 11`() {
        val analyzer = ChannelAnalyzer()
        assertEquals(11, analyzer.freqToChannel24(2462))
    }

    @Test
    fun `ChannelAnalyzer analyze returns empty recommendations for no networks`() {
        val analyzer = ChannelAnalyzer()
        val report   = analyzer.analyze(emptyList())
        assertEquals(0, report.totalNetworksVisible)
        assertTrue(report.networks24GHz.isEmpty())
        assertTrue(report.networks5GHz.isEmpty())
    }

    // ── DeadZoneDetector ──────────────────────────────────────────────────────

    @Test
    fun `DeadZoneDetector analyze returns empty report for fewer than 5 samples`() {
        val detector = DeadZoneDetector()
        val report   = detector.analyze(listOf(makeScan(0f, 0f, -70)))
        assertTrue(report.deadZones.isEmpty())
        assertTrue(report.meshRecommendations.isEmpty())
    }

    @Test
    fun `DeadZoneDetector coverage grade is valid for all-excellent data`() {
        val detector = DeadZoneDetector()
        val scans    = buildUniformGrid(rssi = -45)
        val report   = detector.analyze(scans)
        // All-excellent signal → high coverage, grade should be A or B
        assertTrue(
            "Expected grade A or B, got ${report.coverageGrade}",
            report.coverageGrade.startsWith("A") || report.coverageGrade.startsWith("B")
        )
    }

    @Test
    fun `DeadZoneDetector detects dead zones for all-poor data`() {
        val detector = DeadZoneDetector()
        val scans    = buildUniformGrid(rssi = -95)   // all dead
        val report   = detector.analyze(scans, thresholdDbm = -75)
        assertTrue("Expected dead zones for all-poor signal", report.deadZones.isNotEmpty())
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** 5×5 regular grid of scan points. */
    private fun buildUniformGrid(rssi: Int): List<WifiScanResult> =
        (0..4).flatMap { row ->
            (0..4).map { col ->
                makeScan(x = col.toFloat(), z = row.toFloat(), rssi = rssi)
            }
        }

    /** 8 samples spread in a circle for direction map testing. */
    private fun buildCircularSamples(): List<WifiScanResult> {
        val r = 3f
        return (0 until 8).map { i ->
            val angle = Math.toRadians(i * 45.0)
            makeScan(
                x    = (Math.cos(angle) * r).toFloat(),
                z    = (Math.sin(angle) * r).toFloat(),
                rssi = -60 - i * 3
            )
        }
    }

    private fun makeScan(
        x:           Float,
        z:           Float,
        rssi:        Int,
        timestampMs: Long = System.currentTimeMillis()
    ) = WifiScanResult(
        ssid        = "TestNet",
        bssid       = "AA:BB:CC:DD:EE:FF",
        rssi        = rssi,
        frequency   = 2412,
        arPosX      = x,
        arPosY      = 0f,
        arPosZ      = z,
        sessionId   = "test-session",
        timestamp   = timestampMs
    )
}
