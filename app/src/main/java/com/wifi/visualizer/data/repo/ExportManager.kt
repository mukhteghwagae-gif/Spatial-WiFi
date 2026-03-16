package com.wifi.visualizer.data.repo

import android.content.Context
import com.wifi.visualizer.data.model.WifiScanResult
import com.wifi.visualizer.intelligence.SessionAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles exporting session data in multiple formats:
 *   • CSV  — flat table for spreadsheet analysis
 *   • JSON — structured for programmatic consumption
 *   • KML  — for Google Earth / Google Maps import
 *   • HTML — self-contained styled report
 */
object ExportManager {

    private val ts get() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    suspend fun exportCsv(
        context   : Context,
        records   : List<WifiScanResult>,
        sessionId : String? = null
    ): File = withContext(Dispatchers.IO) {
        val dir  = exportDir(context)
        val file = File(dir, "wifi_scan_${ts}.csv")
        file.bufferedWriter().use { w ->
            w.write("id,timestamp,ssid,bssid,rssi,frequency,link_speed,latitude,longitude,ar_x,ar_y,ar_z,session_id,band,quality\n")
            records.forEach { r ->
                w.write("${r.id},${r.timestamp},\"${r.ssid.csv()}\",${r.bssid},${r.rssi},${r.frequency}," +
                        "${r.linkSpeed},${r.latitude},${r.longitude},${r.arPosX},${r.arPosY},${r.arPosZ}," +
                        "\"${r.sessionId}\",${r.band},${r.signalQuality.label}\n")
            }
        }
        file
    }

    suspend fun exportJson(
        context  : Context,
        records  : List<WifiScanResult>,
        analysis : SessionAnalysis? = null
    ): File = withContext(Dispatchers.IO) {
        val dir  = exportDir(context)
        val file = File(dir, "wifi_scan_${ts}.json")
        file.bufferedWriter().use { w ->
            w.write("{\n")
            w.write("  \"exportedAt\": \"${Date()}\",\n")
            w.write("  \"totalScans\": ${records.size},\n")
            if (analysis != null) {
                w.write("  \"coverageScore\": ${analysis.coverageScore},\n")
                w.write("  \"signalMean\": ${analysis.signalStats.mean},\n")
                w.write("  \"deadZones\": ${analysis.deadZoneReport.deadZones.size},\n")
            }
            w.write("  \"scans\": [\n")
            records.forEachIndexed { idx, r ->
                val comma = if (idx < records.lastIndex) "," else ""
                w.write("    {\"id\":${r.id},\"timestamp\":${r.timestamp},\"ssid\":\"${r.ssid.json()}\"," +
                        "\"bssid\":\"${r.bssid}\",\"rssi\":${r.rssi},\"frequency\":${r.frequency}," +
                        "\"linkSpeed\":${r.linkSpeed},\"lat\":${r.latitude},\"lng\":${r.longitude}," +
                        "\"arX\":${r.arPosX},\"arY\":${r.arPosY},\"arZ\":${r.arPosZ}," +
                        "\"quality\":\"${r.signalQuality.label}\"}$comma\n")
            }
            w.write("  ]\n}\n")
        }
        file
    }

