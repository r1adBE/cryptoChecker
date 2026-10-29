package com.cryptochecker.app.data.remote

import com.cryptochecker.app.data.testTokenIdentityStore
import com.cryptochecker.app.domain.exceptions.ExchangeError
import com.cryptochecker.app.domain.exceptions.NetworkError
import com.cryptochecker.app.domain.market.DataFreshness
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
import java.time.ZoneOffset

/**
 * Markt-Tab über Bitcoin hinaus ([InsightsDataSource]) mit einem Interceptor als Anbieter: Coin-Kennzahlen aus der
 * Kerzen-Kette (sonst Bybit), Fear & Greed, CoinGecko `/global`, selbst gerechnete Altcoin-Saison und der
 * Zyklus-Vergleich (Coin Metrics, sonst Binance, zuletzt die Kette). Der Daten-Spiegel ist hier immer offline.
 */
class InsightsDataSourceTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun chainOpen() = RouteHttp.releaseChain()

    private fun insights(http: RouteHttp) =
        InsightsDataSource(http.client, CandleDataSource(http.client, testTokenIdentityStore(temp.newFolder())))

    private fun mirror(url: String) = "binance.vision/api/v3/klines?symbol=$url"

    @Test
    fun coinFiguresFromTheChain() = runBlocking {
        val http = RouteHttp { url ->
            when {
                mirror("ETHUSDT&interval=1d") in url -> Reply(Klines.binance(400) { 100.0 + it })
                mirror("ETHUSDT&interval=1w") in url -> Reply(Klines.binance(210, step = Klines.WEEK) { 50.0 })
                mirror("BTCUSDT&interval=1d&limit=120") in url -> Reply(Klines.binance(120) { 1000.0 })
                else -> null
            }
        }
        val sourced = insights(http).fetchCoin("eth")
        val coin = sourced.value
        assertEquals(DataFreshness.BINANCE, sourced.provider)
        assertEquals(499.0, coin.price, 0.0)
        assertEquals((450..499).average(), coin.sma50d!!, 1e-9)
        assertEquals((300..499).average(), coin.sma200d!!, 1e-9)
        assertEquals(50.0, coin.sma200w!!, 1e-9)
        assertEquals(469.0, coin.price30dAgo!!, 0.0)
        assertNotNull(coin.sma350d)
        // Allzeithoch = höchstes Tageshoch (heute), nicht die tieferen Wochenkerzen
        assertEquals(499.0 * 1.01, coin.ath!!, 1e-9)
        assertEquals(LocalDate.now(ZoneOffset.UTC), coin.athDate)
        // Stärke gegenüber BTC (konstant): eigene Veränderung über 90 Tage
        assertEquals((499.0 / 409.0 - 1.0) * 100.0, coin.vsBtc90d!!, 1e-9)
        assertTrue(coin.historyDays >= 209 * 7)
    }

    @Test
    fun coinFallsBackToBybit() = runBlocking {
        val http = RouteHttp { url ->
            when {
                "api.bybit.com/v5/market/kline?category=spot&symbol=XYZUSDT&interval=D" in url ->
                    Reply(Klines.bybit(40) { 2.0 + it })
                else -> null
            }
        }
        val sourced = insights(http).fetchCoin("XYZ")
        assertEquals(DataFreshness.BYBIT, sourced.provider)
        // Bybit liefert neueste zuerst: Kurs = jüngste Kerze
        assertEquals(41.0, sourced.value.price, 0.0)
        assertEquals(11.0, sourced.value.price30dAgo!!, 0.0)
        assertNull("ohne Wochenkerzen", sourced.value.sma200w)
        assertNull("ohne BTC-Verlauf", sourced.value.vsBtc90d)
        assertEquals(40, sourced.value.historyDays)
    }

    @Test
    fun coinWithoutAnyCandlesFails() = runBlocking {
        // Kette und Bybit ohne Antwort: der Fehler von Bybit kommt durch
        try {
            insights(RouteHttp { null }).fetchCoin("XYZ")
            fail("Fehler erwartet")
        } catch (e: ExchangeError) {
            assertTrue(e.toString(), e is NetworkError)
        }
    }

    @Test
    fun fearAndGreedWithHistory() = runBlocking {
        val values = (0 until 31).joinToString(",") { i -> """{"value":"${80 - i}","timestamp":"${1_760_000_000 - i * 86_400}"}""" }
        val http = RouteHttp { url -> if (url.startsWith("https://api.alternative.me/fng/")) Reply("""{"data":[$values]}""") else null }
        val fg = insights(http).fearGreed()
        assertEquals(80, fg.value)
        assertEquals(79, fg.yesterday)
        assertEquals(73, fg.weekAgo)
        assertEquals(50, fg.monthAgo)
        assertEquals(1_760_000_000_000L, fg.time)
        assertTrue("Spiegel zuerst gefragt", http.requested.first().startsWith("https://github.com/"))

        // Nur ein Tag, ohne Zeitstempel: Rest unbekannt
        val short = RouteHttp { url -> if ("alternative.me" in url) Reply("""{"data":[{"value":"40"}]}""") else null }
        val one = insights(short).fearGreed()
        assertEquals(40, one.value)
        assertNull(one.yesterday)
        assertNull(one.monthAgo)
        assertNull(one.time)

        // Ohne Wert: Fehler
        val empty = RouteHttp { url -> if ("alternative.me" in url) Reply("""{"data":[]}""") else null }
        try {
            insights(empty).fearGreed()
            fail("Fehler erwartet")
        } catch (e: IllegalStateException) {
            assertEquals("Fear & Greed ohne Wert", e.message)
        }
    }

    @Test
    fun globalMarketWithAndWithoutTotals() = runBlocking {
        val full = """{"data":{"market_cap_percentage":{"btc":57.5,"eth":11.25},""" +
            """"total_market_cap":{"usd":3.4e12,"CHF":3.0e12},"total_volume":{"usd":1.2e11},""" +
            """"market_cap_change_percentage_24h_usd":-1.5}}"""
        val global = insights(RouteHttp { url -> if (url.endsWith("/api/v3/global")) Reply(full) else null }).global()
        assertEquals(57.5, global.dominance.btc, 0.0)
        assertEquals(11.25, global.dominance.eth!!, 0.0)
        val totals = global.totals!!
        assertEquals(mapOf("usd" to 3.4e12, "chf" to 3.0e12), totals.marketCap)
        assertEquals(mapOf("usd" to 1.2e11), totals.volume)
        assertEquals(-1.5, totals.changePercent24h!!, 0.0)

        // Nur die Dominanz (ohne ETH): Summen fehlen, Dominanz bleibt
        val bare = """{"data":{"market_cap_percentage":{"btc":60.0}}}"""
        val dominanceOnly = insights(RouteHttp { url -> if (url.endsWith("/global")) Reply(bare) else null }).global()
        assertEquals(60.0, dominanceOnly.dominance.btc, 0.0)
        assertNull(dominanceOnly.dominance.eth)
        assertNull(dominanceOnly.totals)

        // Leere Summen zählen als fehlend
        val emptyTotals = """{"data":{"market_cap_percentage":{"btc":60.0},"total_market_cap":{},"total_volume":{}}}"""
        assertNull(insights(RouteHttp { url -> if (url.endsWith("/global")) Reply(emptyTotals) else null }).global().totals)
    }

    @Test
    fun altSeasonComputedWhenTheMirrorIsMissing() = runBlocking {
        // BTC +10 % in 90 Tagen; ETH +50 % (schlägt BTC), SOL −10 %; alle übrigen Altcoins ohne Kerzen
        val http = RouteHttp { url ->
            when {
                mirror("BTCUSDT&interval=1d&limit=91") in url -> Reply(Klines.binance(91) { if (it == 90) 110.0 else 100.0 })
                mirror("ETHUSDT&interval=1d&limit=91") in url -> Reply(Klines.binance(91) { if (it == 90) 150.0 else 100.0 })
                mirror("SOLUSDT&interval=1d&limit=91") in url -> Reply(Klines.binance(91) { if (it == 90) 90.0 else 100.0 })
                // Zu kurze Reihe: zählt nicht
                mirror("XRPUSDT&interval=1d&limit=91") in url -> Reply(Klines.binance(30) { 1.0 })
                else -> null
            }
        }
        val sourced = insights(http).altSeason()
        assertEquals(1, sourced.value.outperformers)
        assertEquals(2, sourced.value.total)
        assertEquals(DataFreshness.BINANCE, sourced.provider)
        assertTrue(http.requested.first().startsWith("https://github.com/"))
    }

    @Test
    fun cycleHistoryFromCoinMetrics() = runBlocking {
        val halving = LocalDate.of(2020, 5, 11)
        val start = halving.minusDays(10)
        // Kurs verdoppelt sich in 100 Tagen nach dem Halving, danach konstant
        val rows = (0 until 240).joinToString(",") { d ->
            val day = start.plusDays(d.toLong())
            val price = 8000.0 * (1.0 + (d - 10).coerceIn(0, 100) / 100.0)
            """{"asset":"btc","time":"${day}T00:00:00.000000000Z","PriceUSD":"$price"}"""
        }
        val http = RouteHttp { url ->
            if (url.startsWith("https://community-api.coinmetrics.io/") && "metrics=PriceUSD" in url) {
                Reply("""{"data":[$rows,{"time":"2020-12-31T00:00:00Z","PriceUSD":""}]}""")
            } else {
                null
            }
        }
        val series = insights(http).cycleHistory().series
        // Nur der Zyklus 2020 hat Daten ab dem Halving-Tag (2016 und 2024 fehlen)
        assertEquals(listOf(halving), series.map { it.halving })
        val points = series.single().points
        assertEquals(0 to 1.0, points.first())
        assertTrue(points.any { it.first == 140 && it.second == 2.0 })
        assertEquals(0, http.count("api/v3/klines"))
    }

    @Test
    fun cycleHistoryFallsBackToBinanceThenToTheChain() = runBlocking {
        val halving = LocalDate.of(2020, 5, 11)
        val first = halving.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val binance = RouteHttp { url ->
            if ("data-api.binance.vision/api/v3/klines?symbol=BTCUSDT&interval=1d&limit=1000&startTime=" in url) {
                // Weniger als 1000 Kerzen: letzter Block
                Reply(Klines.binance(50, last = first + 49 * Klines.DAY) { 9000.0 + it })
            } else {
                null
            }
        }
        val fromBinance = insights(binance).cycleHistory().series
        assertEquals(listOf(halving), fromBinance.map { it.halving })
        assertEquals(1, binance.count("startTime="))

        // Coin Metrics und Binance-Verlauf fehlen: Kette mit kurzem, jungem Verlauf → kein Zyklus beginnt am Halving
        val chain = RouteHttp { url ->
            if ("startTime=" !in url && mirror("BTCUSDT&interval=1d&limit=1000") in url) Reply(Klines.binance(300) { 60_000.0 }) else null
        }
        assertTrue(insights(chain).cycleHistory().series.isEmpty())
        assertTrue(chain.count("startTime=") >= 1)
    }
}
