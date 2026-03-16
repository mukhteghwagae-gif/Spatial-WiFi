package com.wifi.visualizer.ui.channels

import android.graphics.Color
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import com.wifi.visualizer.databinding.ActivityChannelAnalyzerBinding
import com.wifi.visualizer.intelligence.ChannelAnalyzer
import com.wifi.visualizer.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * Full-screen channel utilisation analyzer.
 *
 * Shows two bar charts (2.4 GHz / 5 GHz) where bar height represents
 * congestion score per channel.  Recommends the least-congested channels.
 */
class ChannelAnalyzerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChannelAnalyzerBinding
    private val viewModel: MainViewModel by viewModels()
    private val analyzer = ChannelAnalyzer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelAnalyzerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupCharts()
        observe()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun setupCharts() {
        listOf(binding.chart24, binding.chart5).forEach { chart ->
            chart.description.isEnabled  = false
            chart.setBackgroundColor(Color.parseColor("#1A1A2E"))
            chart.xAxis.textColor        = Color.WHITE
            chart.axisLeft.textColor     = Color.WHITE
            chart.axisRight.isEnabled    = false
            chart.legend.isEnabled       = false
            chart.xAxis.granularity      = 1f
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.wifiSnapshot.collect { snap ->
                    snap ?: return@collect
                    val report = analyzer.analyze(snap.nearbyNetworks)
                    render24Chart(report.channelStats24GHz)
                    render5Chart(report.channelStats5GHz)
                    binding.tvRecommended24.text =
                        "Best 2.4 GHz: channels ${report.recommendedChannels24.take(3).joinToString(", ")}"
                    binding.tvRecommended5.text =
                        "Best 5 GHz: channels ${report.recommendedChannels5.take(3).joinToString(", ")}"
                    binding.tvCongestion.text = report.congestionLabel
                    binding.tvTotalNets.text  = "${report.totalNetworksVisible} networks visible"
                }
            }
        }
    }

    private fun render24Chart(stats: Map<Int, com.wifi.visualizer.intelligence.ChannelStat>) {
        val entries = (1..13).map { ch ->
            BarEntry(ch.toFloat(), stats[ch]?.congestionScore?.toFloat() ?: 0f)
        }
        val colors = (1..13).map { ch ->
            val score = stats[ch]?.congestionScore ?: 0
            when {
                score < 20 -> Color.parseColor("#4CAF50")
                score < 50 -> Color.parseColor("#FFC107")
                score < 75 -> Color.parseColor("#FF9800")
                else       -> Color.parseColor("#F44336")
            }
        }
        val ds = BarDataSet(entries, "2.4 GHz Channel Utilisation").apply {
            this.colors = colors; setDrawValues(false)
        }
        binding.chart24.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(v: Float) = "Ch ${v.toInt()}"
        }
        binding.chart24.data = BarData(ds); binding.chart24.invalidate()
    }

    private fun render5Chart(stats: Map<Int, com.wifi.visualizer.intelligence.ChannelStat>) {
        val channels5 = listOf(36,40,44,48,52,56,60,64,100,104,108,112,149,153,157,161)
        val entries = channels5.mapIndexed { i, ch ->
            BarEntry(i.toFloat(), stats[ch]?.congestionScore?.toFloat() ?: 0f)
        }
        val ds = BarDataSet(entries, "5 GHz Channel Utilisation").apply {
            color = Color.parseColor("#38BDF8"); setDrawValues(false)
        }
        binding.chart5.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(v: Float) =
                channels5.getOrElse(v.toInt()) { 0 }.toString()
        }
        binding.chart5.data = BarData(ds); binding.chart5.invalidate()
    }
}
