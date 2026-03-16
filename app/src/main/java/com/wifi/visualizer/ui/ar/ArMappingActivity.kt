package com.wifi.visualizer.ui.ar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.ar.core.*
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.wifi.visualizer.ar.*
import com.wifi.visualizer.data.model.SignalQuality
import com.wifi.visualizer.data.model.WifiScanResult
import com.wifi.visualizer.databinding.ActivityArMappingBinding
import com.wifi.visualizer.intelligence.IdwInterpolator
import com.wifi.visualizer.intelligence.InterpolationGrid
import com.wifi.visualizer.intelligence.SignalIntelligenceEngine
import com.wifi.visualizer.viewmodel.ArViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

private const val TAG           = "ArMappingActivity"
private const val SCAN_INTERVAL = 2_000L
private const val MIN_SPACING   = 0.5f

/**
 * Advanced AR Mapping Activity featuring:
 *   - Colour-height coded pillars (PillarRenderer)
 *   - IDW-interpolated ground mesh (HeatmapMeshRenderer)
 *   - Direction arrow to strongest signal (DirectionArrowRenderer)
 *   - Dead-zone entry alert
 *   - Periodic AI analysis with coverage score
 *   - Live HUD with signal bars
 */
class ArMappingActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private lateinit var binding: ActivityArMappingBinding
    private val viewModel: ArViewModel by viewModels()

    private val arManager      = ArSessionManager(this)
    private var session        : Session? = null

    private val bgRenderer     = BackgroundRenderer()
    private val pillarRenderer = PillarRenderer()
    private val meshRenderer   = HeatmapMeshRenderer()
    private val arrowRenderer  = DirectionArrowRenderer()

    private val pillars      = CopyOnWriteArrayList<PillarData>()
    private val meshDirty    = AtomicBoolean(false)
    @Volatile private var lastPillarPos  : FloatArray? = null
    @Volatile private var latestCameraPose : Pose? = null
    @Volatile private var arrowAngleDeg  = 0f
    @Volatile private var arrowRssi      = -70
    @Volatile private var showMesh       = true
    @Volatile private var showArrow      = true
    @Volatile private var latestGrid     : InterpolationGrid? = null

    private val idw          = IdwInterpolator()
    private val intelligence = SignalIntelligenceEngine()
    private var scanJob      : Job? = null
    private var analysisJob  : Job? = null
    private val mainHandler  = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArMappingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupGlSurface()
        setupButtons()
        observeHud()
    }

    override fun onResume() {
        super.onResume()
        try {
            if (session == null) {
                // Session is created here but the camera texture ID is NOT yet valid —
                // we must wait until onSurfaceCreated on the GL thread.
                // Store a flag so onSurfaceCreated can bind the texture once ready.
                session = arManager.createSession()
                pendingTextureAttach = true
            }
            arManager.resume()
            binding.surfaceView.onResume()
            startPeriodicScanning()
            startPeriodicAnalysis()
        } catch (e: CameraNotAvailableException) {
            Toast.makeText(this, "Camera unavailable", Toast.LENGTH_LONG).show(); finish()
        } catch (e: Exception) {
            Toast.makeText(this, "AR error: ${e.message}", Toast.LENGTH_LONG).show(); finish()
        }
    }

    @Volatile private var pendingTextureAttach = false

    override fun onPause() {
        super.onPause()
        arManager.pause(); binding.surfaceView.onPause()
        scanJob?.cancel(); analysisJob?.cancel()
    }

    override fun onDestroy() { super.onDestroy(); arManager.close() }

    // ── GL ────────────────────────────────────────────────────────────────────

    override fun onSurfaceCreated(gl: GL10?, cfg: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        bgRenderer.createOnGlThread()
        pillarRenderer.createOnGlThread()
        meshRenderer.createOnGlThread()
        arrowRenderer.createOnGlThread()
        // Now the OES texture ID is valid — attach it to the session
        val textureId = bgRenderer.getTextureId()
        session?.setCameraTextureName(textureId)
        pendingTextureAttach = false
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        session?.setDisplayGeometry(windowManager.defaultDisplay.rotation, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        try {
            val frame  = s.update()
            val camera = frame.camera
            bgRenderer.draw(frame)
            latestCameraPose = camera.displayOrientedPose
            if (camera.trackingState != TrackingState.TRACKING) return

            val proj = FloatArray(16).also { camera.getProjectionMatrix(it, 0, 0.1f, 100f) }
            val view = FloatArray(16).also { camera.getViewMatrix(it, 0) }
            val vp   = FloatArray(16)
            android.opengl.Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

            if (meshDirty.getAndSet(false)) latestGrid?.let { meshRenderer.updateGrid(it) }
            if (showMesh) meshRenderer.draw(vp)
            pillarRenderer.draw(vp, pillars.toList())
            if (showArrow) {
                val pose = latestCameraPose
                if (pose != null) arrowRenderer.draw(vp, pose.tx(), pose.ty(), pose.tz(), arrowAngleDeg, arrowRssi)
            }
        } catch (e: Exception) { Log.e(TAG, "Draw error", e) }
    }

    // ── Scanning ──────────────────────────────────────────────────────────────

    private fun startPeriodicScanning() {
        scanJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) { delay(SCAN_INTERVAL); trySaveScan(); viewModel.triggerScan() }
            }
        }
    }

    private fun trySaveScan() {
        val pose = latestCameraPose ?: return
        val snap = viewModel.wifiSnapshot.value ?: return
        val cx = pose.tx(); val cy = pose.ty(); val cz = pose.tz()

        val last = lastPillarPos
        if (last != null) {
            val d = sqrt((cx-last[0]).let{it*it} + (cy-last[1]).let{it*it} + (cz-last[2]).let{it*it})
            if (d < MIN_SPACING) return
        }

        session?.let { s ->
            runCatching {
                val anchor = s.createAnchor(pose)
                val rssi   = snap.rssi
                val result = WifiScanResult(
                    ssid = snap.connectedSsid, bssid = snap.connectedBssid,
                    rssi = rssi, frequency = snap.frequency, linkSpeed = snap.linkSpeed,
                    arPosX = cx, arPosY = cy, arPosZ = cz, sessionId = viewModel.currentSessionId
                )
                pillars.add(PillarData(anchor, result, PillarData.rssiToHeight(rssi), PillarData.qualityToRgb(SignalQuality.from(rssi))))
                lastPillarPos = floatArrayOf(cx, cy, cz)
                viewModel.saveScan(cx, cy, cz)

                // Update direction arrow
                val dir = intelligence.strongestSignalDirection(cx, cz, pillars.map { it.scanResult })
                if (dir != null) { arrowAngleDeg = dir; arrowRssi = rssi }

                // Dead zone alert
                val predicted = idw.predict(cx, cz, pillars.map { it.scanResult })
                if (predicted != null && predicted < -80f) {
                    mainHandler.post {
                        binding.deadZoneAlert.visibility = View.VISIBLE
                        mainHandler.postDelayed({ binding.deadZoneAlert.visibility = View.GONE }, 3000)
                    }
                }
                mainHandler.post { binding.tvPillarCount.text = "${pillars.size} pts" }
            }
        }
    }

    private fun startPeriodicAnalysis() {
        analysisJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(15_000)
                    if (pillars.size >= 5) {
                        val analysis = intelligence.analyzeSession(pillars.map { it.scanResult })
                        analysis.interpolationGrid?.let { latestGrid = it; meshDirty.set(true) }
                        mainHandler.post {
                            binding.tvCoverage.text = "Coverage: ${analysis.coverageScore}%"
                            binding.tvInsight.text  = analysis.insights.firstOrNull()?.title ?: ""
                        }
                    }
                }
            }
        }
    }

    private fun observeHud() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.wifiSnapshot.collect { snap ->
                    snap ?: return@collect
                    binding.tvHudSsid.text  = snap.connectedSsid
                    binding.tvHudRssi.text  = "${snap.rssi} dBm"
                    binding.tvHudFreq.text  = "${snap.frequency} MHz · ${snap.linkSpeed} Mbps"
                    val q = SignalQuality.from(snap.rssi)
                    binding.tvHudQuality.text = q.label
                    binding.tvHudQuality.setTextColor(android.graphics.Color.parseColor(q.colorHex))
                    binding.signalStrengthBar.progress = ((snap.rssi + 100).coerceIn(0, 70) * 100 / 70)
                }
            }
        }
    }

    private fun setupGlSurface() {
        binding.surfaceView.apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@ArMappingActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }

    private fun setupButtons() {
        binding.btnStopMapping.setOnClickListener { finish() }
        binding.btnToggleMesh.setOnClickListener {
            showMesh = !showMesh
            binding.btnToggleMesh.text = if (showMesh) "Hide Mesh" else "Show Mesh"
        }
        binding.btnToggleArrow.setOnClickListener {
            showArrow = !showArrow
            binding.btnToggleArrow.text = if (showArrow) "Hide Arrow" else "Show Arrow"
        }
        binding.btnClearPillars.setOnClickListener {
            pillars.forEach { it.anchor.detach() }; pillars.clear()
            lastPillarPos = null; latestGrid = null
            binding.tvPillarCount.text = "0 pts"
            binding.tvCoverage.text    = "Coverage: —"
        }
        binding.btnAnalyze.setOnClickListener {
            if (pillars.size < 3) { Toast.makeText(this,"Collect 3+ points first",Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            lifecycleScope.launch {
                val analysis = intelligence.analyzeSession(pillars.map { it.scanResult })
                analysis.interpolationGrid?.let { latestGrid = it; meshDirty.set(true) }
                val msg = "Coverage: ${analysis.coverageScore}%\n" +
                          analysis.insights.take(2).joinToString("\n") { "• ${it.title}" }
                Toast.makeText(this@ArMappingActivity, msg, Toast.LENGTH_LONG).show()
                mainHandler.post { binding.tvCoverage.text = "Coverage: ${analysis.coverageScore}%" }
            }
        }
    }
}
