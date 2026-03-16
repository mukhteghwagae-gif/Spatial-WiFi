package com.wifi.visualizer.intelligence

import kotlin.math.log10
import kotlin.math.pow

/**
 * IEEE 802.11 Log-Distance Path Loss Model.
 *
 * Models RSSI as a function of distance from an access point:
 *
 *   RSSI(d) = RSSI(d₀) - 10·n·log₁₀(d/d₀) - X_σ
 *
 * where:
 *   d₀    = reference distance (typically 1 m)
 *   n     = path-loss exponent (environment-dependent)
 *   X_σ   = zero-mean Gaussian shadowing term (standard deviation σ)
 *
 * Path-loss exponent n by environment:
 *   Free space          : 2.0
 *   Open office         : 2.0–2.5
 *   Cluttered office    : 2.7–3.5
 *   Home (drywall)      : 2.5–3.0
 *   Home (concrete)     : 3.5–4.5
 *   Warehouse           : 2.4–2.8
 *
 * Usage:
 *   val model = PathLossModel.calibrate(measurements)
 *   val predictedRssi = model.predict(distanceMetres)
 *   val predictedDist = model.distanceFrom(rssiDbm)
 */
class PathLossModel(
    /** RSSI at reference distance d₀ = 1 m (dBm). */
    val rssiAtOneMetre: Float = -40f,
    /** Path-loss exponent. */
    val n: Float = 3.0f,
    /** Shadow fading std-dev (dB). Higher = more unpredictable channel. */
    val shadowingStdDev: Float = 4f,
    /** Estimated AP transmit power + antenna gain (dBm). */
    val txPower: Float = 20f
) {

    // ── Prediction ────────────────────────────────────────────────────────────

    /**
     * Predict RSSI at [distanceMetres] from the AP.
     */
    fun predict(distanceMetres: Float): Float {
        if (distanceMetres <= 0f) return rssiAtOneMetre
        return rssiAtOneMetre - 10f * n * log10(distanceMetres.toDouble()).toFloat()
    }

    /**
     * Estimate distance (metres) from a measured RSSI.
     *
     * Inverse of [predict]: d = 10^((RSSI₀ - RSSI) / (10·n))
     */
    fun distanceFrom(rssiDbm: Int): Float {
        if (rssiDbm >= rssiAtOneMetre) return 0.5f  // very close
        val exponent = (rssiAtOneMetre - rssiDbm) / (10f * n)
        return 10f.pow(exponent).coerceIn(0.5f, 200f)
    }

    /**
     * Coverage radius at which RSSI drops below [thresholdDbm].
     */
    fun coverageRadius(thresholdDbm: Int = -70): Float =
        distanceFrom(thresholdDbm)

    /**
     * Signal quality percentage [0–100] derived from RSSI.
     */
    fun signalQualityPercent(rssiDbm: Int): Int {
        // Clamp to [-100, -30] then linearly map to [0, 100]
        val clamped = rssiDbm.coerceIn(-100, -30)
        return ((clamped + 100) * 100 / 70).coerceIn(0, 100)
    }

    // ── Calibration ───────────────────────────────────────────────────────────

    companion object {

        /**
         * Calibrate a [PathLossModel] from a list of (distance_m, rssi_dBm) pairs
         * using least-squares regression on the log-distance relationship.
         *
         * Requires at least 3 distinct distance samples for a meaningful fit.
         */
        fun calibrate(
            measurements: List<Pair<Float, Int>>,
            n: Float = 3.0f
        ): PathLossModel {
            if (measurements.size < 3) {
                return PathLossModel(n = n)   // fallback to defaults
            }

            // Linear regression: RSSI = A + B·log₁₀(d)
            // where A = rssiAtOneMetre (intercept), B = -10n (slope)
            val xs = measurements.map { log10(it.first.toDouble().coerceAtLeast(0.1)) }
            val ys = measurements.map { it.second.toDouble() }
            val xMean = xs.average()
            val yMean = ys.average()

            val slope = xs.zip(ys).sumOf { (x, y) -> (x - xMean) * (y - yMean) } /
                        xs.sumOf { x -> (x - xMean).pow(2) }.coerceAtLeast(1e-9)

            val intercept = yMean - slope * xMean
            val estimatedN = (-slope / 10.0).toFloat().coerceIn(1.5f, 5f)

            // Compute residual std dev (shadowing)
            val residuals = xs.zip(ys).map { (x, y) -> (y - (intercept + slope * x)).pow(2) }
            val sigma = if (residuals.size > 2)
                Math.sqrt(residuals.sum() / (residuals.size - 2)).toFloat()
            else 4f

            return PathLossModel(
                rssiAtOneMetre  = intercept.toFloat(),
                n               = estimatedN,
                shadowingStdDev = sigma
            )
        }

        /** Default models for common environments. */
        val FREE_SPACE     = PathLossModel(rssiAtOneMetre = -40f, n = 2.0f, shadowingStdDev = 2f)
        val OPEN_OFFICE    = PathLossModel(rssiAtOneMetre = -40f, n = 2.4f, shadowingStdDev = 4f)
        val HOME_DRYWALL   = PathLossModel(rssiAtOneMetre = -40f, n = 2.8f, shadowingStdDev = 5f)
        val HOME_CONCRETE  = PathLossModel(rssiAtOneMetre = -40f, n = 3.8f, shadowingStdDev = 6f)
        val INDUSTRIAL     = PathLossModel(rssiAtOneMetre = -40f, n = 3.3f, shadowingStdDev = 7f)
    }
}
