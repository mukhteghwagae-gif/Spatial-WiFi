package com.wifi.visualizer.intelligence

import com.wifi.visualizer.data.model.WifiScanResult
import com.wifi.visualizer.utils.NearbyNetwork
import com.wifi.visualizer.utils.WifiSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Master intelligence orchestrator.
 *
 * Combines IDW interpolation, path-loss modelling, dead-zone detection,
 * channel analysis, and real-time trend analysis into a single API that
 * ViewModels can call.
 *
 * All heavy computation is dispatched to [Dispatchers.Default].
 */
class SignalIntelligenceEngine {

    private val idw             = IdwInterpolator(power = 2.0)
    private val channelAnalyzer = ChannelAnalyzer()
    private val deadZoneDetector = DeadZoneDetector()

    // ── Full session analysis ─────────────────────────────────────────────────

    /**
     * Run the complete analysis pipeline on a set of scan results.
     * This is the "AI Summary" that appears on the Analytics dashboard.
     */
    suspend fun analyzeSession(
        scans       : List<WifiScanResult>,
        nearbyNets  : List<NearbyNetwork> = emptyList(),
        environment : EnvironmentType      = EnvironmentType.AUTO_DETECT
    ): SessionAnalysis = withContext(Dispatchers.Default) {

        if (scans.isEmpty()) return@withContext SessionAnalysis.empty()

        // 1. Calibrate path-loss model from data
        val pathLossModel = calibratePathLoss(scans, environment)

        // 2. Interpolate full grid
        val grid = idw.buildGrid(scans, cellSize = 0.4f)

        // 3. Dead zone analysis
        val deadZoneReport = deadZoneDetector.analyze(scans)

        // 4. Channel analysis
        val channelReport = if (nearbyNets.isNotEmpty())
            channelAnalyzer.analyze(nearbyNets)
        else null

        // 5. Signal statistics
        val stats = computeSignalStats(scans)

        // 6. Signal stability (how much variance per location)
        val stability = computeStability(scans)

        // 7. Coverage direction analysis
        val directionMap = computeDirectionStrengths(scans)

        // 8. Anomaly detection
        val anomalies = detectAnomalies(scans)

        // 9. Trend (improving / degrading)
        val trend = computeTrend(scans)

        // 10. Smart insights
        val insights = generateInsights(stats, deadZoneReport, channelReport, stability, trend)

        SessionAnalysis(
            sampleCount       = scans.size,
            signalStats       = stats,
            pathLossModel     = pathLossModel,
            interpolationGrid = grid,
            deadZoneReport    = deadZoneReport,
            channelReport     = channelReport,
            stabilityScore    = stability,
            directionStrengths = directionMap,
            anomalies         = anomalies,
            signalTrend       = trend,
            insights          = insights,
            coverageScore     = grid?.coverageFraction(-70)?.times(100)?.toInt() ?: 0
        )
    }

    // ── Real-time prediction ──────────────────────────────────────────────────

    /**
     * Predict signal at an unsampled location using IDW.
     * Called every frame from the AR renderer to decide pillar height.
     */
    fun predictAt(x: Float, z: Float, samples: List<WifiScanResult>): Float? =
        idw.predict(x, z, samples)

