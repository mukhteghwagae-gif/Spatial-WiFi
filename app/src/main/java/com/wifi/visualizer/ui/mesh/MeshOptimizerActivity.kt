package com.wifi.visualizer.ui.mesh

import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wifi.visualizer.data.model.WifiScanResult
import com.wifi.visualizer.databinding.ActivityMeshOptimizerBinding
import com.wifi.visualizer.intelligence.*
import com.wifi.visualizer.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Mesh Optimizer — shows a canvas-rendered top-down view of:
 *   • All scan points (colour-coded by RSSI)
 *   • Dead zone areas (semi-transparent red)
 *   • Recommended AP/mesh-node positions (cyan stars)
 *
 * Tap a recommendation star to see details.
 */
class MeshOptimizerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMeshOptimizerBinding
    private val viewModel: MainViewModel by viewModels()
    private val intelligence   = SignalIntelligenceEngine()
    private val deadZoneDetector = DeadZoneDetector()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMeshOptimizerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        observeData()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.allScans.collect { scans ->
                    if (scans.isEmpty()) {
                        binding.tvNoData.visibility    = View.VISIBLE
                        binding.mapView.visibility     = View.GONE
                        return@collect
                    }
                    binding.tvNoData.visibility = View.GONE
                    binding.mapView.visibility  = View.VISIBLE

                    // Run analysis
                    val report = deadZoneDetector.analyze(scans)
                    binding.mapView.setData(scans, report)
                    renderSummary(report)
                }
            }
        }
    }

    private fun renderSummary(report: DeadZoneReport) {
        binding.tvGrade.text       = "Grade: ${report.coverageGrade}"
        binding.tvCoverage.text    = "Coverage: ${report.coveragePercent.toInt()}%"
        binding.tvDeadArea.text    = "Dead zone area: ${report.deadZoneAreaM2.toInt()} m²"
        binding.tvRecommendations.text = if (report.meshRecommendations.isEmpty()) {
            "✅ No additional APs needed — coverage is good."
        } else {
            report.meshRecommendations.take(5).joinToString("\n") { r ->
                "📡 Place AP at (${String.format("%.1f",r.worldX)}m, ${String.format("%.1f",r.worldZ)}m) → fixes ${(r.impactScore*100).toInt()}% of dead zone"
            }
        }
    }
}

/**
 * Custom canvas view that renders scan points, dead zones, and AP recommendations.
 */
class MeshMapView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private var scans: List<WifiScanResult> = emptyList()
    private var report: DeadZoneReport? = null

    private val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val deadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 244, 67, 54)
        style = Paint.Style.FILL
    }
    private val apPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        style = Paint.Style.FILL
        strokeWidth = 3f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
    }
    private val gridPaint = Paint().apply {
        color = Color.argb(60, 255, 255, 255)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    fun setData(scans: List<WifiScanResult>, report: DeadZoneReport) {
        this.scans  = scans
        this.report = report
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (scans.isEmpty()) return

        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawColor(Color.parseColor("#0f172a"))

        val xs = scans.map { it.arPosX }
        val zs = scans.map { it.arPosZ }
        val minX = xs.min(); val maxX = max(xs.max(), minX + 0.1f)
        val minZ = zs.min(); val maxZ = max(zs.max(), minZ + 0.1f)
        val pad  = 50f

        fun worldToScreen(wx: Float, wz: Float): Pair<Float, Float> {
            val sx = pad + (wx - minX) / (maxX - minX) * (w - 2 * pad)
            val sy = pad + (wz - minZ) / (maxZ - minZ) * (h - 2 * pad)
            return sx to sy
        }

        // Grid
        for (i in 0..4) {
            val x = pad + i * (w - 2*pad) / 4
            val y = pad + i * (h - 2*pad) / 4
            canvas.drawLine(x, pad, x, h - pad, gridPaint)
            canvas.drawLine(pad, y, w - pad, y, gridPaint)
        }

        // Dead zones
        report?.deadZones?.forEach { dz ->
            val (sx, sy) = worldToScreen(dz.centerX, dz.centerZ)
            val r = (dz.areaM2 * 10f).coerceIn(20f, 80f)
            canvas.drawCircle(sx, sy, r, deadPaint)
        }

        // Scan points
        scans.forEach { scan ->
            val (sx, sy) = worldToScreen(scan.arPosX, scan.arPosZ)
            scanPaint.color = Color.parseColor(scan.signalQuality.colorHex)
            scanPaint.alpha = 200
            val r = ((scan.rssi + 100).coerceIn(5, 30)).toFloat() / 3f
            canvas.drawCircle(sx, sy, r + 4f, scanPaint)
        }

        // AP recommendations
        report?.meshRecommendations?.forEach { rec ->
            val (sx, sy) = worldToScreen(rec.worldX, rec.worldZ)
            drawStar(canvas, sx, sy, 18f, apPaint)
            canvas.drawText("AP", sx - 10f, sy - 22f, textPaint)
        }

        // Legend
        val legendY = h - 30f
        listOf(
            Color.parseColor("#4CAF50") to "Excellent",
            Color.parseColor("#FFC107") to "Good",
            Color.parseColor("#FF9800") to "Fair",
            Color.parseColor("#F44336") to "Poor"
        ).forEachIndexed { i, (color, label) ->
            scanPaint.color = color
            val lx = pad + i * (w - 2*pad) / 4
            canvas.drawCircle(lx, legendY, 8f, scanPaint)
            textPaint.textSize = 22f
            canvas.drawText(label, lx + 12f, legendY + 7f, textPaint)
        }
    }

    private fun drawStar(canvas: Canvas, cx: Float, cy: Float, r: Float, paint: Paint) {
        val path = Path()
        val inner = r * 0.45f
        for (i in 0 until 10) {
            val angle = (i * 36 - 90) * Math.PI / 180.0
            val radius = if (i % 2 == 0) r else inner
            val x = cx + (radius * Math.cos(angle)).toFloat()
            val y = cy + (radius * Math.sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, paint)
    }
}
