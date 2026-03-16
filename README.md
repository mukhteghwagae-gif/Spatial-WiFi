# 📡 Wi-Fi Visualizer AR — v2.0

[![Android CI](https://github.com/YOUR_USERNAME/WiFiVisualizerAR/actions/workflows/build.yml/badge.svg)](https://github.com/YOUR_USERNAME/WiFiVisualizerAR/actions/workflows/build.yml)
![Min SDK](https://img.shields.io/badge/minSdk-24-green)
![Target SDK](https://img.shields.io/badge/targetSdk-34-blue)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9.23-purple)
![License](https://img.shields.io/badge/license-MIT-orange)

An advanced Android app that visualises Wi-Fi signal strength as **colour-coded 3D pillars anchored in the real world** using ARCore — with an AI-powered analysis engine, interactive 2D heatmap, channel analyzer, mesh optimizer, and multi-format data export.

---

## ✨ Features

### AR Mapping
| Feature | Description |
|---------|-------------|
| 🏛 Signal Pillars | Colour-height coded cuboids at each scan point |
| 🌈 IDW Ground Mesh | Real-time inverse-distance-weighted heatmap carpet |
| 🧭 Direction Arrow | "Walk this way" arrow pointing toward strongest signal |
| ⚠️ Dead Zone Alert | Red banner when entering weak coverage zones |

### Intelligence Engine
| Module | What it does |
|--------|-------------|
| `IdwInterpolator` | IDW prediction with configurable power and radius |
| `PathLossModel` | IEEE 802.11 log-distance, self-calibrating from data |
| `DeadZoneDetector` | Flood-fill clustering + AP placement recommendations |
| `ChannelAnalyzer` | Per-channel congestion score for 2.4/5/6 GHz |
| `SignalIntelligenceEngine` | Orchestrates all above + anomaly detection + trend |

### Screens
- **AR Mapping** — 3D pillar + mesh + arrow
- **2D Heatmap** — scatter chart with session filter and sparkline
- **Analytics Dashboard** — coverage gauge, RSSI time series, distribution chart, AI insight cards
- **Channel Analyzer** — congestion bar charts for 2.4 GHz and 5 GHz
- **Mesh Optimizer** — top-down canvas map with dead zones and AP star markers
- **Network List** — all visible SSIDs with RSSI bar and channel congestion
- **Onboarding** — 3-step first-launch guide

### Export
- **CSV** — flat table for Excel/Sheets analysis
- **JSON** — structured with session metadata
- **KML** — importable in Google Earth / Google Maps
- **HTML** — self-contained styled report with embedded canvas chart

---

## 🏗 Architecture

```
app/src/main/java/com/wifi/visualizer/
├── ar/                   OpenGL ES 2.0 renderers (ARCore)
│   ├── ArSessionManager
│   ├── BackgroundRenderer
│   ├── PillarRenderer
│   ├── HeatmapMeshRenderer  ← IDW ground mesh
│   └── DirectionArrowRenderer
├── data/
│   ├── db/               Room DAOs + AppDatabase (v2)
│   ├── model/            WifiScanResult, SignalSample, SessionMetadata
│   └── repo/             WifiScanRepository, ExportManager
├── intelligence/         Pure-Kotlin AI analysis (no Android deps)
│   ├── IdwInterpolator
│   ├── PathLossModel
│   ├── DeadZoneDetector
│   ├── ChannelAnalyzer
│   └── SignalIntelligenceEngine
├── ui/                   Activities (ViewBinding)
├── utils/                WifiScanner (reactive Flow wrapper)
└── viewmodel/            MainViewModel, ArViewModel, AnalyticsViewModel
```

**Pattern:** MVVM · Room + Flow · Coroutines · ViewBinding · no Fragments

---

## 🚀 Building

### Requirements
| Tool | Version |
|------|---------|
| JDK | 17+ |
| Android SDK | API 34 |
| Build-tools | 34.0.0 |
| Gradle | 8.6 (wrapper included) |

### Quick start
```bash
# Clone
git clone https://github.com/YOUR_USERNAME/WiFiVisualizerAR.git
cd WiFiVisualizerAR

# Copy SDK path template
cp local.properties.example local.properties
# Edit local.properties → set sdk.dir=/path/to/your/sdk

# Build debug APK
./gradlew assembleDebug

# Install on connected device
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Run unit tests
```bash
./gradlew testDebugUnitTest
```

### Android Studio
Open the project root → wait for Gradle sync → **Run ▶**.

---

## 🔐 Release Signing (GitHub Actions)

Add these secrets to your repository (**Settings → Secrets → Actions**):

| Secret | Value |
|--------|-------|
| `KEYSTORE_BASE64` | Base64-encoded `.jks` keystore file |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |

Create a signed release by pushing a version tag:
```bash
git tag v2.0.0
git push origin v2.0.0
```

The workflow will build, sign, and attach the APK to a GitHub Release automatically.

---

## 📱 Device Requirements

| Requirement | Notes |
|-------------|-------|
| Android 7.0+ (API 24) | Minimum |
| Camera | Required |
| Wi-Fi hardware | Required |
| ARCore support | **Optional** — app degrades gracefully; all non-AR screens work on any device |

Check ARCore device support: https://developers.google.com/ar/devices

---

## 📊 Signal Colour Legend

| Colour | RSSI | Quality |
|--------|------|---------|
| 🟢 Green | > −50 dBm | Excellent |
| 🟡 Yellow | −50 to −70 | Good |
| 🟠 Orange | −70 to −80 | Fair |
| 🔴 Red | < −80 dBm | Poor |

---

## ⚠️ Known Limitations

- **Android 9+ scan throttling** — OS limits `startScan()` to 4 calls/2 min in foreground. The HUD still updates via `RSSI_CHANGED`; pillar placement is distance-gated (0.5 m) not time-gated.
- **KML GPS coordinates** — ARCore provides relative world-space positions, not GPS. KML export only includes points with non-zero GPS. Future: fuse with `FusedLocationProviderClient`.
- **IDW on GL thread** — grid rebuild happens on the render thread to avoid VBO races. May cause a brief frame drop on very large grids (>200 points); the renderer auto-subsamples to stay within 16-bit index limits.

---

## 🪪 License

```
MIT License — Copyright (c) 2025 Wi-Fi Visualizer AR Contributors
```
