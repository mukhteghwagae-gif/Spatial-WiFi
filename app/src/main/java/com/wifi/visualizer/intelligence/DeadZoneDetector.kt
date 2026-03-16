package com.wifi.visualizer.intelligence

import com.wifi.visualizer.data.model.WifiScanResult
import kotlin.math.sqrt

/**
 * Detects Wi-Fi dead zones and recommends optimal access-point / mesh-node
 * placement to eliminate them.
 *
 * Algorithm:
 *   1. Build an IDW grid from measured scan results.
 *   2. Identify "dead cells" where predicted RSSI < [thresholdDbm].
 *   3. Cluster adjacent dead cells using flood-fill (4-connected).
 *   4. For each cluster, compute its centroid — that is the recommended
 *      placement location for an additional AP or mesh node.
 *   5. Score each placement by how many dead cells it would likely fix
 *      using the provided [PathLossModel].
 */
class DeadZoneDetector(
    private val interpolator  : IdwInterpolator  = IdwInterpolator(),
    private val pathLossModel : PathLossModel     = PathLossModel.HOME_DRYWALL
) {

    /**
     * Analyse a set of scan results and return a [DeadZoneReport].
     *
     * @param samples       collected scan measurements
     * @param thresholdDbm  cells below this value are considered "dead"
     * @param cellSize      spatial resolution of the analysis grid (metres)
     */
    fun analyze(
        samples       : List<WifiScanResult>,
        thresholdDbm  : Int   = -75,
        cellSize      : Float = 0.5f
    ): DeadZoneReport {
        if (samples.size < 5) {
            return DeadZoneReport(emptyList(), emptyList(), 0f, 0f, samples.size)
        }

        val grid = interpolator.buildGrid(samples, cellSize, padding = 3)
            ?: return DeadZoneReport(emptyList(), emptyList(), 0f, 0f, samples.size)

        val deadCells   = findDeadCells(grid, thresholdDbm)
        val clusters    = clusterDeadCells(deadCells, grid.cols)
        val placements  = clusters.map { cluster ->
            buildPlacementSuggestion(cluster, grid, samples, thresholdDbm)
        }.sortedByDescending { it.impactScore }

        val coveragePct      = grid.coverageFraction(thresholdDbm) * 100f
        val deadZoneAreaM2   = deadCells.size * cellSize * cellSize

        return DeadZoneReport(
            deadZones        = clusters.map { buildDeadZone(it, grid, thresholdDbm) },
            meshRecommendations = placements,
            coveragePercent  = coveragePct,
            deadZoneAreaM2   = deadZoneAreaM2,
            sampleCount      = samples.size
        )
    }

    // ── Dead cell detection ───────────────────────────────────────────────────

    private fun findDeadCells(grid: InterpolationGrid, threshold: Int): List<CellIndex> {
        val dead = mutableListOf<CellIndex>()
        for (row in 0 until grid.rows) {
            for (col in 0 until grid.cols) {
                if (grid.values[row][col] < threshold) {
                    dead.add(CellIndex(row, col))
                }
            }
        }
        return dead
    }

    // ── Flood-fill clustering ─────────────────────────────────────────────────

    private fun clusterDeadCells(
        deadCells: List<CellIndex>,
        cols: Int
    ): List<List<CellIndex>> {
        val deadSet    = deadCells.toHashSet()
        val visited    = HashSet<CellIndex>()
        val clusters   = mutableListOf<List<CellIndex>>()

        for (cell in deadCells) {
            if (cell in visited) continue
            val cluster = mutableListOf<CellIndex>()
            val queue   = ArrayDeque<CellIndex>()
            queue.add(cell)
            visited.add(cell)

            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                cluster.add(cur)
                for (n in cur.neighbours()) {
                    if (n in deadSet && n !in visited) {
                        visited.add(n)
                        queue.add(n)
                    }
                }
            }
            if (cluster.size >= 2) clusters.add(cluster)  // skip single-cell noise
        }
        return clusters
    }

    // ── Placement suggestion ──────────────────────────────────────────────────

    private fun buildPlacementSuggestion(
        cluster     : List<CellIndex>,
        grid        : InterpolationGrid,
        samples     : List<WifiScanResult>,
        threshold   : Int
    ): MeshNodeRecommendation {
        val (centroidRow, centroidCol) = cluster.let {
            it.map { c -> c.row }.average() to it.map { c -> c.col }.average()
        }
        val worldX = grid.originX + centroidCol.toFloat() * grid.cellSize
        val worldZ = grid.originZ + centroidRow.toFloat() * grid.cellSize

        // Estimate how many dead cells would be fixed by a mesh node at the centroid
        val coverageRadius = pathLossModel.coverageRadius(threshold)
        val fixedCount = cluster.count { cell ->
            val wx = grid.originX + cell.col * grid.cellSize
            val wz = grid.originZ + cell.row * grid.cellSize
            val dx = wx - worldX; val dz = wz - worldZ
            sqrt(dx * dx + dz * dz) <= coverageRadius
        }

        val impactScore = fixedCount.toFloat() / cluster.size.toFloat()

        return MeshNodeRecommendation(
            worldX        = worldX,
            worldZ        = worldZ,
            worldY        = 1.5f,           // typical AP height 1.5 m
            deadCellsFixed = fixedCount,
            totalDeadCells = cluster.size,
            impactScore    = impactScore,
            worstRssiInZone = cluster.minOf { cell ->
                grid.values[cell.row][cell.col].toInt()
            }
        )
    }

    private fun buildDeadZone(
        cluster     : List<CellIndex>,
        grid        : InterpolationGrid,
        threshold   : Int
    ): DeadZone {
        val xs = cluster.map { grid.originX + it.col * grid.cellSize }
        val zs = cluster.map { grid.originZ + it.row * grid.cellSize }
        return DeadZone(
            centerX    = xs.average().toFloat(),
            centerZ    = zs.average().toFloat(),
            areaM2     = cluster.size * grid.cellSize * grid.cellSize,
            worstRssi  = cluster.minOf { grid.values[it.row][it.col].toInt() },
            cellCount  = cluster.size
        )
    }

    data class CellIndex(val row: Int, val col: Int) {
        fun neighbours() = listOf(
            CellIndex(row - 1, col), CellIndex(row + 1, col),
            CellIndex(row, col - 1), CellIndex(row, col + 1)
        )
    }
}

