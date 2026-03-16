╔══════════════════════════════════════════════════════════════════════════════╗
║           Wi-Fi Visualizer AR — v2.0  Advanced Edition                      ║
║           Buildable Android Project (Kotlin / ARCore / Room)                ║
╚══════════════════════════════════════════════════════════════════════════════╝

═══════════════════════════════════════════════════════════════
  WHAT'S NEW IN v2.0
═══════════════════════════════════════════════════════════════

  AR RENDERING
  ────────────
  • IDW-interpolated ground mesh (HeatmapMeshRenderer)
    - Real-time Inverse Distance Weighting fills gaps between pillars
    - Smooth RGB heatmap carpet at floor level, semi-transparent
    - Rebuilds every 15 s as new data arrives
  • "Walk this way" direction arrow (DirectionArrowRenderer)
    - World-space 3-D arrow points toward strongest measured signal
    - Colour-coded by the predicted RSSI at the target
    - Toggle on/off from the AR HUD
  • Improved pillar HUD
    - Signal strength progress bar
    - Live coverage percentage
    - Dead zone warning banner

  AI INTELLIGENCE ENGINE
  ──────────────────────
  • IdwInterpolator      — Inverse Distance Weighting (power=2, configurable)
  • PathLossModel        — IEEE 802.11 log-distance calibration from data
  • DeadZoneDetector     — Flood-fill clustering → mesh node placement
  • ChannelAnalyzer      — Per-channel congestion scoring (2.4/5 GHz)
  • SignalIntelligenceEngine — Orchestrates all of the above:
      - Anomaly detection (Z-score > 2.5σ)
      - Signal trend (Improving / Stable / Degrading)
      - Directional strength map (8 compass sectors)
      - AI insight generation with severity levels

  SCREENS & FEATURES
  ──────────────────
  • Analytics Dashboard  — Full session deep-dive:
      - Coverage score gauge
      - RSSI over time line chart (sparkline)
      - Signal distribution bar chart
      - Directional strength table
      - AI path-loss model display (auto-calibrated n exponent)
      - AI insight cards (INFO / WARNING / ACTION REQUIRED)
      - Anomaly count badge
  • Channel Analyzer     — 2.4 GHz + 5 GHz bar charts, recommendations
  • Mesh Optimizer       — Canvas top-down map:
      - Colour-coded scan points
      - Red dead-zone circles
      - Cyan stars for AP placement recommendations
      - Coverage grade, dead area m²
  • Network List         — All visible SSIDs, RSSI bars, channel congestion
  • Heatmap (2-D)        — Improved with session spinner, sparkline, stats strip
  • Export               — CSV / JSON / KML (Google Earth) / HTML report

  DATABASE
  ────────
  • Room v2 with 3 tables:
      - wifi_scan_results  (AR-anchored measurements)
      - signal_samples     (rapid temporal samples for sparklines)
      - sessions           (metadata, label, coverage score)

═══════════════════════════════════════════════════════════════
  BUILD REQUIREMENTS
═══════════════════════════════════════════════════════════════

  • JDK 17 or later
  • Android SDK  (API 34 + build-tools 34.0.0 + platform-tools)
  • ANDROID_HOME env var pointing at SDK root
  • sdkmanager --licenses accepted

  Android Studio (recommended):
    File → Open → WiFiVisualizerAR → wait for Gradle sync
    Build → Generate Signed / Debug APK

  Command line:
    # 1. Download the Gradle wrapper JAR (one-time, ~60 KB):
    curl -L "https://services.gradle.org/distributions/gradle-8.2.2-bin.zip" \
         -o /tmp/gradle.zip
    # Or let Android Studio sync handle it automatically.

    # 2. Build debug APK:
    cd WiFiVisualizerAR
    chmod +x gradlew
    ./gradlew assembleDebug

    # 3. Install on device:
    adb install app/build/outputs/apk/debug/app-debug.apk

    Alternatively run build.sh which automates all of the above.

═══════════════════════════════════════════════════════════════
  DEVICE REQUIREMENTS
═══════════════════════════════════════════════════════════════

  • Android 7.0+ (API 24) minimum
  • ARCore-supported device for 3-D mapping:
      https://developers.google.com/ar/devices
  • App degrades gracefully on non-ARCore devices:
      2-D heatmap, channel analyzer and analytics still fully functional

  Verified permissions needed at runtime:
    CAMERA               → AR camera feed
    ACCESS_FINE_LOCATION → Wi-Fi scan results on Android 9+
    NEARBY_WIFI_DEVICES  → Wi-Fi scan results on Android 13+

═══════════════════════════════════════════════════════════════
  SIGNAL COLOUR LEGEND
═══════════════════════════════════════════════════════════════

  🟢 Excellent  > -50 dBm   Tall pillar / bright green
  🟡 Good       -50 to -70  Medium pillar / yellow
  🟠 Fair       -70 to -80  Short pillar / orange
  🔴 Poor       < -80 dBm   Minimal pillar / red