    /**
     * Return the direction (angle in degrees, 0=+Z) toward the strongest
     * signal from the current position.
     */
    fun strongestSignalDirection(
        fromX: Float, fromZ: Float,
        samples: List<WifiScanResult>
    ): Float? {
        if (samples.isEmpty()) return null
        val best = samples.maxByOrNull { it.rssi } ?: return null
        val dx = best.arPosX - fromX
        val dz = best.arPosZ - fromZ
        return (Math.toDegrees(Math.atan2(dx.toDouble(), dz.toDouble())).toFloat() + 360f) % 360f
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun calibratePathLoss(
        scans: List<WifiScanResult>,
        env: EnvironmentType
    ): PathLossModel {
        // Try to self-calibrate if we have enough spread of RSSI values
        if (scans.size >= 10) {
            val origin = scans.maxByOrNull { it.rssi } ?: return PathLossModel.HOME_DRYWALL
            val measurements = scans.map { s ->
                val dx = s.arPosX - origin.arPosX
                val dz = s.arPosZ - origin.arPosZ
                val dist = sqrt(dx * dx + dz * dz).coerceAtLeast(0.1f)
                dist to s.rssi
            }
            return PathLossModel.calibrate(measurements)
        }
        return when (env) {
            EnvironmentType.OPEN_OFFICE   -> PathLossModel.OPEN_OFFICE
            EnvironmentType.HOME_DRYWALL  -> PathLossModel.HOME_DRYWALL
            EnvironmentType.HOME_CONCRETE -> PathLossModel.HOME_CONCRETE
            EnvironmentType.INDUSTRIAL    -> PathLossModel.INDUSTRIAL
            EnvironmentType.FREE_SPACE    -> PathLossModel.FREE_SPACE
            EnvironmentType.AUTO_DETECT   -> autoDetectEnvironment(scans)
        }
    }

    private fun autoDetectEnvironment(scans: List<WifiScanResult>): PathLossModel {
        if (scans.size < 5) return PathLossModel.HOME_DRYWALL
        // Heuristic: compute RSSI variance. High variance → obstructed environment
        val mean = scans.map { it.rssi }.average()
        val variance = scans.map { (it.rssi - mean).let { d -> d * d } }.average()
        return when {
            variance < 20  -> PathLossModel.FREE_SPACE
            variance < 60  -> PathLossModel.OPEN_OFFICE
            variance < 120 -> PathLossModel.HOME_DRYWALL
            else           -> PathLossModel.HOME_CONCRETE
        }
    }

    private fun computeSignalStats(scans: List<WifiScanResult>): SignalStats {
        val rssis = scans.map { it.rssi }
        val mean  = rssis.average()
        val variance = rssis.map { (it - mean).let { d -> d * d } }.average()
        return SignalStats(
            min      = rssis.min(),
            max      = rssis.max(),
            mean     = mean.toFloat(),
            median   = rssis.sorted().let { it[it.size / 2] },
            stdDev   = sqrt(variance).toFloat(),
            p25      = rssis.sorted().let { it[it.size / 4] },
            p75      = rssis.sorted().let { it[it.size * 3 / 4] }
        )
    }

    private fun computeStability(scans: List<WifiScanResult>): Int {
        if (scans.size < 3) return 50
        // Group nearby scans (within 1m) and measure variance within group
        val variances = mutableListOf<Double>()
        for (i in scans.indices) {
            val neighbours = scans.filter { other ->
                val dx = other.arPosX - scans[i].arPosX
                val dz = other.arPosZ - scans[i].arPosZ
                sqrt(dx * dx + dz * dz) < 1.0f
            }
            if (neighbours.size >= 2) {
                val mean = neighbours.map { it.rssi }.average()
                val variance = neighbours.map { (it.rssi - mean).let { d -> d * d } }.average()
                variances.add(variance)
            }
        }
        if (variances.isEmpty()) return 75
        val avgVariance = variances.average()
        // Low variance → high stability. Score 0-100.
        return (100 - avgVariance.coerceIn(0.0, 100.0)).toInt()
    }

    private fun computeDirectionStrengths(scans: List<WifiScanResult>): Map<String, Float> {
        if (scans.isEmpty()) return emptyMap()
        val cx = scans.map { it.arPosX }.average().toFloat()
        val cz = scans.map { it.arPosZ }.average().toFloat()
        val dirs = mapOf("N" to 0.0, "NE" to 45.0, "E" to 90.0, "SE" to 135.0,
                         "S" to 180.0, "SW" to 225.0, "W" to 270.0, "NW" to 315.0)
        return dirs.mapValues { (_, angle) ->
            val inSector = scans.filter { s ->
                val dx = s.arPosX - cx; val dz = s.arPosZ - cz
                val scanAngle = (Math.toDegrees(Math.atan2(dx.toDouble(), dz.toDouble())) + 360) % 360
                abs(scanAngle - angle) < 22.5 || abs(scanAngle - angle) > 337.5
            }
            inSector.map { it.rssi }.average().takeIf { !it.isNaN() }?.toFloat() ?: -90f
        }
    }

    private fun detectAnomalies(scans: List<WifiScanResult>): List<SignalAnomaly> {
        val anomalies = mutableListOf<SignalAnomaly>()
        if (scans.size < 5) return anomalies
        val mean   = scans.map { it.rssi }.average()
        val stdDev = sqrt(scans.map { (it.rssi - mean).let { d -> d * d } }.average())
        for (scan in scans) {
            val zScore = abs(scan.rssi - mean) / stdDev.coerceAtLeast(1.0)
            if (zScore > 2.5) {
                anomalies.add(SignalAnomaly(
                    x         = scan.arPosX,
                    z         = scan.arPosZ,
                    rssi      = scan.rssi,
                    zScore    = zScore.toFloat(),
                    type      = if (scan.rssi > mean) AnomalyType.UNUSUALLY_STRONG
                                else                  AnomalyType.UNUSUALLY_WEAK,
                    timestamp = scan.timestamp
                ))
            }
        }
        return anomalies
    }

    private fun computeTrend(scans: List<WifiScanResult>): SignalTrend {
        if (scans.size < 6) return SignalTrend.STABLE
        val sorted    = scans.sortedBy { it.timestamp }
        val firstHalf = sorted.take(sorted.size / 2).map { it.rssi }.average()
        val lastHalf  = sorted.takeLast(sorted.size / 2).map { it.rssi }.average()
        val delta     = lastHalf - firstHalf
        return when {
            delta > 5  -> SignalTrend.IMPROVING
            delta < -5 -> SignalTrend.DEGRADING
            else       -> SignalTrend.STABLE
        }
    }

    private fun generateInsights(
        stats        : SignalStats,
        deadZones    : DeadZoneReport,
        channelReport: ChannelReport?,
        stability    : Int,
        trend        : SignalTrend
    ): List<Insight> = buildList {
        // Coverage grade
        add(Insight(
            type    = InsightType.COVERAGE,
            title   = "Coverage Grade: ${deadZones.coverageGrade.split(" — ")[0]}",
            detail  = "${deadZones.coveragePercent.toInt()}% of mapped area has acceptable signal (>-70 dBm). ${deadZones.coverageGrade}",
            severity = if (deadZones.coveragePercent > 75) InsightSeverity.INFO else InsightSeverity.WARNING
        ))
        // Signal variance
        if (stats.stdDev > 15) add(Insight(
            type     = InsightType.STABILITY,
            title    = "High Signal Variance",
            detail   = "Std dev ${stats.stdDev.toInt()} dB suggests thick walls or interference. Consider 5 GHz band.",
            severity = InsightSeverity.WARNING
        ))
        // Stability
        add(Insight(
            type     = InsightType.STABILITY,
            title    = "Signal Stability: $stability%",
            detail   = when {
                stability > 80 -> "Very stable channel — good for video calls and gaming."
                stability > 60 -> "Moderately stable. Occasional interference may cause latency spikes."
                else           -> "Unstable signal. Avoid bandwidth-intensive tasks in weak zones."
            },
            severity = if (stability > 70) InsightSeverity.INFO else InsightSeverity.WARNING
        ))
        // Dead zones
        if (deadZones.hasDeadZones) add(Insight(
            type     = InsightType.DEAD_ZONE,
            title    = "${deadZones.deadZones.size} Dead Zone(s) Detected",
            detail   = "~${deadZones.deadZoneAreaM2.toInt()} m² of mapped area has poor/no signal. " +
                       "See Mesh Optimizer for recommended repeater placements.",
            severity = InsightSeverity.ACTION_REQUIRED
        ))
        // Trend
        add(Insight(
            type     = InsightType.TREND,
            title    = "Signal Trend: ${trend.label}",
            detail   = trend.description,
            severity = when(trend) {
                SignalTrend.IMPROVING -> InsightSeverity.INFO
                SignalTrend.STABLE    -> InsightSeverity.INFO
                SignalTrend.DEGRADING -> InsightSeverity.WARNING
            }
        ))
        // Channel recommendation
        channelReport?.let { cr ->
            if (cr.recommendedChannels24.isNotEmpty()) add(Insight(
                type     = InsightType.CHANNEL,
                title    = "Recommended 2.4 GHz Channels: ${cr.recommendedChannels24.take(2).joinToString(", ")}",
                detail   = "Current congestion: ${cr.congestionLabel}. " +
                           "${cr.totalNetworksVisible} networks visible.",
                severity = InsightSeverity.INFO
            ))
        }
    }
}

// ── Enums and data classes ────────────────────────────────────────────────────

enum class EnvironmentType {
    AUTO_DETECT, OPEN_OFFICE, HOME_DRYWALL, HOME_CONCRETE, INDUSTRIAL, FREE_SPACE
}

enum class SignalTrend(val label: String, val description: String) {
    IMPROVING("Improving ↑", "Signal generally improved as you moved during this session."),
    STABLE   ("Stable →",    "Signal was consistent throughout the session."),
    DEGRADING("Degrading ↓", "Signal worsened during the session — check for interference sources.")
}

enum class AnomalyType { UNUSUALLY_STRONG, UNUSUALLY_WEAK }

enum class InsightType { COVERAGE, STABILITY, DEAD_ZONE, CHANNEL, TREND, PERFORMANCE }
enum class InsightSeverity { INFO, WARNING, ACTION_REQUIRED }

data class SignalStats(
    val min    : Int,
    val max    : Int,
    val mean   : Float,
    val median : Int,
    val stdDev : Float,
    val p25    : Int,
    val p75    : Int
) {
    val range: Int get() = max - min
    val dynamicRangeDb: Int get() = range
}

data class SignalAnomaly(
    val x         : Float,
    val z         : Float,
    val rssi      : Int,
    val zScore    : Float,
    val type      : AnomalyType,
    val timestamp : Long
)

data class Insight(
    val type     : InsightType,
    val title    : String,
    val detail   : String,
    val severity : InsightSeverity
)

data class SessionAnalysis(
    val sampleCount        : Int,
    val signalStats        : SignalStats,
    val pathLossModel      : PathLossModel,
    val interpolationGrid  : InterpolationGrid?,
    val deadZoneReport     : DeadZoneReport,
    val channelReport      : ChannelReport?,
    val stabilityScore     : Int,
    val directionStrengths : Map<String, Float>,
    val anomalies          : List<SignalAnomaly>,
    val signalTrend        : SignalTrend,
    val insights           : List<Insight>,
    val coverageScore      : Int
) {
    companion object {
        fun empty() = SessionAnalysis(
            sampleCount = 0,
            signalStats = SignalStats(0, 0, 0f, 0, 0f, 0, 0),
            pathLossModel = PathLossModel.HOME_DRYWALL,
            interpolationGrid = null,
            deadZoneReport = DeadZoneReport(emptyList(), emptyList(), 0f, 0f, 0),
            channelReport = null,
            stabilityScore = 0,
            directionStrengths = emptyMap(),
            anomalies = emptyList(),
            signalTrend = SignalTrend.STABLE,
            insights = emptyList(),
            coverageScore = 0
        )
    }
}