// ── Report data classes ───────────────────────────────────────────────────────

data class DeadZone(
    val centerX   : Float,
    val centerZ   : Float,
    val areaM2    : Float,
    val worstRssi : Int,
    val cellCount : Int
) {
    val severity: String get() = when {
        worstRssi > -80 -> "Weak"
        worstRssi > -90 -> "Dead"
        else            -> "No Signal"
    }
}

data class MeshNodeRecommendation(
    val worldX          : Float,
    val worldZ          : Float,
    val worldY          : Float,
    val deadCellsFixed  : Int,
    val totalDeadCells  : Int,
    val impactScore     : Float,     // 0-1; higher = more beneficial
    val worstRssiInZone : Int
) {
    val description: String get() =
        "Place AP/repeater here to fix ${(impactScore * 100).toInt()}% of dead zone " +
        "(~${String.format("%.1f", worldX)}m, ~${String.format("%.1f", worldZ)}m)"
}

data class DeadZoneReport(
    val deadZones           : List<DeadZone>,
    val meshRecommendations : List<MeshNodeRecommendation>,
    val coveragePercent     : Float,
    val deadZoneAreaM2      : Float,
    val sampleCount         : Int
) {
    val hasDeadZones: Boolean get() = deadZones.isNotEmpty()
    val coverageGrade: String get() = when {
        coveragePercent >= 90 -> "A — Excellent"
        coveragePercent >= 75 -> "B — Good"
        coveragePercent >= 60 -> "C — Fair"
        coveragePercent >= 40 -> "D — Poor"
        else                  -> "F — Critical"
    }
}
