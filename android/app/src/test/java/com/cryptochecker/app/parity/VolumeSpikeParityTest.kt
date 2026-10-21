package com.cryptochecker.app.parity

import com.cryptochecker.app.data.remote.VolumeDataSource
import com.cryptochecker.app.domain.activity.HourCandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gemeinsame Testfälle des Volumen-Spikes (`testdata/parity/volume_spike.json`): Stundenraster,
 * laufende Stunde aus Uhr und neuester Kerze, fehlende Stunden = 0, veraltete Reihe → nichts.
 * Gegenstück: `Tests/VolumeSpikeParityTests.swift`.
 */
class VolumeSpikeParityTest {

    private val data = ParityJson.load("volume_spike.json")
    private val hour = 3_600_000L

    @Test
    fun spikeOf() {
        val running = data["runningHour"].num()!!.toLong()
        val cases = data["cases"].list()
        assertTrue(cases.size >= 10)
        for (raw in cases) {
            val c = raw.obj()
            val name = c["name"] as String
            val now = running * hour + c["nowMinute"].num()!!.toLong() * 60_000L
            val candles = c["candles"].list().map { spec ->
                val k = spec.list()
                val minute = k.getOrNull(2).num()?.toLong() ?: 0L
                val openTime = (running + k[0].num()!!.toLong()) * hour + minute * 60_000L
                HourCandle(openTime = openTime, open = 1.0, high = 1.0, low = 1.0, close = 1.0, volume = k[1].num()!!)
            }
            val spike = VolumeDataSource.spikeOf(candles, now)
            val expected = c["expected"]
            if (expected == null) {
                assertNull(name, spike)
                continue
            }
            val e = expected.obj()
            assertNotNull(name, spike)
            assertEquals(name, e["ratio"].num()!!, spike!!.ratio, 1e-9)
            assertEquals(name, (running + e["hour"].num()!!.toLong()) * hour, spike.candleOpenTime)
        }
    }
}
