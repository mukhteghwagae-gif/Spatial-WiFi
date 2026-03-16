package com.wifi.visualizer.ui.heatmap

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.ScatterChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import com.wifi.visualizer.data.db.SessionSummary
import com.wifi.visualizer.data.model.SignalQuality
import com.wifi.visualizer.data.model.WifiScanResult
import com.wifi.visualizer.databinding.ActivityHeatmapBinding
import com.wifi.visualizer.ui.analytics.AnalyticsDashboardActivity
import com.wifi.visualizer.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * Enhanced 2-D heatmap with:
 *   • Colour-coded scatter chart (AR XZ plane)
 *   • Session selector spinner
 *   • Stats summary strip (avg / best / worst / count)
 *   • Sparkline of recent RSSI readings
 *   • "Open Analytics" button for deep-dive
 */
class HeatmapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHeatmapBinding
    private val viewModel: MainViewModel by viewModels()

    private var sessions: List<SessionSummary> = emptyList()
    private var selectedSessionId: String? = null
    private var currentScans: List<WifiScanResult> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHeatmapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Signal Heatmap"

        setupChart()
        setupSparkline()
        observeData()

        binding.btnExportSession.setOnClickListener {
            selectedSessionId?.let { viewModel.exportSessionCsv(it) }
                ?: viewModel.exportAllCsv()
        }

        binding.btnOpenAnalytics.setOnClickListener {
            startActivity(Intent(this, AnalyticsDashboardActivity::class.java).apply {
                putExtra(AnalyticsDashboardActivity.EXTRA_SESSION_ID,
                    selectedSessionId ?: viewModel.currentSessionId)
            })
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ── Chart ─────────────────────────────────────────────────────────────────

    private fun setupChart() {
        binding.scatterChart.apply {
            description.isEnabled   = false
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            setGridBackgroundColor(Color.parseColor("#16213E"))
            setDrawGridBackground(true)
            xAxis.textColor         = Color.WHITE
            xAxis.gridColor         = Color.parseColor("#333366")
            xAxis.axisLineColor     = Color.WHITE
            xAxis.valueFormatter    = object : ValueFormatter() {
                override fun getFormattedValue(v: Float) = "${v.toInt()}m"
            }
            axisLeft.textColor      = Color.WHITE
            axisLeft.gridColor      = Color.parseColor("#333366")
            axisLeft.valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(v: Float) = "${v.toInt()}m"
            }
            axisRight.isEnabled     = false
            legend.isEnabled        = true
            legend.textColor        = Color.WHITE
            legend.form             = Legend.LegendForm.CIRCLE
            setTouchEnabled(true)
            isDragEnabled           = true
            isScaleXEnabled         = true
            isScaleYEnabled         = true
            setPinchZoom(true)
        }
    }

    private fun setupSparkline() {
        binding.sparklineChart.apply {
            description.isEnabled   = false
            setBackgroundColor(Color.parseColor("#0f172a"))
            axisLeft.textColor      = Color.WHITE
            axisLeft.textSize       = 9f
            axisRight.isEnabled     = false
            xAxis.isEnabled         = false
            legend.isEnabled        = false
            setTouchEnabled(false)
        }
    }

    // ── Observation ───────────────────────────────────────────────────────────

    private fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                // Session list → populate spinner
                launch {
                    viewModel.sessionIds.collect { list ->
                        sessions = list
                        populateSpinner(list)
                    }
                }

                // All scans (changes when session filter changes)
                launch {
                    viewModel.allScans.collect { scans ->
                        val filtered = if (selectedSessionId != null)
                            scans.filter { it.sessionId == selectedSessionId }
                        else scans
                        currentScans = filtered
                        updateUi(filtered)
                    }
                }
            }
        }
    }

    private fun populateSpinner(sessions: List<SessionSummary>) {
        val labels = mutableListOf("All Sessions") +
            sessions.mapIndexed { i, s -> "Session ${i + 1} (${formatTs(s.earliest)})" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSession.adapter = adapter
        binding.spinnerSession.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p: AdapterView<*>?) {}
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectedSessionId = if (pos == 0) null else sessions.getOrNull(pos - 1)?.sessionId
                val filtered = if (selectedSessionId != null)
                    currentScans.filter { it.sessionId == selectedSessionId }
                else currentScans
                updateUi(filtered)
            }
        }
    }

    private fun updateUi(scans: List<WifiScanResult>) {
        if (scans.isEmpty()) {
            binding.tvNoData.visibility     = View.VISIBLE
            binding.scatterChart.visibility = View.GONE
            binding.statsStrip.visibility   = View.GONE
            return
        }
        binding.tvNoData.visibility     = View.GONE
        binding.scatterChart.visibility = View.VISIBLE
        binding.statsStrip.visibility   = View.VISIBLE
        renderScatter(scans)
        renderSparkline(scans)
        updateStats(scans)
    }

    // ── Scatter chart ─────────────────────────────────────────────────────────

    private fun renderScatter(scans: List<WifiScanResult>) {
        val groups = scans.groupBy { it.signalQuality }
        val dataSets = SignalQuality.values().mapNotNull { quality ->
            val group = groups[quality] ?: return@mapNotNull null
            val entries = group.map { r -> Entry(r.arPosX, r.arPosZ) }
            ScatterDataSet(entries, quality.label).apply {
                color           = Color.parseColor(quality.colorHex)
                setScatterShape(ScatterChart.ScatterShape.CIRCLE)
                scatterShapeSize = when (quality) {
                    SignalQuality.EXCELLENT -> 22f
                    SignalQuality.GOOD      -> 17f
                    SignalQuality.FAIR      -> 13f
                    SignalQuality.POOR      ->  9f
                }
                setDrawValues(false)
            }
        }
        if (dataSets.isEmpty()) return
        binding.scatterChart.data = ScatterData(dataSets)
        binding.scatterChart.animateXY(400, 400)
        binding.scatterChart.invalidate()
    }

    // ── Sparkline ─────────────────────────────────────────────────────────────

    private fun renderSparkline(scans: List<WifiScanResult>) {
        val sorted   = scans.sortedBy { it.timestamp }.takeLast(60)
        val t0       = sorted.firstOrNull()?.timestamp ?: return
        val entries  = sorted.mapIndexed { i, s ->
            Entry(((s.timestamp - t0) / 1000f), s.rssi.toFloat())
        }
        val ds = LineDataSet(entries, "RSSI").apply {
            color          = Color.parseColor("#38BDF8")
            setDrawCircles(false)
            lineWidth      = 2f
            setDrawValues(false)
            mode           = LineDataSet.Mode.CUBIC_BEZIER
            fillAlpha      = 50
            fillColor      = Color.parseColor("#38BDF8")
            setDrawFilled(true)
            // Colour each segment based on RSSI threshold
        }
        binding.sparklineChart.data = LineData(ds)
        binding.sparklineChart.invalidate()
    }

    // ── Stats strip ────────────────────────────────────────────────────────────

    private fun updateStats(scans: List<WifiScanResult>) {
        val rssis  = scans.map { it.rssi }
        val avg    = rssis.average()
        val best   = rssis.max()
        val worst  = rssis.min()
        val pct75  = rssis.filter { it > -70 }.size * 100 / rssis.size

        binding.tvScanTotal.text = "${scans.size} pts"
        binding.tvAvgRssi.text   = "${avg.toInt()} dBm avg"
        binding.tvBestRssi.text  = "Best: $best"
        binding.tvWorstRssi.text = "Worst: $worst"
        binding.tvCoverageHint.text = "$pct75% above -70 dBm"

        // Colour the avg
        val avgColor = Color.parseColor(SignalQuality.from(avg.toInt()).colorHex)
        binding.tvAvgRssi.setTextColor(avgColor)
    }

    private fun formatTs(epochMs: Long): String =
        java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(epochMs))
}
