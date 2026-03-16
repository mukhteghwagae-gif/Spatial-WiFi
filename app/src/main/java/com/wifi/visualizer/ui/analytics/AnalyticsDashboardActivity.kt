package com.wifi.visualizer.ui.analytics

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import com.wifi.visualizer.databinding.ActivityAnalyticsDashboardBinding
import com.wifi.visualizer.intelligence.*
import com.wifi.visualizer.viewmodel.AnalyticsViewModel
import kotlinx.coroutines.launch

/**
 * Analytics Dashboard — shows:
 *   • Coverage score gauge
 *   • RSSI over time line chart
 *   • Signal distribution bar chart (bins of 5 dBm)
 *   • Dead zones count and area
 *   • AI insight cards
 *   • Export buttons (CSV / JSON / KML / HTML Report)
 */
class AnalyticsDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAnalyticsDashboardBinding
    private val viewModel: AnalyticsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAnalyticsDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
            ?: return finish()

        setupCharts()
        setupExportButtons(sessionId)
        observeAnalysis(sessionId)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ── Charts ────────────────────────────────────────────────────────────────

    private fun setupCharts() {
        // RSSI over time
        binding.chartRssiOverTime.apply {
            description.isEnabled   = false
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            xAxis.textColor         = Color.WHITE
            axisLeft.textColor      = Color.WHITE
            axisRight.isEnabled     = false
            legend.textColor        = Color.WHITE
            xAxis.position          = XAxis.XAxisPosition.BOTTOM
            xAxis.valueFormatter    = object : ValueFormatter() {
                override fun getFormattedValue(v: Float) = "${v.toInt()}s"
            }
            axisLeft.valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(v: Float) = "${v.toInt()} dBm"
            }
        }

        // Signal distribution
        binding.chartDistribution.apply {
            description.isEnabled = false
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            xAxis.textColor       = Color.WHITE
            axisLeft.textColor    = Color.WHITE
            axisRight.isEnabled   = false
            legend.isEnabled      = false
            xAxis.position        = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity     = 1f
            xAxis.valueFormatter  = object : ValueFormatter() {
                private val labels = listOf("-100","-90","-80","-70","-60","-50","-40")
                override fun getFormattedValue(v: Float) = labels.getOrElse(v.toInt()) { "" }
            }
        }
    }

    // ── Data observation ──────────────────────────────────────────────────────

    private fun observeAnalysis(sessionId: String) {
        viewModel.loadSession(sessionId)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.analysis.collect { analysis ->
                        analysis ?: return@collect
                        renderAnalysis(analysis)
                    }
                }
                launch {
                    viewModel.isLoading.collect { loading ->
                        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.rssiTimeSeries.collect { series ->
                        if (series.isNotEmpty()) renderTimeChart(series)
                    }
                }
            }
        }
    }

    private fun renderAnalysis(analysis: SessionAnalysis) {
        val stats = analysis.signalStats
        val dead  = analysis.deadZoneReport

        // Coverage gauge
        binding.coverageGauge.progress = analysis.coverageScore
        binding.tvCoverageScore.text   = "${analysis.coverageScore}%"
        binding.tvCoverageGrade.text   = dead.coverageGrade

        // Stats cards
        binding.tvAvgRssi.text     = "${stats.mean.toInt()} dBm"
        binding.tvBestRssi.text    = "${stats.max} dBm"
        binding.tvWorstRssi.text   = "${stats.min} dBm"
        binding.tvStability.text   = "${analysis.stabilityScore}%"
        binding.tvPathLossN.text   = String.format("%.2f", analysis.pathLossModel.n)
        binding.tvDeadZones.text   = "${dead.deadZones.size} zones (${dead.deadZoneAreaM2.toInt()} m²)"
        binding.tvTrend.text       = analysis.signalTrend.label
        binding.tvSamples.text     = "${analysis.sampleCount} samples"

        // Signal distribution chart
        renderDistributionChart(analysis)

        // Direction compass
        renderDirectionTable(analysis.directionStrengths)

        // Insights
        renderInsights(analysis.insights)

        // Path loss model info
        binding.tvModelInfo.text = buildString {
            append("Environment: ${guessEnvironment(analysis.pathLossModel.n)}\n")
            append("Path-loss exponent n = ${String.format("%.2f", analysis.pathLossModel.n)}\n")
            append("RSSI @ 1m = ${analysis.pathLossModel.rssiAtOneMetre.toInt()} dBm\n")
            append("Coverage radius (-70dBm) ≈ ${analysis.pathLossModel.coverageRadius(-70).toInt()} m")
        }

        // Anomalies
        if (analysis.anomalies.isNotEmpty()) {
            binding.tvAnomalies.text = "⚠ ${analysis.anomalies.size} signal anomalies detected"
            binding.tvAnomalies.visibility = View.VISIBLE
        } else {
            binding.tvAnomalies.visibility = View.GONE
        }
    }

    private fun renderTimeChart(series: List<Pair<Long, Float>>) {
        if (series.isEmpty()) return
        val t0 = series.first().first
        val entries = series.mapIndexed { i, (t, rssi) ->
            Entry(((t - t0) / 1000f), rssi)
        }
        val ds = LineDataSet(entries, "RSSI over time").apply {
            color = Color.parseColor("#38BDF8")
            setDrawCircles(false)
            lineWidth = 2f
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
            fillAlpha = 60
            fillColor = Color.parseColor("#38BDF8")
            setDrawFilled(true)
        }
        binding.chartRssiOverTime.data = LineData(ds)
        binding.chartRssiOverTime.animateX(800)
        binding.chartRssiOverTime.invalidate()
    }

    /**
     * Render a bar chart showing how many scan samples fall in each 10-dBm bucket.
     * Buckets: [-100,-90), [-90,-80), [-80,-70), [-70,-60), [-60,-50), [-50,-40), [-40,-30]
     * We rebuild this from the PathLossModel + known stats mean/stddev using a Gaussian
     * approximation when we don't have raw scan data in this ViewModel.  When the
     * time-series data is available we derive it from that instead.
     */
    private fun renderDistributionChart(analysis: SessionAnalysis) {
        val stats  = analysis.signalStats
        val mean   = stats.mean.toDouble()
        val stdDev = stats.stdDev.toDouble().coerceAtLeast(1.0)
        val total  = analysis.sampleCount.coerceAtLeast(1)

        // Approximate count in each 10-dBm bucket using normal distribution
        val bucketLowers = intArrayOf(-100, -90, -80, -70, -60, -50, -40)
        val counts = FloatArray(7) { i ->
            val lo = bucketLowers[i].toDouble()
            val hi = lo + 10.0
            // Fraction between lo and hi under N(mean, stdDev)
            val zLo = (lo - mean) / stdDev
            val zHi = (hi - mean) / stdDev
            val frac = normalCdf(zHi) - normalCdf(zLo)
            (frac * total).toFloat().coerceAtLeast(0f)
        }

        val entries = counts.mapIndexed { i, count -> BarEntry(i.toFloat(), count) }
        val colors  = listOf(
            Color.parseColor("#F44336"), Color.parseColor("#FF5722"),
            Color.parseColor("#FF9800"), Color.parseColor("#FFC107"),
            Color.parseColor("#8BC34A"), Color.parseColor("#4CAF50"),
            Color.parseColor("#00E676")
        )
        val ds = BarDataSet(entries, "RSSI Distribution").apply {
            this.colors = colors
            setDrawValues(false)
        }
        binding.chartDistribution.data = BarData(ds)
        binding.chartDistribution.animateY(600)
        binding.chartDistribution.invalidate()
    }

    /** Cumulative distribution function for standard normal using Horner's method approximation. */
    private fun normalCdf(z: Double): Double {
        if (z < -6.0) return 0.0
        if (z >  6.0) return 1.0
        var k = 1.0 / (1.0 + 0.2316419 * Math.abs(z))
        var poly = k * (0.319381530 + k * (-0.356563782 + k * (1.781477937 + k * (-1.821255978 + k * 1.330274429))))
        val pdf  = Math.exp(-0.5 * z * z) / Math.sqrt(2.0 * Math.PI)
        val cdf  = 1.0 - pdf * poly
        return if (z >= 0) cdf else 1.0 - cdf
    }

    private fun renderDirectionTable(dirs: Map<String, Float>) {
        if (dirs.isEmpty()) return
        val sb = StringBuilder()
        val orderedDirs = listOf("N","NE","E","SE","S","SW","W","NW")
        for (d in orderedDirs) {
            val rssi = dirs[d] ?: continue
            val bar  = "▓".repeat(((rssi + 100).coerceIn(0f, 70f).toInt() * 10 / 70))
            sb.appendLine("$d\t${rssi.toInt()} dBm  $bar")
        }
        binding.tvDirectionTable.text = sb.toString()
    }

    private fun renderInsights(insights: List<Insight>) {
        val sb = StringBuilder()
        for (insight in insights) {
            val icon = when (insight.severity) {
                InsightSeverity.ACTION_REQUIRED -> "🔴"
                InsightSeverity.WARNING         -> "🟡"
                InsightSeverity.INFO            -> "🟢"
            }
            sb.appendLine("$icon ${insight.title}")
            sb.appendLine("   ${insight.detail}")
            sb.appendLine()
        }
        binding.tvInsights.text = sb.toString()
    }

    private fun guessEnvironment(n: Float): String = when {
        n < 2.2f -> "Free space / outdoor"
        n < 2.7f -> "Open office"
        n < 3.2f -> "Home (drywall)"
        n < 4.0f -> "Home (concrete/brick)"
        else     -> "Industrial / dense"
    }

    // ── Export buttons ────────────────────────────────────────────────────────

    private fun setupExportButtons(sessionId: String) {
        binding.btnExportCsv.setOnClickListener  { exportFormat(sessionId, "csv") }
        binding.btnExportJson.setOnClickListener { exportFormat(sessionId, "json") }
        binding.btnExportKml.setOnClickListener  { exportFormat(sessionId, "kml") }
        binding.btnExportHtml.setOnClickListener { exportFormat(sessionId, "html") }
    }

    private fun exportFormat(sessionId: String, format: String) {
        lifecycleScope.launch {
            runCatching { viewModel.export(sessionId, format) }
                .onSuccess { file ->
                    val uri = FileProvider.getUriForFile(
                        this@AnalyticsDashboardActivity,
                        "${packageName}.fileprovider", file)
                    val mime = when (format) {
                        "csv" -> "text/csv"; "json" -> "application/json"
                        "kml" -> "application/vnd.google-earth.kml+xml"
                        else  -> "text/html"
                    }
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = mime; putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share $format"))
                }
                .onFailure { Toast.makeText(this@AnalyticsDashboardActivity, "Export failed: ${it.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"
    }
}
