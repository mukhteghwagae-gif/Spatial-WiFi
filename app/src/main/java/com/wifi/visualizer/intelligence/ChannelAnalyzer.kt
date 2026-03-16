package com.wifi.visualizer.intelligence

import com.wifi.visualizer.utils.NearbyNetwork

/**
 * Analyzes Wi-Fi channel utilization and interference across both
 * 2.4 GHz and 5 GHz bands.
 *
 * Key capabilities:
 *   • Groups networks by channel
 *   • Computes interference score per channel (weighted by RSSI)
 *   • Identifies the least-congested channels
 *   • Detects overlapping 2.4 GHz channels (channels 1, 6, 11 don't overlap)
 *   • Classifies 5 GHz channels into U-NII bands
 */
class ChannelAnalyzer {

    // ── Public API ────────────────────────────────────────────────────────────

    fun analyze(networks: List<NearbyNetwork>): ChannelReport {
        val band24 = networks.filter { it.frequency in 2400..2500 }
        val band5  = networks.filter { it.frequency in 5100..5900 }
        val band6  = networks.filter { it.frequency in 5925..7125 }

        val channels24 = buildChannelStats(band24) { freqToChannel24(it.frequency) }
        val channels5  = buildChannelStats(band5)  { freqToChannel5(it.frequency) }

        val best24 = findBestChannels24(channels24)
        val best5  = findBestChannels5(channels5)

        val overallInterference = computeOverallInterference(networks)

        return ChannelReport(
            networks24GHz        = band24,
            networks5GHz         = band5,
            networks6GHz         = band6,
            channelStats24GHz    = channels24,
            channelStats5GHz     = channels5,
            recommendedChannels24 = best24,
            recommendedChannels5  = best5,
            totalNetworksVisible  = networks.size,
            overallCongestionScore = overallInterference
        )
    }

    // ── Channel mapping ───────────────────────────────────────────────────────

    fun freqToChannel24(freqMHz: Int): Int {
        if (freqMHz == 2484) return 14
        if (freqMHz < 2412) return -1
        return (freqMHz - 2412) / 5 + 1
    }

    fun freqToChannel5(freqMHz: Int): Int {
        return (freqMHz - 5000) / 5
    }

    fun channelToFreq24(channel: Int): Int = 2412 + (channel - 1) * 5

    /** Non-overlapping 2.4 GHz channels (varies by region; US/EU = 1,6,11). */
    val nonOverlapping24 = setOf(1, 6, 11)

    /** Returns channels that overlap with [channel] in 2.4 GHz (±2 channels). */
    fun overlappingChannels24(channel: Int): Set<Int> =
        ((channel - 2)..(channel + 2)).filter { it in 1..13 }.toSet()

    // ── Channel stats ─────────────────────────────────────────────────────────

    private fun buildChannelStats(
        networks: List<NearbyNetwork>,
        toChannel: (NearbyNetwork) -> Int
    ): Map<Int, ChannelStat> {
        val grouped = networks.groupBy { toChannel(it) }.filter { it.key > 0 }
        return grouped.map { (ch, nets) ->
            // Interference score = sum of linear RSSI powers (mW)
            val interferenceMw = nets.sumOf { rssiToMw(it.rssi) }
            // Normalise to a 0-100 congestion index
            val congestionScore = (interferenceMw * 100 / 10.0).coerceIn(0.0, 100.0).toInt()
            ch to ChannelStat(
                channel          = ch,
                networkCount     = nets.size,
                strongestRssi    = nets.maxOf { it.rssi },
                averageRssi      = nets.map { it.rssi }.average().toInt(),
                congestionScore  = congestionScore,
                networks         = nets
            )
        }.toMap()
    }

    private fun findBestChannels24(stats: Map<Int, ChannelStat>): List<Int> {
        // Prefer non-overlapping channels with lowest total interference
        return (1..13).sortedBy { channel ->
            val direct   = stats[channel]?.congestionScore ?: 0
            val adjacent = overlappingChannels24(channel)
                .filter { it != channel }
                .sumOf { stats[it]?.congestionScore ?: 0 }
            direct + adjacent / 2  // weight adjacent interference less
        }.take(3)
    }

    private fun findBestChannels5(stats: Map<Int, ChannelStat>): List<Int> {
        val preferred5GHz = listOf(36, 40, 44, 48, 149, 153, 157, 161)  // DFS-free
        return preferred5GHz.sortedBy { ch -> stats[ch]?.congestionScore ?: 0 }.take(3)
    }

    private fun computeOverallInterference(networks: List<NearbyNetwork>): Int {
        val totalMw = networks.sumOf { rssiToMw(it.rssi) }
        return (totalMw * 10).coerceIn(0.0, 100.0).toInt()
    }

    private fun rssiToMw(rssi: Int): Double = Math.pow(10.0, rssi / 10.0)
}

// ── Data classes ──────────────────────────────────────────────────────────────

data class ChannelStat(
    val channel         : Int,
    val networkCount    : Int,
    val strongestRssi   : Int,
    val averageRssi     : Int,
    val congestionScore : Int,   // 0 = clear, 100 = very congested
    val networks        : List<NearbyNetwork>
) {
    val congestionLabel: String get() = when {
        congestionScore < 20 -> "Clear"
        congestionScore < 50 -> "Moderate"
        congestionScore < 75 -> "Busy"
        else                 -> "Congested"
    }
}

data class ChannelReport(
    val networks24GHz         : List<NearbyNetwork>,
    val networks5GHz          : List<NearbyNetwork>,
    val networks6GHz          : List<NearbyNetwork>,
    val channelStats24GHz     : Map<Int, ChannelStat>,
    val channelStats5GHz      : Map<Int, ChannelStat>,
    val recommendedChannels24 : List<Int>,
    val recommendedChannels5  : List<Int>,
    val totalNetworksVisible  : Int,
    val overallCongestionScore: Int
) {
    val congestionLabel: String get() = when {
        overallCongestionScore < 25 -> "Low congestion"
        overallCongestionScore < 55 -> "Moderate congestion"
        overallCongestionScore < 80 -> "High congestion"
        else                        -> "Extreme congestion"
    }
}
