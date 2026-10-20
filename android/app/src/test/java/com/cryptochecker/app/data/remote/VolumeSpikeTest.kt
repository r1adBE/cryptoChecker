package com.cryptochecker.app.data.remote

import com.cryptochecker.app.domain.activity.HourCandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Volumen-Spike nach Uhrzeit: Stunden ohne Kerze zählen als 0 ([VolumeDataSource.spikeOf]). */
class VolumeSpikeTest {

    private val hour = 3_600_000L
    private val running = 480_000L // Stunde Nr. (seit 1970) der laufenden Kerze
    private val now = running * hour + 20 * 60_000L // 20 Min. in der laufenden Stunde

    private fun candle(hourIndex: Long, volume: Double) =
        HourCandle(openTime = hourIndex * hour, open = 1.0, high = 1.0, low = 1.0, close = 1.0, volume = volume)

    @Test
    fun fullSeriesComparesLastClosedHourWithTheDayBefore() {
        // 24 Vergleichsstunden je 10, letzte abgeschlossene 40, laufende 5
        val candles = (running - 25 until running - 1).map { candle(it, 10.0) } +
            candle(running - 1, 40.0) + candle(running, 5.0)
        val spike = assertNotNullSpike(VolumeDataSource.spikeOf(candles, now))
        assertEquals(4.0, spike.ratio, 1e-9)
        assertEquals((running - 1) * hour, spike.candleOpenTime)
    }

    @Test
    fun missingHoursCountAsZeroInsteadOfShiftingTheWindow() {
        // Jede zweite Vergleichsstunde fehlt (kein Handel): Schnitt = 12 × 20 / 24 = 10
        val candles = (running - 25 until running - 1).filter { (it - (running - 25)) % 2 == 0L }.map { candle(it, 20.0) } +
            candle(running - 1, 30.0) + candle(running, 1.0)
        val spike = assertNotNullSpike(VolumeDataSource.spikeOf(candles, now))
        assertEquals(3.0, spike.ratio, 1e-9)
        assertEquals((running - 1) * hour, spike.candleOpenTime)
    }

    @Test
    fun missingLastClosedHourMeansZeroVolumeNotAnOlderCandle() {
        // Ohne Kerze der letzten abgeschlossenen Stunde: früher galt die vorletzte VORHANDENE Kerze
        val candles = (running - 26 until running - 1).map { candle(it, 10.0) }.toMutableList()
        candles[candles.lastIndex] = candle(running - 2, 100.0) // grosse, aber ältere Stunde
        val spike = assertNotNullSpike(VolumeDataSource.spikeOf(candles, now))
        assertEquals(0.0, spike.ratio, 1e-9)
        assertEquals((running - 1) * hour, spike.candleOpenTime)
    }

    @Test
    fun seriesNotCoveringTheComparisonWindowGivesNothing() {
        // Nur 10 Stunden Verlauf: die übrigen Vergleichsstunden sind unbekannt, nicht 0
        val candles = (running - 10..running).map { candle(it, 10.0) }
        assertNull(VolumeDataSource.spikeOf(candles, now))
    }

    @Test
    fun staleSeriesGivesNothing() {
        // Reihe endete vor zwei Tagen (Paar dort nicht mehr gehandelt)
        val end = running - 48
        val candles = (end - 30..end).map { candle(it, 10.0) }
        assertNull(VolumeDataSource.spikeOf(candles, now))
    }

    private fun assertNotNullSpike(spike: VolumeSpike?): VolumeSpike {
        assertNotNull(spike)
        return spike!!
    }

    @Test
    fun foreignCandlesNameTheirExchange() {
        val kraken = com.cryptochecker.app.data.local.model.WatchEntity(
            marketKey = "Kraken", marketName = "Kraken", baseAsset = "BTC", quoteAsset = "USD",
        )
        val spike = VolumeSpike(ratio = 3.0, candleOpenTime = 0L)
        with(VolumeDataSource) {
            // Kraken-Paar mit Binance-Stundenkerzen: Börse für den Alarmtext
            assertEquals("Binance", spike.withSource(kraken, "Binance").source)
            // Eigene Börse bzw. unbekannter Anbieter: nichts nennen
            assertNull(spike.withSource(kraken, "Kraken").source)
            assertNull(spike.withSource(kraken, null).source)
        }
    }
}
