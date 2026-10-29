package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.TokenIdentityStore
import com.cryptochecker.app.data.fakePortfolioDao
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.remote.Klines
import com.cryptochecker.app.data.remote.Reply
import com.cryptochecker.app.data.remote.RouteHttp
import com.cryptochecker.app.data.remote.candles.OwnCandleDataSource
import com.cryptochecker.app.data.testTokenIdentityStore
import com.cryptochecker.app.data.unusedExchangeRepository
import com.cryptochecker.app.domain.market.DataFreshness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Tagesschlusskurse für den Stichtag-Export ([HistoricPriceSource.dailyCloseUsdt]) über einen Interceptor: USDT = 1,
 * ohne Bezug «ungeprüft», Binance-Spot mit Identitätsprüfung (1/3…3), Futures und Binance.US mit ±25 %, Coinbase mit
 * Tagesfenster, Namenssperre; dazu mehrere Coins auf einmal. Devisenkurse siehe [HistoricFxTest].
 */
class HistoricPriceSourceTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun chainOpen() = RouteHttp.releaseChain()

    private val date: LocalDate = LocalDate.of(2024, 1, 2)
    private val start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun source(http: RouteHttp, names: TokenIdentityStore = testTokenIdentityStore(temp.newFolder())) =
        HistoricPriceSource(http.client, names, OwnCandleDataSource(http.client), fakePortfolioDao(), unusedExchangeRepository())

    /** Binance-Antwort: Tageskerze des Stichtags (Abfrage mit startTime) bzw. jüngste Kerze (ohne). */
    private fun binance(url: String, close: Double, latest: Double): Reply =
        if ("startTime=$start" in url) Reply(Klines.binance(1, last = start) { close }) else Reply(Klines.binance(1) { latest })

    private val notFound = Reply("""{"code":-1121,"msg":"Invalid symbol."}""", 404)

    @Test
    fun stablecoinOddCoinsAndMissingReference() = runBlocking {
        val http = RouteHttp { null }
        val historic = source(http)
        assertEquals(HistoricPriceSource.CloseResult(1.0), historic.dailyCloseUsdt(" usdt ", date, null))
        assertEquals(HistoricPriceSource.CloseResult(null), historic.dailyCloseUsdt("BAD COIN", date, 1.0))
        // Ohne gültigen aktuellen Kurs: keine Abfrage, «ungeprüft»
        assertEquals(HistoricPriceSource.CloseResult(null, unchecked = true), historic.dailyCloseUsdt("ETH", date, null))
        assertEquals(HistoricPriceSource.CloseResult(null, unchecked = true), historic.dailyCloseUsdt("ETH", date, Double.NaN))
        assertTrue(http.requested.isEmpty())
    }

    @Test
    fun binanceSpotCloseWithIdentityCheck() = runBlocking {
        val http = RouteHttp { url ->
            if (url.startsWith("https://data-api.binance.vision/api/v3/klines?symbol=ETHUSDT&interval=1d")) {
                binance(url, close = 2000.0, latest = 3000.0)
            } else {
                null
            }
        }
        val result = source(http).dailyCloseUsdt("eth", date, current = 3100.0)
        assertEquals(HistoricPriceSource.CloseResult(2000.0, unchecked = false, source = DataFreshness.BINANCE), result)
        assertEquals(2, http.requested.size)
    }

    @Test
    fun foreignTokenEverywhereIsUnchecked() = runBlocking {
        // Jede Binance-Quelle führt unter «ETH» heute einen Kurs von 0.6 — nicht derselbe Token wie im Portfolio (3000)
        val http = RouteHttp { url -> if ("binance" in url && "klines" in url) binance(url, close = 0.5, latest = 0.6) else null }
        val result = source(http).dailyCloseUsdt("ETH", date, current = 3000.0)
        assertEquals(HistoricPriceSource.CloseResult(null, unchecked = true), result)
        assertEquals(2, http.count("api.binance.us/api/v3/klines?symbol=ETHUSDT&interval=1d"))
        assertEquals(2, http.count("api.binance.us/api/v3/klines?symbol=ETHUSD&interval=1d"))
        // Coinbase, Kraken, OKX und Bybit offline: nur gefragt, ohne Kurs
        assertTrue(http.count("api.exchange.coinbase.com") >= 1)
    }

    @Test
    fun futuresNeedTheQuarterBand() = runBlocking {
        fun http() = RouteHttp { url ->
            when {
                url.startsWith("https://data-api.binance.vision/") || url.startsWith("https://api.binance.com/") -> notFound
                url.startsWith("https://fapi.binance.com/fapi/v1/klines?symbol=ETHUSDT") -> binance(url, close = 2000.0, latest = 3000.0)
                else -> null
            }
        }
        // Jüngster Futures-Schluss 3000 gegen den Portfolio-Kurs 3500: innerhalb von ±25 %
        assertEquals(
            HistoricPriceSource.CloseResult(2000.0, source = DataFreshness.BINANCE_FUTURES),
            source(http()).dailyCloseUsdt("ETH", date, current = 3500.0),
        )
        // 5000: noch derselbe Token (1/3…3), aber ausserhalb von ±25 % → nicht übernommen
        assertEquals(HistoricPriceSource.CloseResult(null, unchecked = true), source(http()).dailyCloseUsdt("ETH", date, current = 5000.0))
    }

    @Test
    fun binanceUsWithUsdSymbol() = runBlocking {
        val http = RouteHttp { url ->
            when {
                url.startsWith("https://api.binance.us/api/v3/klines?symbol=SOLUSDT&") -> notFound
                url.startsWith("https://api.binance.us/api/v3/klines?symbol=SOLUSD&") -> binance(url, close = 100.0, latest = 150.0)
                else -> null
            }
        }
        assertEquals(
            HistoricPriceSource.CloseResult(100.0, source = DataFreshness.BINANCE_US),
            source(http).dailyCloseUsdt("SOL", date, current = 160.0),
        )
    }

    @Test
    fun coinbaseWindowOverTheDay() = runBlocking {
        val today = Klines.today()
        val http = RouteHttp { url ->
            when {
                !url.startsWith("https://api.exchange.coinbase.com/products/ETH-USD/candles?granularity=86400") -> null
                "&start=2024-01-02T00:00:00Z" in url && "&end=2024-01-02T23:59:59Z" in url ->
                    Reply(Klines.coinbase(1, Klines.DAY, start) { 2000.0 })
                else -> Reply(Klines.coinbase(3, Klines.DAY, today) { 2900.0 + it * 50 })
            }
        }
        val result = source(http).dailyCloseUsdt("ETH", date, current = 3100.0)
        assertEquals(HistoricPriceSource.CloseResult(2000.0, source = DataFreshness.COINBASE), result)
        // Jüngster Schluss (3000) passt; USDC nicht mehr nötig
        assertEquals(0, http.count("products/ETH-USDC/"))
    }

    @Test
    fun coinbaseForeignTokenIsRejected() = runBlocking {
        val today = Klines.today()
        val http = RouteHttp { url ->
            when {
                !url.startsWith("https://api.exchange.coinbase.com/products/") -> null
                "&start=" in url -> Reply(Klines.coinbase(1, Klines.DAY, start) { 2.0 })
                else -> Reply(Klines.coinbase(1, Klines.DAY, today) { 3.0 })
            }
        }
        val result = source(http).dailyCloseUsdt("ETH", date, current = 3000.0)
        assertEquals(HistoricPriceSource.CloseResult(null, unchecked = true), result)
        // USD und USDC geprüft, beide abgelehnt
        assertEquals(2, http.count("products/ETH-USDC/"))
    }

    @Test
    fun conflictingNameSkipsBinanceAndTheChain() = runBlocking {
        val root = temp.newFolder()
        val names = testTokenIdentityStore(root) {
            listOf(WatchEntity(exchangeKey = "Mexc", exchangeName = "MEXC", baseAsset = "CAT", quoteAsset = "USDT"))
        }
        names.saveExchange("Mexc", mapOf("CAT" to "Simon's Cat"))
        names.saveBinance(mapOf("CAT" to "Catcoin"))
        // Binance.US offline: diese Quelle prüft den Namen nicht (nur das Kursband)
        val http = RouteHttp { url -> if ("klines" in url && "binance.us" !in url) binance(url, close = 1.0, latest = 1.0) else null }
        val result = source(http, names).dailyCloseUsdt("CAT", date, current = 1.0)
        assertNull(result.price)
        assertTrue(result.unchecked)
        // Binance (Spiegel, Spot, Futures) und Kraken/OKX/Bybit gar nicht gefragt
        assertEquals(0, http.count("data-api.binance.vision"))
        assertEquals(0, http.count("fapi.binance.com"))
        assertEquals(0, http.count("kraken.com"))
        assertEquals(0, http.count("okx.com"))
        assertEquals(0, http.count("bybit.com"))
    }

    @Test
    fun severalCoinsAtOnce() = runBlocking {
        val http = RouteHttp { url ->
            if (url.startsWith("https://data-api.binance.vision/api/v3/klines?symbol=ETHUSDT&")) binance(url, 2000.0, 3000.0) else null
        }
        val closes = source(http).dailyClosesUsdt(listOf("usdt", "eth", "ETH", "bad coin", "SOL"), date, mapOf("ETH" to 3100.0))
        assertEquals(mapOf("USDT" to 1.0, "ETH" to 2000.0), closes.prices.mapValues { it.value.toDouble() })
        assertEquals(setOf("SOL"), closes.unchecked)
        // ETH nur einmal abgefragt (Tag und jüngste Kerze)
        assertEquals(2, http.count("symbol=ETHUSDT"))
    }
}
