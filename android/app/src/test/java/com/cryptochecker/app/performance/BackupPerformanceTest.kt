package com.cryptochecker.app.performance

import com.cryptochecker.app.data.BackupFormat
import com.cryptochecker.app.data.BackupLimits
import com.cryptochecker.app.data.BackupWatchlistCodec
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.WatchlistLimits
import com.cryptochecker.app.performance.PerformanceTiming.assertScales
import com.cryptochecker.app.performance.PerformanceTiming.medianMillis
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Merkliste und Paar-Alarme in der Sicherung mit 2000 Paaren: Grösse des Klartexts, Zeit zum Schreiben
 * ([BackupWatchlistCodec] + [BackupFormat.encode]) und zum Lesen samt Bereinigung und Grenzen
 * ([BackupWatchlistCodec.readWatchlist]). Einstellungen, Portfolio und Adressen gehören nicht dazu. iOS:
 * BackupPerformanceTests.swift.
 */
class BackupPerformanceTest {

    /** Merkliste-Teil der Sicherung, aufgebaut wie in `BackupManager.export`. */
    private fun root(watches: List<WatchEntity>, alarms: List<AlarmEntity>): JSONObject = JSONObject()
        .put("watches", JSONArray().apply { watches.forEach { put(BackupWatchlistCodec.watchToJson(it)) } })
        .put("alarms", JSONArray().apply { alarms.forEach { put(BackupWatchlistCodec.alarmToJson(it)) } })

    private fun encoded(n: Int, perPair: Int? = null): ByteArray {
        val watches = PerformanceData.watches(n)
        return BackupFormat.encode(root(watches, PerformanceData.alarms(watches, perPair = perPair)))
    }

    @Test
    fun sizeStaysSmall() {
        val n = PerformanceTiming.LARGE
        // 2000 Paare mit 1–3 Alarmen: gerechnet rund 1,4 MB
        val typical = encoded(n).size
        PerformanceTiming.reportSize("Sicherung Merkliste (1–3 Alarme je Paar)", n, typical, TWO_MB)
        assertTrue("Sicherung mit $n Paaren: $typical Bytes", typical < TWO_MB)
        // Volle Grenzen (2000 Paare mit je zehn Alarmen): bleibt weit unter dem, was sich einlesen lässt
        val full = encoded(n, perPair = WatchlistLimits.MAX_ALARMS_PER_PAIR).size
        PerformanceTiming.reportSize("Sicherung Merkliste (10 Alarme je Paar)", n, full, BackupLimits.MAX_BYTES / 8)
        assertTrue("Sicherung mit $n Paaren und je zehn Alarmen: $full Bytes", full < BackupLimits.MAX_BYTES / 8)
    }

    @Test
    fun writeWatchlist() {
        // JSON aufbauen und kompakt schreiben: erwartet einige 10 ms
        assertScales("Sicherung schreiben (Merkliste)", limitMillis = 1_000.0) { n ->
            val watches = PerformanceData.watches(n)
            val alarms = PerformanceData.alarms(watches)
            medianMillis { BackupFormat.encode(root(watches, alarms)) }
        }
    }

    @Test
    fun readWatchlist() {
        // Lesen, Kennungen prüfen, Doppel bereinigen, Grenzen anwenden: erwartet einige 10 ms
        assertScales("Sicherung lesen (Merkliste)", limitMillis = 1_500.0) { n ->
            val text = encoded(n).toString(Charsets.UTF_8)
            medianMillis { BackupWatchlistCodec.readWatchlist(JSONObject(text)) }
        }
        val watches = PerformanceData.watches(PerformanceTiming.LARGE)
        val alarms = PerformanceData.alarms(watches)
        val read = BackupWatchlistCodec.readWatchlist(
            JSONObject(BackupFormat.encode(root(watches, alarms)).toString(Charsets.UTF_8)),
        )
        assertEquals(watches.size, read.watches.size)
        assertEquals(alarms.size, read.alarms.size)
        assertEquals(0, read.invalidAlarms + read.droppedPairs + read.droppedAlarms)
    }

    private companion object {
        const val TWO_MB = 2 * 1024 * 1024
    }
}
