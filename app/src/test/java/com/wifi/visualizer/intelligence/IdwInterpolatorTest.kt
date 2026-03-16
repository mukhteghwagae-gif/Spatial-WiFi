package com.wifi.visualizer.intelligence

import com.wifi.visualizer.data.model.WifiScanResult
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [IdwInterpolator].
 *
 * All tests are pure-JVM — no Android framework required.
 */
class IdwInterpolatorTest {

    private lateinit var idw: IdwInterpolator

    @Before
    fun setUp() {
        idw = IdwInterpolator(power = 2.0)
    }

    // ── predict() ────────────────────────────────────────────────────────────

    @Test
    fun `predict returns null for empty sample list`() {
        assertNull(idw.predict(0f, 0f, emptyList()))
    }

    @Test
    fun `predict returns exact sample value when query is on a sample point`() {
        val samples = listOf(makeScan(x = 0f, z = 0f, rssi = -55))
        // Distance < 0.001 → exact hit
        val result = idw.predict(0f, 0f, samples)
        assertNotNull(result)
        assertEquals(-55f, result!!, 0.001f)
    }

    @Test
    fun `predict interpolates between two equal-distance samples`() {
        val samples = listOf(
            makeScan(x = -1f, z = 0f, rssi = -60),
            makeScan(x =  1f, z = 0f, rssi = -80)
        )
        // Midpoint: equidistant, so result should be average
        val result = idw.predict(0f, 0f, samples)
        assertNotNull(result)
        assertEquals(-70f, result!!, 1.0f)
    }

    @Test
    fun `predict ignores samples beyond maxRadius`() {
        val nearSample = makeScan(x = 0f,  z = 0f,  rssi = -50)
        val farSample  = makeScan(x = 20f, z = 0f,  rssi = -30)
        val result = idw.predict(0f, 0f, listOf(nearSample, farSample), maxRadius = 8f)
        assertNotNull(result)
        // farSample is outside 8 m; result should equal the near sample's RSSI
        assertEquals(-50f, result!!, 0.001f)
    }

    @Test
    fun `predict returns null when only sample is beyond maxRadius`() {
        val far = makeScan(x = 100f, z = 100f, rssi = -70)
        assertNull(idw.predict(0f, 0f, listOf(far), maxRadius = 5f))
    }

    @Test
    fun `predict respects inverse-square weighting (closer sample dominates)`() {
        val close = makeScan(x = 0.5f, z = 0f, rssi = -40)
        val far   = makeScan(x = 5f,   z = 0f, rssi = -90)
        val result = idw.predict(0f, 0f, listOf(close, far))
        // Closer sample at 0.5 m should dominate; result much closer to -40
        assertNotNull(result)
        assertTrue("Expected result > -65, got $result", result!! > -65f)
    }

    // ── buildGrid() ──────────────────────────────────────────────────────────

    @Test
    fun `buildGrid returns null for empty samples`() {
        assertNull(idw.buildGrid(emptyList()))
    }

    @Test
    fun `buildGrid produces grid with positive dimensions`() {
        val samples = (1..6).map { i -> makeScan(x = i.toFloat(), z = i.toFloat(), rssi = -60) }
        val grid = idw.buildGrid(samples, cellSize = 0.5f, padding = 1)
        assertNotNull(grid)
        assertTrue(grid!!.cols > 0)
        assertTrue(grid.rows > 0)
    }

    @Test
    fun `buildGrid coverageFraction is between 0 and 1`() {
        val samples = listOf(
            makeScan(0f, 0f, -45),
            makeScan(1f, 0f, -55),
            makeScan(2f, 0f, -85),
            makeScan(0f, 1f, -90),
            makeScan(1f, 1f, -60)
        )
        val grid = idw.buildGrid(samples, cellSize = 0.5f)
        assertNotNull(grid)
        val frac = grid!!.coverageFraction(-70)
        assertTrue("Coverage fraction should be in [0,1], got $frac", frac in 0f..1f)
    }

    @Test
    fun `InterpolationGrid minRssi is less than or equal to maxRssi`() {
        val samples = listOf(
            makeScan(0f, 0f, -50),
            makeScan(1f, 0f, -90)
        )
        val grid = idw.buildGrid(samples)!!
        assertTrue(grid.minRssi <= grid.maxRssi)
    }

    @Test
    fun `InterpolationGrid equals is structurally correct`() {
        val samples = listOf(makeScan(0f, 0f, -60), makeScan(1f, 0f, -70))
        val g1 = idw.buildGrid(samples)!!
        val g2 = idw.buildGrid(samples)!!
        assertEquals(g1, g2)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun makeScan(x: Float, z: Float, rssi: Int) = WifiScanResult(
        ssid      = "TestNet",
        bssid     = "AA:BB:CC:DD:EE:FF",
        rssi      = rssi,
        frequency = 2412,
        arPosX    = x,
        arPosY    = 0f,
        arPosZ    = z,
        sessionId = "test-session"
    )
}