    suspend fun exportKml(
        context  : Context,
        records  : List<WifiScanResult>
    ): File = withContext(Dispatchers.IO) {
        val dir  = exportDir(context)
        val file = File(dir, "wifi_scan_${ts}.kml")
        // Only include scans that have real GPS coordinates
        val geoRecords = records.filter { it.latitude != 0.0 && it.longitude != 0.0 }
        file.bufferedWriter().use { w ->
            w.write("""<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <name>Wi-Fi Visualizer AR Export</name>
    <description>Wi-Fi signal strength heatmap export from Wi-Fi Visualizer AR</description>
    <Style id="excellent"><IconStyle><color>ff4caf50</color><scale>1.2</scale></IconStyle></Style>
    <Style id="good"><IconStyle><color>ff00c8ff</color><scale>1.0</scale></IconStyle></Style>
    <Style id="fair"><IconStyle><color>ff0080ff</color><scale>0.8</scale></IconStyle></Style>
    <Style id="poor"><IconStyle><color>ff0000ff</color><scale>0.6</scale></IconStyle></Style>
""")
            geoRecords.forEach { r ->
                val styleId = r.signalQuality.name.lowercase()
                w.write("""    <Placemark>
      <name>${r.ssid.xml()} (${r.rssi} dBm)</name>
      <description>BSSID: ${r.bssid}&#10;Frequency: ${r.frequency} MHz&#10;Quality: ${r.signalQuality.label}</description>
      <styleUrl>#$styleId</styleUrl>
      <Point><coordinates>${r.longitude},${r.latitude},0</coordinates></Point>
    </Placemark>
""")
            }
            w.write("  </Document>\n</kml>")
        }
        file
    }

