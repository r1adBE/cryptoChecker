package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.ExchangeRepository
import com.cryptochecker.app.data.FolderContext
import com.cryptochecker.app.data.MutableTicker
import com.cryptochecker.app.data.TokenIdentityStore
import com.cryptochecker.app.data.fakePortfolioDao
import com.cryptochecker.app.data.fakeWatchDao
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.proxyOf
import com.cryptochecker.app.data.remote.CandleDataSource
import com.cryptochecker.app.data.remote.Klines
import com.cryptochecker.app.data.remote.Reply
import com.cryptochecker.app.data.remote.RouteHttp
import com.cryptochecker.app.data.unusedExchangeRepository
import com.cryptochecker.app.domain.alarm.DepegReadingStore
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.model.ExchangePairsInfo
import com.cryptochecker.app.domain.model.ExchangeTickerResult
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.util.KeyValueStore
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.PairQuery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.math.BigDecimal

/**
 * Aktuelle Portfolio-Kurse ([PortfolioPriceSource.prices]) über einen Interceptor: Binance-Sammelabfrage nur für
 * denselben Token, sonst Merklisten-Paar, sonst Kerzenkurs mit Bezug; Zwischenspeicher (60 s), gemerkter Stand auf dem
 * Gerät, «ungeprüft» und «nicht abrufbar»; eingetragene Börse statt Binance.
 */
class PortfolioPriceSourceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var context: FolderContext
    private lateinit var names: TokenIdentityStore
    private var watches: List<WatchEntity> = emptyList()

    @Before
    fun setUp() {
        RouteHttp.releaseChain()
        root = temp.newFolder()
        context = FolderContext(root)
        names = TokenIdentityStore(context, fakeWatchDao { watches })
        names.saveBinance(mapOf("BTC" to "Bitcoin", "ETH" to "Ethereum"))
    }

    /** Leere Ablage: keine Messung USDT/USD (Ersatz 1). */
    private object EmptyStore : KeyValueStore {
        override fun getInt(key: String, default: Int) = default
        override fun getLong(key: String, default: Long) = default
        override fun getBoolean(key: String, default: Boolean) = default
        override fun getString(key: String): String? = null
        override fun contains(key: String) = false
        override fun all(): Map<String, Any?> = emptyMap()
        override fun edit(block: KeyValueStore.Editor.() -> Unit) = Unit
    }

    private fun source(
        http: RouteHttp,
        exchanges: ExchangeRepository = unusedExchangeRepository(),
        txs: List<PortfolioTxEntity> = emptyList(),
        context: FolderContext = this.context,
    ) = PortfolioPriceSource(
        context, http.client, CandleDataSource(http.client, names), exchanges, fakeWatchDao { watches },
        fakePortfolioDao { txs }, FxRateSource(http.client), names, DepegReadingStore { EmptyStore },
    )

    private val tickers = "https://data-api.binance.vision/api/v3/ticker/price?symbols="
    private val binanceReply = Reply("""[{"symbol":"BTCUSDT","price":"60000.5"},{"symbol":"ETHUSDT","price":"3000"}]""")

    private fun ticker(last: Double, error: String? = null) =
        ExchangeTickerResult(MutableTicker().apply { this.last = last }, PairQuery("X", "USDT", null, FuturesContractType.NONE), error)

    private fun watch(base: String, price: Double, exchangeKey: String = "Kraken") = WatchEntity(
        exchangeKey = exchangeKey, exchangeName = exchangeKey, baseAsset = base, quoteAsset = "USDT",
        lastPrice = price, lastUpdate = System.currentTimeMillis(),
    )

    /** Zwei laufende Stundenkerzen XYZ/USDT mit Schluss [close] beim Binance-Spiegel. */
    private fun candles(close: Double) = RouteHttp { url ->
        if (url.startsWith("https://data-api.binance.vision/api/v3/klines?symbol=XYZUSDT&interval=1h")) {
            Reply(Klines.binance(2, step = Klines.HOUR, last = System.currentTimeMillis() / Klines.HOUR * Klines.HOUR) { close })
        } else {
            null
        }
    }

    @Test
    fun binanceTickersAreCachedAndStored() = runBlocking {
        val http = RouteHttp { url -> if (url.startsWith(tickers)) binanceReply else null }
        val prices = source(http)
        val result = prices.prices(listOf("btc", "eth", "usdt", "eth"))
        assertEquals(mapOf("BTC" to 60000.5, "ETH" to 3000.0, "USDT" to 1.0), result.prices)
        assertTrue(result.unchecked.isEmpty())
        assertTrue(result.unavailable.isEmpty())
        assertEquals(1, http.requested.size)

        // Innerhalb von 60 s aus dem Zwischenspeicher; mit force neu
        prices.prices(listOf("BTC"))
        assertEquals(1, http.requested.size)
        prices.prices(listOf("BTC"), force = true)
        assertEquals(2, http.requested.size)
        assertEquals(60000.5, prices.cached(listOf("btc")).prices["BTC"]!!, 0.0)
        assertEquals(1.0, prices.price("USDT")!!, 0.0)

        // Neuer Prozess: kein Zwischenspeicher, aber der gemerkte Stand
        val file = File(root, "no_backup/portfolio_last_prices.txt")
        assertTrue(file.isFile)
        val restarted = source(RouteHttp { null })
        assertNull(restarted.cached(listOf("BTC")).prices["BTC"])
        assertEquals(60000.5, restarted.stored(listOf("BTC")).prices["BTC"]!!, 0.0)

        // «Portfolio leeren»: Datei und Zwischenspeicher weg
        prices.clearStored()
        assertFalse(file.exists())
        assertNull(prices.cached(listOf("BTC")).prices["BTC"])
        assertNull(prices.stored(listOf("BTC")).prices["BTC"])
    }

    /** «Portfolio leeren» während einer laufenden Abfrage: deren Kurse kommen nicht zurück, die Datei entsteht nicht neu. */
    @Test
    fun clearDuringFetchKeepsNothing() = runBlocking {
        lateinit var prices: PortfolioPriceSource
        var cleared = false
        val http = RouteHttp { url ->
            if (!url.startsWith(tickers)) return@RouteHttp null
            // Die Antwort kommt erst nach dem Leeren (nur bei der ersten Abfrage)
            if (!cleared) {
                cleared = true
                runBlocking { prices.clearStored() }
            }
            binanceReply
        }
        prices = source(http)
        assertNull(prices.prices(listOf("BTC")).prices["BTC"])
        assertNull(prices.cached(listOf("BTC")).prices["BTC"])
        assertFalse(File(root, "no_backup/portfolio_last_prices.txt").exists())
        // Eine danach begonnene Abfrage gilt wieder
        assertEquals(60000.5, prices.prices(listOf("BTC"), force = true).prices["BTC"]!!, 0.0)
    }

    @Test
    fun watchPairPriceStepsInForUnknownNames() = runBlocking {
        watches = listOf(watch("XYZ", 10.0))
        val exchanges = proxyOf(ExchangeRepository::class.java, mapOf("getExchangeTicker" to { ticker(9.9) }))
        val http = candles(10.4)
        val result = source(http, exchanges).prices(listOf("XYZ"))
        assertEquals(9.9, result.prices["XYZ"]!!, 0.0)
        assertEquals("ohne Binance-Namen keine Sammelabfrage, kein Kerzenkurs nötig", 0, http.requested.size)
    }

    @Test
    fun candlePriceNeedsAReference() = runBlocking {
        // Merklisten-Paar antwortet mit Fehler: Kerzenkurs, geprüft gegen den Merklisten-Kurs 10
        watches = listOf(watch("XYZ", 10.0))
        val failing = proxyOf(ExchangeRepository::class.java, mapOf("getExchangeTicker" to { ticker(0.0, error = "offline") }))
        val accepted = source(candles(10.4), failing).prices(listOf("XYZ"))
        assertEquals(10.4, accepted.prices["XYZ"]!!, 0.0)
        assertTrue(accepted.unchecked.isEmpty())

        // Kerzenkurs zehnmal daneben: anderer Token, kein Kurs, auch nicht «ungeprüft»
        val rejected = source(candles(104.0), failing).prices(listOf("XYZ"))
        assertNull(rejected.prices["XYZ"])
        assertFalse("XYZ" in rejected.unchecked)

        // Ohne Merkliste und ohne gemerkten Kurs (neues Gerät) kein Bezug: kein Kurs, «ungeprüft»
        watches = emptyList()
        val unchecked = source(candles(10.4), context = FolderContext(temp.newFolder())).prices(listOf("XYZ"))
        assertNull(unchecked.prices["XYZ"])
        assertEquals(setOf("XYZ"), unchecked.unchecked)
    }

    /**
     * Erste Aktualisierung (frisch installiert, Erfassen-Blatt nie offen, keine Coin-Liste): Binance lehnt die
     * Sammelabfrage wegen eines unbekannten Symbols mit 400 ab — BTC und IOTA bekommen trotzdem ihren Binance-Kurs
     * (Halbieren), ohne Coin-Liste vorab und ohne «nicht abrufbar»; der unbekannte Coin ist nicht gescheitert.
     */
    @Test
    fun firstRefreshWithoutCoinListStillPricesBinanceCoins() = runBlocking {
        names.saveBinance(mapOf("BTC" to "Bitcoin", "ETH" to "Ethereum", "IOTA" to "IOTA", "FUT" to "Futures Only"))
        val known = mapOf("BTCUSDT" to "60000.5", "IOTAUSDT" to "0.21")
        val http = RouteHttp { url ->
            if (!url.startsWith(tickers)) return@RouteHttp null
            val asked = java.net.URLDecoder.decode(url.removePrefix(tickers), "UTF-8")
            if ("FUTUSDT" in asked) return@RouteHttp Reply("""{"code":-1121,"msg":"Invalid symbol."}""", 400)
            Reply(known.filterKeys { "\"$it\"" in asked }.entries.joinToString(",", "[", "]") {
                """{"symbol":"${it.key}","price":"${it.value}"}"""
            })
        }
        val result = source(http, context = FolderContext(temp.newFolder())).prices(listOf("BTC", "IOTA", "FUT"))
        assertEquals(60000.5, result.prices["BTC"]!!, 0.0)
        assertEquals(0.21, result.prices["IOTA"]!!, 0.0)
        assertTrue(result.unavailable.isEmpty())
        assertTrue(result.unchecked.isEmpty())
        assertEquals("keine Coin-Liste vorab geladen", 0, http.count("exchangeInfo"))
        // Abgelehnt: alle, dann [BTC, IOTA] gut und [FUT] einzeln abgelehnt
        assertEquals(3, http.count("ticker/price"))
    }

    /** Ohne Binance-Namen und ohne jede Quelle mit Kurs (Weg b): «Kein Markt», nicht «Kurs ungeprüft». */
    @Test
    fun noSourceAtAllIsNoMarket() = runBlocking {
        val result = source(RouteHttp { null }, context = FolderContext(temp.newFolder())).prices(listOf("ZZZ"))
        assertNull(result.prices["ZZZ"])
        assertEquals(setOf("ZZZ"), result.noMarket)
        assertTrue(result.unchecked.isEmpty())
        assertTrue(result.unavailable.isEmpty())

        // Kerzenkurs ohne Bezug (Weg a): weiterhin «Kurs ungeprüft»
        val candle = source(candles(10.4), context = FolderContext(temp.newFolder())).prices(listOf("XYZ"))
        assertEquals(setOf("XYZ"), candle.unchecked)
        assertTrue(candle.noMarket.isEmpty())
    }

    @Test
    fun failedRequestsMarkCoinsUnavailable() = runBlocking {
        val result = source(RouteHttp { null }).prices(listOf("BTC"))
        assertNull(result.prices["BTC"])
        assertEquals(setOf("BTC"), result.unavailable)
        assertNull(source(RouteHttp { null }).price("BTC"))
    }

    @Test
    fun enteredExchangeReplacesBinance() = runBlocking {
        val txs = listOf(
            PortfolioTxEntity(coin = "BTC", type = PortfolioTxType.BUY, amount = BigDecimal.ONE, time = 1L, exchange = "Kraken"),
        )
        val pairs = ExchangePairsInfo(pairs = listOf(ExchangePair("BTC", "EUR", "XBTEUR"), ExchangePair("BTC", "USD", "XBTUSD")))
        val list = listOf(ExchangeInfo("Binance", "Binance"), ExchangeInfo("Kraken", "Kraken"))
        val kraken = proxyOf(
            ExchangeRepository::class.java,
            mapOf("getExchangeList" to { list }, "getExchangeCurrencyPairsInfo" to { pairs }, "getExchangeTicker" to { ticker(61000.0) }),
        )
        val http = RouteHttp { url -> if (url.startsWith(tickers)) binanceReply else null }
        val result = source(http, kraken, txs).prices(listOf("BTC", "ETH"))
        // USD-Paar (vor EUR), USDT/USD ohne Messung = 1
        assertEquals(61000.0, result.prices["BTC"]!!, 0.0)
        assertEquals("Kraken", result.sources["BTC"])
        assertEquals(60000.5, result.binance["BTC"]!!, 0.0)
        assertEquals(3000.0, result.prices["ETH"]!!, 0.0)
        assertNull(result.sources["ETH"])

        // Börse antwortet nicht: Binance bleibt, Hinweis «Kraken nicht erreichbar»
        val down = proxyOf(
            ExchangeRepository::class.java,
            mapOf("getExchangeList" to { list }, "getExchangeCurrencyPairsInfo" to { pairs }, "getExchangeTicker" to { error("offline") }),
        )
        val fallback = source(http, down, txs).prices(listOf("BTC"))
        assertEquals(60000.5, fallback.prices["BTC"]!!, 0.0)
        assertEquals("Kraken", fallback.exchangeFailed["BTC"])
        assertNull(fallback.sources["BTC"])
    }
}
