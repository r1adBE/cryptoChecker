package com.cryptochecker.app.data.remote

import com.cryptochecker.app.data.testTokenIdentityStore
import com.cryptochecker.app.domain.market.DataFreshness
import com.cryptochecker.app.domain.market.OnChainValues
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

/**
 * Daten des Zyklus-Modells ([CycleDataSource]): BTC-Tages- und Wochenkerzen aus der Kette, On-Chain-Werte von
 * Coin Metrics (MVRV, Puell, Hashrate) und der Verlauf seit 2016 für die Einordnung; bekannte On-Chain-Werte
 * ersparen den Abruf, zu wenige Tageskerzen sind ein Fehler.
 */
class CycleDataSourceTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun chainOpen() = RouteHttp.releaseChain()

    private fun cycle(http: RouteHttp) =
        CycleDataSource(http.client, CandleDataSource(http.client, testTokenIdentityStore(temp.newFolder())))

    private val daily = "binance.vision/api/v3/klines?symbol=BTCUSDT&interval=1d"
    private val weekly = "binance.vision/api/v3/klines?symbol=BTCUSDT&interval=1w"

    /** 400 Tage On-Chain-Werte: MVRV steigt bis 2.5, Ausgabe konstant 1 Mio. ausser heute 2 Mio., Hashrate 600. */
    private val onChain: String = run {
        val start = LocalDate.of(2025, 1, 1)
        val rows = (0 until 400).joinToString(",") { d ->
            val issuance = if (d == 399) 2_000_000.0 else 1_000_000.0
            """{"time":"${start.plusDays(d.toLong())}T00:00:00Z","CapMVRVCur":"${1.0 + d * 1.5 / 399}",""" +
                """"IssTotUSD":"$issuance","HashRate":"600"}"""
        }
        """{"data":[$rows]}"""
    }

    /** 500 Tageskurse seit 2016 (genug für die Einordnung). */
    private val history: String = run {
        val start = LocalDate.of(2016, 6, 1)
        val rows = (0 until 500).joinToString(",") { d ->
            """{"time":"${start.plusDays(d.toLong())}T00:00:00Z","PriceUSD":"${500.0 + d}"}"""
        }
        """{"data":[$rows,{"time":"2018-01-01T00:00:00Z"}]}"""
    }

    private fun isOnChain(url: String) = "community-api.coinmetrics.io" in url && "CapMVRVCur" in url
    private fun isHistory(url: String) = "community-api.coinmetrics.io" in url && "metrics=PriceUSD" in url

    @Test
    fun candlesOnChainAndHistoryTogether() = runBlocking {
        val http = RouteHttp { url ->
            when {
                daily in url -> Reply(Klines.binance(400) { 20_000.0 + it * 100 })
                weekly in url -> Reply(Klines.binance(220, step = Klines.WEEK) { 30_000.0 })
                isOnChain(url) -> Reply(onChain)
                isHistory(url) -> Reply(history)
                else -> null
            }
        }
        val sourced = cycle(http).fetchSourced()
        val inputs = sourced.value
        assertEquals("Binance, Coin Metrics", sourced.provider)
        assertEquals(59_900.0, inputs.price, 0.0)
        assertEquals((200 until 400).map { 20_000.0 + it * 100 }.average(), inputs.sma200d!!, 1e-6)
        assertEquals(30_000.0, inputs.sma200w!!, 1e-9)
        assertEquals(20_000.0 + 369 * 100, inputs.price30dAgo!!, 0.0)
        assertNotNull(inputs.sma350d)
        assertEquals(59_900.0 * 1.01, inputs.ath!!, 1e-6)
        assertEquals(2.5, inputs.mvrv!!, 1e-9)
        // Puell: heutige Ausgabe / Schnitt der letzten 365 Tage
        assertEquals(2_000_000.0 / ((364 * 1_000_000.0 + 2_000_000.0) / 365), inputs.puell!!, 1e-9)
        assertEquals(600.0, inputs.hash30d!!, 0.0)
        assertEquals(600.0, inputs.hash60d!!, 0.0)
        assertNotNull("Einordnung mit 500 Tageskursen", inputs.history)
        // Der Spiegel wurde je Coin-Metrics-Adresse zuerst gefragt
        assertEquals(2, http.count("github.com/"))
    }

    @Test
    fun knownOnChainValuesSkipTheRequest() = runBlocking {
        val known = OnChainValues(mvrv = 1.8, puell = 0.9, hash30d = 500.0, hash60d = 480.0)
        val http = RouteHttp { url -> if (daily in url) Reply(Klines.binance(250) { 40_000.0 }) else null }
        val sourced = cycle(http).fetchSourced(knownOnChain = known)
        assertEquals(0, http.count("CapMVRVCur"))
        val inputs = sourced.value
        assertEquals(1.8, inputs.mvrv!!, 0.0)
        assertEquals(0.9, inputs.puell!!, 0.0)
        // Ohne Wochenkerzen kein 200-Wochen-Schnitt; ohne Verlauf keine Einordnung
        assertNull(inputs.sma200w)
        assertNull(inputs.history)
        assertNull("250 Tage reichen nicht für 350", inputs.sma350d)
        assertEquals("Binance, Coin Metrics", sourced.provider)
    }

    @Test
    fun withoutOnChainOnlyTheCandleProviderIsNamed() = runBlocking {
        val http = RouteHttp { url ->
            when {
                daily in url -> Reply(Klines.binance(200) { 40_000.0 })
                // Unlesbare On-Chain-Antwort: Modell rechnet ohne sie
                isOnChain(url) -> Reply("""{"data":"kaputt"}""")
                else -> null
            }
        }
        val sourced = cycle(http).fetchSourced()
        assertEquals(DataFreshness.BINANCE, sourced.provider)
        assertNull(sourced.value.mvrv)
        assertNull(sourced.value.puell)
        assertEquals(40_000.0, cycle(http).fetch().price, 0.0)
    }

    @Test
    fun shortIssuanceHistoryGivesNoPuell() = runBlocking {
        val rows = (0 until 100).joinToString(",") { """{"time":"2026-01-01T00:00:00Z","CapMVRVCur":"2.0","IssTotUSD":"5"}""" }
        val http = RouteHttp { url ->
            when {
                daily in url -> Reply(Klines.binance(210) { 40_000.0 })
                isOnChain(url) -> Reply("""{"data":[$rows]}""")
                else -> null
            }
        }
        val inputs = cycle(http).fetch()
        assertEquals(2.0, inputs.mvrv!!, 0.0)
        assertNull(inputs.puell)
        assertNull("keine Hashrate", inputs.hash30d)
    }

    @Test
    fun tooFewOrNoDailyCandlesFail() = runBlocking {
        val short = RouteHttp { url -> if (daily in url) Reply(Klines.binance(150) { 40_000.0 }) else null }
        try {
            cycle(short).fetch()
            fail("Fehler erwartet")
        } catch (e: IllegalArgumentException) {
            assertEquals("Zu wenige Tageskerzen: 150", e.message)
        }
        try {
            cycle(RouteHttp { null }).fetch()
            fail("Fehler erwartet")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.startsWith("Keine BTC-Tageskerzen"))
        }
    }
}