    suspend fun exportHtmlReport(
        context  : Context,
        records  : List<WifiScanResult>,
        analysis : SessionAnalysis
    ): File = withContext(Dispatchers.IO) {
        val dir  = exportDir(context)
        val file = File(dir, "wifi_report_${ts}.html")
        val stats = analysis.signalStats
        val dead  = analysis.deadZoneReport

        // Build inline scatter chart data
        val scatterPoints = records.joinToString(",") { r ->
            val color = r.signalQuality.colorHex
            "{x:${r.arPosX.toInt()},y:${r.arPosZ.toInt()},rssi:${r.rssi},color:'$color'}"
        }

        file.bufferedWriter().use { w -> w.write("""<!DOCTYPE html>
<html lang="en"><head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Wi-Fi Visualizer AR — Signal Report</title>
<style>
  body{font-family:system-ui,sans-serif;background:#0f172a;color:#e2e8f0;margin:0;padding:20px}
  h1{color:#38bdf8;font-size:1.8em}h2{color:#7dd3fc;font-size:1.2em;border-bottom:1px solid #1e40af;padding-bottom:6px}
  .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:16px;margin:16px 0}
  .card{background:#1e293b;border-radius:12px;padding:16px;border-left:4px solid #38bdf8}
  .card.warn{border-left-color:#f59e0b}.card.danger{border-left-color:#ef4444}.card.ok{border-left-color:#22c55e}
  .big{font-size:2.2em;font-weight:700;color:#38bdf8}.label{font-size:0.8em;color:#94a3b8;text-transform:uppercase}
  canvas{border-radius:8px;background:#0f172a}
  .insight{background:#1e293b;border-radius:8px;padding:12px;margin:8px 0;border-left:4px solid #38bdf8}
  .insight.warn{border-left-color:#f59e0b}.insight.danger{border-left-color:#ef4444}
  table{width:100%;border-collapse:collapse;font-size:0.85em}
  th{background:#1e40af;padding:8px;text-align:left}td{padding:6px 8px;border-bottom:1px solid #1e293b}
  tr:hover td{background:#1e293b}
</style></head><body>
<h1>📡 Wi-Fi Visualizer AR — Signal Report</h1>
<p style="color:#64748b">Generated: ${Date()} &nbsp;|&nbsp; ${records.size} scan points</p>

<h2>📊 Signal Statistics</h2>
<div class="grid">
  <div class="card ${if(stats.mean > -65) "ok" else if(stats.mean > -75) "warn" else "danger"}">
    <div class="big">${stats.mean.toInt()} dBm</div><div class="label">Average RSSI</div>
  </div>
  <div class="card ${if(stats.max > -50) "ok" else "warn"}">
    <div class="big">${stats.max} dBm</div><div class="label">Best Signal</div>
  </div>
  <div class="card ${if(stats.min > -80) "warn" else "danger"}">
    <div class="big">${stats.min} dBm</div><div class="label">Worst Signal</div>
  </div>
  <div class="card ${if(dead.coveragePercent > 75) "ok" else if(dead.coveragePercent > 50) "warn" else "danger"}">
    <div class="big">${dead.coveragePercent.toInt()}%</div><div class="label">Coverage (-70dBm)</div>
  </div>
  <div class="card ${if(analysis.stabilityScore > 75) "ok" else "warn"}">
    <div class="big">${analysis.stabilityScore}%</div><div class="label">Signal Stability</div>
  </div>
  <div class="card">
    <div class="big">${analysis.coverageScore}%</div><div class="label">Overall Score</div>
  </div>
</div>

<h2>🗺️ Signal Map (AR World Space)</h2>
<canvas id="map" width="700" height="400"></canvas>

<h2>💡 AI Insights</h2>
${analysis.insights.joinToString("") { i ->
    val cls = when(i.severity) {
        com.wifi.visualizer.intelligence.InsightSeverity.ACTION_REQUIRED -> "danger"
        com.wifi.visualizer.intelligence.InsightSeverity.WARNING -> "warn"
        else -> ""
    }
    "<div class='insight $cls'><strong>${i.title}</strong><br><span style='color:#94a3b8'>${i.detail}</span></div>"
}}

<h2>📋 Scan Data (first 200)</h2>
<table><thead><tr><th>SSID</th><th>RSSI</th><th>Freq</th><th>Quality</th><th>AR X</th><th>AR Z</th></tr></thead>
<tbody>
${records.take(200).joinToString("") { r ->
    "<tr><td>${r.ssid}</td><td style='color:${r.signalQuality.colorHex}'>${r.rssi} dBm</td>" +
    "<td>${r.frequency} MHz</td><td>${r.signalQuality.label}</td>" +
    "<td>${String.format("%.1f", r.arPosX)}m</td><td>${String.format("%.1f", r.arPosZ)}m</td></tr>"
}}
</tbody></table>

<script>
const pts=$scatterPoints;
const c=document.getElementById('map'),ctx=c.getContext('2d');
if(pts.length){
  const xs=pts.map(p=>p.x),ys=pts.map(p=>p.y);
  const minX=Math.min(...xs),maxX=Math.max(...xs),minY=Math.min(...ys),maxY=Math.max(...ys);
  const W=c.width-60,H=c.height-60,pad=30;
  const sx=v=>pad+(v-minX)/(Math.max(maxX-minX,1))*W;
  const sy=v=>pad+(v-minY)/(Math.max(maxY-minY,1))*H;
  ctx.fillStyle='#0f172a';ctx.fillRect(0,0,c.width,c.height);
  // Grid lines
  ctx.strokeStyle='#1e293b';ctx.lineWidth=1;
  for(let i=0;i<=4;i++){
    ctx.beginPath();ctx.moveTo(pad+i*W/4,pad);ctx.lineTo(pad+i*W/4,pad+H);ctx.stroke();
    ctx.beginPath();ctx.moveTo(pad,pad+i*H/4);ctx.lineTo(pad+W,pad+i*H/4);ctx.stroke();
  }
  pts.forEach(p=>{
    const r=Math.max(3,Math.min(12,(p.rssi+100)/7));
    ctx.beginPath();ctx.arc(sx(p.x),sy(p.y),r,0,Math.PI*2);
    ctx.fillStyle=p.color+'cc';ctx.fill();
    ctx.strokeStyle='white';ctx.lineWidth=0.5;ctx.stroke();
  });
  // Axes labels
  ctx.fillStyle='#94a3b8';ctx.font='11px system-ui';ctx.textAlign='center';
  ctx.fillText('← West / East →',c.width/2,c.height-5);
}
</script>
</body></html>""") }
        file
    }

    private fun exportDir(context: Context): File =
        (context.getExternalFilesDir("exports") ?: context.filesDir)
            .also { it.mkdirs() }

    private fun String.csv()  = replace("\"", "\"\"")
    private fun String.json() = replace("\"", "\\\"").replace("\n", "\\n")
    private fun String.xml()  = replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
}
