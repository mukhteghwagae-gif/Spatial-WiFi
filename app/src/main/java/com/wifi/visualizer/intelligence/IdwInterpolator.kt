package com.wifi.visualizer.intelligence

import com.wifi.visualizer.data.model.WifiScanResult
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Inverse Distance Weighting (IDW) interpolator.
 *
 * Given a sparse set of Wi-Fi measurement points, predicts the signal
 * strength at any arbitrary (x, z) location in AR world-space.
 *
 * Formula:  predicted = Σ(wᵢ · rssiᵢ) / Σwᵢ
 * where     wᵢ = 1 / distanceᵢ^power
 *
 * The power parameter controls how rapidly influence drops with distance:
 *   power = 1 → linear falloff
 *   power = 2 → inverse-square (default, physically motivated)
 *   power ≥ 3 → Nearest-neighbour-like
 */
class IdwInterpolator(private val power: Double = 2.0) {

    /**
     * Predict RSSI at position (queryX, queryZ) from a list of samples.
     *
     * @return predicted RSSI in dBm, or null if [samples] is empty.
     */
    fun predict(
        queryX: Float,
        queryZ: Float,
        samples: List<WifiScanResult>,
        maxRadius: Float = 8f   // ignore points farther than this (metres)
    ): Float? {
        if (samples.isEmpty()) return null

        var weightedSum = 0.0
        var totalWeight  = 0.0
        var exactHit: Float? = null

        for (s in samples) {
            val dx   = (queryX - s.arPosX).toDouble()
            val dz   = (queryZ - s.arPosZ).toDouble()
            val dist = sqrt(dx * dx + dz * dz)

            if (dist > maxRadius) continue
            if (dist < 0.001) { exactHit = s.rssi.toFloat(); break }

            val w = 1.0 / dist.pow(power)
            weightedSum += w * s.rssi
            totalWeight  += w
        }

        if (exactHit != null) return exactHit
        if (totalWeight == 0.0) return null

        return (weightedSum / totalWeight).toFloat()
    }

    /**
     * Build a raster grid of predicted RSSI values suitable for rendering
     * as a coloured heat-mesh or bitmap.
     *
     * @param samples     measured scan results
     * @param cellSize    world-space metres per grid cell (default 0.5 m)
     * @param padding     extra cells around the bounding box of samples
     *
     * @return [InterpolationGrid] containing the predicted values and
     *         world-space origin so the caller can position the mesh.
     */
    fun buildGrid(
        samples: List<WifiScanResult>,
        cellSize: Float = 0.5f,
        padding: Int    = 2
    ): InterpolationGrid? {
        if (samples.isEmpty()) return null

        val xs = samples.map { it.arPosX }
        val zs = samples.map { it.arPosZ }
        val minX = xs.min() - padding * cellSize
        val maxX = xs.max() + padding * cellSize
        val minZ = zs.min() - padding * cellSize
        val maxZ = zs.max() + padding * cellSize

        val cols = ((maxX - minX) / cellSize).toInt().coerceAtLeast(1)
        val rows = ((maxZ - minZ) / cellSize).toInt().coerceAtLeast(1)

        val grid = Array(rows) { row ->
            FloatArray(cols) { col ->
                val wx = minX + col * cellSize + cellSize / 2f
                val wz = minZ + row * cellSize + cellSize / 2f
                predict(wx, wz, samples) ?: -90f
            }
        }

        return InterpolationGrid(
            values   = grid,
            originX  = minX,
            originZ  = minZ,
            cellSize = cellSize,
            cols     = cols,
            rows     = rows
        )
    }
}

/**
 * A 2-D raster of predicted RSSI values.
 *
 * NOTE: Not a data class — Array<FloatArray> does not support structural
 * equals/hashCode, so we implement them manually.
 *
 * @param values   [rows][cols] grid of RSSI dBm values
 * @param originX  world-space X of the top-left corner
 * @param originZ  world-space Z of the top-left corner
 * @param cellSize world-space metres per cell
 */
class InterpolationGrid(
    val values   : Array<FloatArray>,
    val originX  : Float,
    val originZ  : Float,
    val cellSize : Float,
    val cols     : Int,
    val rows     : Int
) {
    /** Min RSSI across the entire grid (for normalisation). */
    val minRssi: Float by lazy {
        var m = Float.MAX_VALUE
        for (row in values) for (v in row) if (v < m) m = v
        if (m == Float.MAX_VALUE) -100f else m
    }

    /** Max RSSI across the entire grid (for normalisation). */
    val maxRssi: Float by lazy {
        var m = -Float.MAX_VALUE
        for (row in values) for (v in row) if (v > m) m = v
        if (m == -Float.MAX_VALUE) -30f else m
    }

    /** Fraction [0,1] of cells with RSSI > [threshold] dBm. */
    fun coverageFraction(threshold: Int = -70): Float {
        if (rows == 0 || cols == 0) return 0f
        val total   = rows * cols
        val covered = values.sumOf { row -> row.count { it > threshold } }
        return covered.toFloat() / total
    }

    /** Copy of this grid with a deep-copied values array. */
    fun copy(): InterpolationGrid = InterpolationGrid(
        values   = Array(rows) { r -> values[r].copyOf() },
        originX  = originX,
        originZ  = originZ,
        cellSize = cellSize,
        cols     = cols,
        rows     = rows
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InterpolationGrid) return false
        return originX == other.originX && originZ == other.originZ &&
               cellSize == other.cellSize && cols == other.cols && rows == other.rows &&
               values.contentDeepEquals(other.values)
    }

    override fun hashCode(): Int {
        var result = values.contentDeepHashCode()
        result = 31 * result + originX.hashCode()
        result = 31 * result + originZ.hashCode()
        result = 31 * result + cellSize.hashCode()
        result = 31 * result + cols
        result = 31 * result + rows
        return result
    }
}