═══════════════════════════════════════════════════════════════
  ARCHITECTURE OVERVIEW
═══════════════════════════════════════════════════════════════

  app/src/main/java/com/wifi/visualizer/
  ├── ar/
  │   ├── ArSessionManager.kt        ARCore session lifecycle
  │   ├── BackgroundRenderer.kt      Camera OES texture
  │   ├── PillarRenderer.kt          Colour-height pillars + PillarData
  │   ├── HeatmapMeshRenderer.kt  ★  IDW ground-plane mesh (NEW)
  │   └── DirectionArrowRenderer.kt ★ "Walk here" arrow (NEW)
  ├── data/
  │   ├── db/
  │   │   ├── AppDatabase.kt         Room DB (v2)
  │   │   ├── WifiScanDao.kt         AR scan DAO
  │   │   └── SignalSampleDao.kt  ★  Temporal sample + session DAOs (NEW)
  │   ├── model/
  │   │   ├── WifiScanResult.kt      AR-anchored scan entity
  │   │   └── SignalHistory.kt    ★  SignalSample + SessionMetadata (NEW)
  │   └── repo/
  │       ├── WifiScanRepository.kt  Single source of truth
  │       └── ExportManager.kt    ★  CSV/JSON/KML/HTML export (NEW)
  ├── intelligence/              ★  ENTIRELY NEW PACKAGE
  │   ├── IdwInterpolator.kt         IDW prediction + grid builder
  │   ├── PathLossModel.kt           IEEE 802.11 calibration
  │   ├── ChannelAnalyzer.kt         Channel congestion scoring
  │   ├── DeadZoneDetector.kt        Dead zone flood-fill + AP placement
  │   └── SignalIntelligenceEngine.kt  Master orchestrator
  ├── ui/
  │   ├── main/MainActivity.kt       Home screen (updated nav)
  │   ├── ar/ArMappingActivity.kt    AR screen (mesh + arrow + analysis)
  │   ├── heatmap/HeatmapActivity.kt 2-D scatter + session filter
  │   ├── analytics/              ★  NEW
  │   │   └── AnalyticsDashboardActivity.kt
  │   ├── channels/               ★  NEW
  │   │   └── ChannelAnalyzerActivity.kt
  │   ├── mesh/                   ★  NEW
  │   │   └── MeshOptimizerActivity.kt (+ MeshMapView canvas)
  │   ├── networkList/            ★  NEW
  │   │   └── NetworkListActivity.kt
  │   └── onboarding/
  │       ├── OnboardingActivity.kt
  │       └── OnboardingAdapter.kt
  ├── utils/
  │   └── WifiScanner.kt             Reactive WifiManager wrapper
  └── viewmodel/
      ├── MainViewModel.kt           Shared session + scan state
      ├── ArViewModel.kt          ★  AR screen dedicated VM (NEW)
      └── AnalyticsViewModel.kt   ★  Analytics screen VM (NEW)

  ★ = new or substantially upgraded in v2.0

═══════════════════════════════════════════════════════════════
  TESTING GUIDE
═══════════════════════════════════════════════════════════════

  1. PERMISSIONS    Grant all prompts; verify HUD shows live SSID/RSSI
  2. AR MAPPING     Walk >0.5 m between points; pillars should appear
  3. MESH HEATMAP   After 5+ points, tap "Analyze" → mesh appears on floor
  4. DIRECTION ARROW Arrow should point toward strongest recorded signal
  5. DEAD ZONE ALERT Walk into area with RSSI < -80; red banner appears
  6. ANALYTICS      Open Analytics → verify charts populate with session data
  7. CHANNEL CHART  Open Channel Analyzer → verify 2.4/5 GHz bars visible
  8. MESH OPTIMIZER Open with data → cyan stars mark AP recommendations
  9. NETWORK LIST   Lists all SSIDs; tap to select as tracked network
  10. EXPORT        All four formats (CSV/JSON/KML/HTML) must be shareable

═══════════════════════════════════════════════════════════════
  KNOWN LIMITATIONS
═══════════════════════════════════════════════════════════════

  • Android 9+ throttles startScan() to 4 calls/2 min in foreground.
    The HUD updates via RSSI_CHANGED regardless; pillar spacing is
    distance-gated (0.5 m) rather than time-gated to work around this.

  • IDW grid rebuild happens on the GL thread to avoid VBO races.
    On very slow devices (< 200 scan points) this may cause a brief
    frame drop; consider increasing SCAN_INTERVAL if needed.

  • KML export only includes points with non-zero GPS coordinates.
    GPS is not captured by default (ARCore provides relative positions).
    Future: fuse ARCore poses with FusedLocationProviderClient.

  • The HTML report uses inline JavaScript for the scatter canvas.
    Some email clients may strip it; share via Files or a browser instead.

