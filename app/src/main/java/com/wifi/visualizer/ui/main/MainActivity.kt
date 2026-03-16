package com.wifi.visualizer.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.ar.core.ArCoreApk
import com.wifi.visualizer.R
import com.wifi.visualizer.ar.ArAvailability
import com.wifi.visualizer.ar.ArSessionManager
import com.wifi.visualizer.databinding.ActivityMainBinding
import com.wifi.visualizer.ui.ar.ArMappingActivity
import com.wifi.visualizer.ui.analytics.AnalyticsDashboardActivity
import com.wifi.visualizer.ui.channels.ChannelAnalyzerActivity
import com.wifi.visualizer.ui.heatmap.HeatmapActivity
import com.wifi.visualizer.ui.mesh.MeshOptimizerActivity
import com.wifi.visualizer.ui.networkList.NetworkListActivity
import com.wifi.visualizer.ui.onboarding.OnboardingActivity
import com.wifi.visualizer.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val arManager = ArSessionManager(this)

    private val requiredPermissions: Array<String> get() = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) onAllPermissionsGranted()
        else showPermissionRationale()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("onboarding_done", false)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        setupButtons()
        observeViewModel()
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        try {
            when (ArCoreApk.getInstance().requestInstall(this, !requestedArInstall)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> requestedArInstall = true
                else -> {}
            }
        } catch (_: Exception) {}
    }

    private var requestedArInstall = false

    private fun setupButtons() {
        binding.btnStartMapping.setOnClickListener {
            if (!hasAllPermissions()) { checkAndRequestPermissions(); return@setOnClickListener }
            if (arManager.checkArAvailability() == ArAvailability.READY) {
                viewModel.startNewSession()
                startActivity(Intent(this, ArMappingActivity::class.java))
            } else {
                AlertDialog.Builder(this)
                    .setTitle("AR Not Available")
                    .setMessage("ARCore is not supported on this device.\n\nYou can still use the 2-D heatmap and analytics from collected data.")
                    .setPositiveButton("Open Heatmap") { _, _ -> startActivity(Intent(this, HeatmapActivity::class.java)) }
                    .setNegativeButton("Cancel", null).show()
            }
        }

        binding.btnViewHeatmap.setOnClickListener {
            startActivity(Intent(this, HeatmapActivity::class.java))
        }
        binding.btnNetworks.setOnClickListener {
            startActivity(Intent(this, NetworkListActivity::class.java))
        }
        binding.btnChannels.setOnClickListener {
            startActivity(Intent(this, ChannelAnalyzerActivity::class.java))
        }
        binding.btnMeshOptimizer.setOnClickListener {
            startActivity(Intent(this, MeshOptimizerActivity::class.java))
        }
        binding.btnAnalytics.setOnClickListener {
            startActivity(Intent(this, AnalyticsDashboardActivity::class.java).apply {
                putExtra(AnalyticsDashboardActivity.EXTRA_SESSION_ID, viewModel.currentSessionId)
            })
        }
        binding.btnExportCsv.setOnClickListener { viewModel.exportAllCsv() }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.wifiSnapshot.collect { snap ->
                        if (snap == null) {
                            binding.tvSsid.text = "No Wi-Fi"
                            binding.tvRssi.text = "—"; binding.tvFreq.text = "—"; binding.tvLinkSpeed.text = "—"
                            binding.signalBar.progress = 0
                        } else {
                            binding.tvSsid.text = snap.connectedSsid
                            binding.tvRssi.text = "${snap.rssi} dBm"
                            binding.tvFreq.text = "${snap.frequency} MHz"
                            binding.tvLinkSpeed.text = "${snap.linkSpeed} Mbps"
                            val pct = ((snap.rssi + 100).coerceIn(0, 70) * 100 / 70)
                            binding.signalBar.progress = pct
                            val q = com.wifi.visualizer.data.model.SignalQuality.from(snap.rssi)
                            binding.tvQualityLabel.text = q.label
                            binding.tvQualityLabel.setTextColor(android.graphics.Color.parseColor(q.colorHex))
                        }
                    }
                }
                launch {
                    viewModel.scanCount.collect { count ->
                        binding.tvScanCount.text = "$count scans stored"
                    }
                }
                launch {
                    viewModel.exportResult.collect { result ->
                        result
                            .onSuccess { file ->
                                Toast.makeText(this@MainActivity, "Exported: ${file.name}", Toast.LENGTH_LONG).show()
                                val uri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", file)
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, "Share CSV"))
                            }
                            .onFailure { Toast.makeText(this@MainActivity, "Export failed", Toast.LENGTH_SHORT).show() }
                    }
                }
            }
        }
        viewModel.startScanning()
    }

    private fun hasAllPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkAndRequestPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) onAllPermissionsGranted()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun onAllPermissionsGranted() { binding.btnStartMapping.isEnabled = true }

    private fun showPermissionRationale() {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("Camera, Location, and Nearby Devices permissions are all required for full functionality.")
            .setPositiveButton("Grant") { _, _ -> checkAndRequestPermissions() }
            .setNegativeButton("Dismiss", null).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu); return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_delete_all -> {
            AlertDialog.Builder(this)
                .setTitle("Delete All Data")
                .setMessage("This permanently erases all scan records and sessions. Continue?")
                .setPositiveButton("Delete") { _, _ -> viewModel.deleteAllData() }
                .setNegativeButton("Cancel", null).show()
            true
        }
        R.id.action_about -> {
            AlertDialog.Builder(this)
                .setTitle("Wi-Fi Visualizer AR v2.0")
                .setMessage(
                    "Advanced AR Wi-Fi signal mapper with AI-powered analysis.\n\n" +
                    "Features:\n" +
                    "• ARCore 3-D pillar heatmap\n" +
                    "• IDW-interpolated ground mesh\n" +
                    "• Signal-to-router direction arrow\n" +
                    "• IEEE 802.11 path-loss modelling\n" +
                    "• Dead zone detection\n" +
                    "• Mesh node placement AI\n" +
                    "• Channel congestion analyzer\n" +
                    "• Export: CSV / JSON / KML / HTML\n\n" +
                    "🟢 > -50 dBm  Excellent\n" +
                    "🟡 -50 to -70  Good\n" +
                    "🟠 -70 to -80  Fair\n" +
                    "🔴 < -80 dBm  Poor"
                )
                .setPositiveButton("OK", null).show()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}
