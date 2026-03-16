package com.wifi.visualizer.ui.networkList

import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wifi.visualizer.databinding.ActivityNetworkListBinding
import com.wifi.visualizer.databinding.ItemNetworkBinding
import com.wifi.visualizer.intelligence.ChannelAnalyzer
import com.wifi.visualizer.utils.NearbyNetwork
import com.wifi.visualizer.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * Scans for all nearby Wi-Fi networks and shows them in a sorted list with:
 *   • SSID and BSSID
 *   • RSSI bar and dBm value
 *   • Frequency band and channel number
 *   • Congestion score for that channel
 *   • Tap to set as the tracked network
 */
class NetworkListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNetworkListBinding
    private val viewModel: MainViewModel by viewModels()
    private val adapter  = NetworkAdapter { ssid -> viewModel.selectSsid(ssid) }
    private val channelAnalyzer = ChannelAnalyzer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNetworkListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Nearby Networks"

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.triggerScan()
            binding.swipeRefresh.isRefreshing = false
        }

        observe()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.wifiSnapshot.collect { snap ->
                    snap ?: return@collect
                    val channelReport = channelAnalyzer.analyze(snap.nearbyNetworks)
                    val items = snap.nearbyNetworks
                        .sortedByDescending { it.rssi }
                        .map { net ->
                            val ch = if (net.frequency < 3000) channelAnalyzer.freqToChannel24(net.frequency)
                                     else channelAnalyzer.freqToChannel5(net.frequency)
                            val congestion = (if (net.frequency < 3000) channelReport.channelStats24GHz
                                             else channelReport.channelStats5GHz)[ch]?.congestionScore ?: 0
                            NetworkItem(net, ch, congestion)
                        }
                    adapter.submitList(items)
                    binding.tvCount.text = "${items.size} networks found"
                }
            }
        }
    }
}

data class NetworkItem(val network: NearbyNetwork, val channel: Int, val channelCongestion: Int)

class NetworkAdapter(private val onSelect: (String) -> Unit) :
    ListAdapter<NetworkItem, NetworkAdapter.VH>(DIFF) {

    inner class VH(val b: ItemNetworkBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(
        ItemNetworkBinding.inflate(LayoutInflater.from(p.context), p, false)
    )

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = getItem(pos)
        val net  = item.network
        h.b.tvSsid.text    = net.ssid.ifBlank { "<Hidden>" }
        h.b.tvBssid.text   = net.bssid
        h.b.tvRssi.text    = "${net.rssi} dBm"
        h.b.tvBand.text    = if (net.frequency < 3000) "2.4 GHz" else "5 GHz"
        h.b.tvChannel.text = "Ch ${item.channel}"

        val pct = ((net.rssi + 100).coerceIn(0, 70) * 100 / 70)
        h.b.rssiBar.progress = pct

        val signalColor = when {
            net.rssi > -50 -> Color.parseColor("#4CAF50")
            net.rssi > -70 -> Color.parseColor("#FFC107")
            net.rssi > -80 -> Color.parseColor("#FF9800")
            else           -> Color.parseColor("#F44336")
        }
        h.b.tvRssi.setTextColor(signalColor)

        val congLabel = when {
            item.channelCongestion < 20 -> "Clear"
            item.channelCongestion < 50 -> "Moderate"
            item.channelCongestion < 75 -> "Busy"
            else                        -> "Congested"
        }
        h.b.tvCongestion.text = "Channel: $congLabel"

        h.itemView.setOnClickListener { onSelect(net.ssid) }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<NetworkItem>() {
            override fun areItemsTheSame(a: NetworkItem, b: NetworkItem) = a.network.bssid == b.network.bssid
            override fun areContentsTheSame(a: NetworkItem, b: NetworkItem) = a == b
        }
    }
}
