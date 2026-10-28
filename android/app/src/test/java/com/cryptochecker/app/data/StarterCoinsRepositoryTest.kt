package com.cryptochecker.app.data

import com.cryptochecker.app.data.remote.Reply
import com.cryptochecker.app.data.remote.RouteHttp
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.model.ExchangePairsInfo
import com.cryptochecker.app.domain.starter.StarterCoins
import com.cryptochecker.app.domain.starter.StarterPairs
import com.cryptochecker.app.domain.starter.StarterPrice
import com.cryptochecker.marketdata.model.ExchangePair
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Start-Merkliste ([StarterCoinsRepository]) über einen Interceptor: Prüfung der Binance-Standortsperre, die fünf
 * grössten Coins mit Paar an der Start-Börse (24 h gemerkt, sonst Ausweich-Liste), die Coins für «Heute auffällig» und
 * die Kurse der Start-Coins (60 s im Speicher).
 */
class StarterCoinsRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: FolderContext

    @Before
    fun setUp() {
        context = FolderContext(temp.newFolder())
    }

    private val markets = "api.coingecko.com/api/v3/coins/markets"

    /** Rangliste: Stablecoin und verpackter Coin fallen weg. */
    private val ranking = listOf(
        "btc" to "Bitcoin", "eth" to "Ethereum", "usdt" to "Tether", "wbtc" to "Wrapped Bitcoin", "xrp" to "XRP",
        "bnb" to "BNB", "sol" to "Solana", "doge" to "Dogecoin", "ada" to "Cardano", "trx" to "TRON", "link" to "Chainlink",
    ).mapIndexed { i, (symbol, name) -> """{"symbol":"$symbol","name":"$name","market_cap_rank":${i + 1},"market_cap":${1e12 / (i + 1)}}""" }
        .joinToString(",", "[", """,{"symbol":"","name":"leer"},{"symbol":"x","market_cap_rank":null,"market_cap":null}]""")

    private val usdtPairs = listOf("BTC", "ETH", "XRP", "BNB", "SOL", "DOGE", "ADA", "TRX", "LINK")
        .map { ExchangePair(it, "USDT", it + "USDT") } + ExchangePair("ETH", "BTC", "ETHBTC")

    private fun exchanges(lastSync: Long, pairs: List<ExchangePair> = usdtPairs) = proxyOf(
        ExchangeRepository::class.java,
        mapOf(
            "getExchangeList" to { listOf(ExchangeInfo("Binance", "Binance"), ExchangeInfo("BinanceUs", "Binance.US")) },
            "getExchangeCurrencyPairsInfo" to { ExchangePairsInfo(lastSyncDate = lastSync, pairs = pairs) },
        ),
    )

    private fun repo(http: RouteHttp, exchanges: ExchangeRepository = exchanges(lastSync = 1L)) =
        StarterCoinsRepository(context, http.client, exchanges)

    @Test
    fun binanceGeoBlockIsProbedAndRemembered() = runBlocking {
        var code = 451
        val http = RouteHttp { url -> if (url == StarterPairs.BINANCE_PROBE_URL) Reply("{}", code) else null }
        val starter = repo(http)
        assertFalse(starter.binanceBlocked)
        // Geräte-Region USA: keine Prüfung
        assertFalse(starter.probeBinance("US"))
        assertTrue(http.requested.isEmpty())

        assertTrue("gesperrt → geändert", starter.probeBinance("CH"))
        assertTrue(starter.binanceBlocked)
        assertEquals("US", starter.starterRegion("CH"))
        assertFalse("gleicher Stand", starter.probeBinance("CH"))

        code = 200
        assertTrue(starter.probeBinance("CH"))
        assertFalse(starter.binanceBlocked)
        assertEquals("CH", starter.starterRegion("CH"))

        // Kein Netz: Stand bleibt
        assertFalse(repo(RouteHttp { null }).probeBinance("DE"))
    }

    @Test
    fun freshPickIsStoredForADay() = runBlocking {
        val http = RouteHttp { url -> if (markets in url && "per_page=30" in url) Reply(ranking) else null }
        val starter = repo(http)
        // Ohne Zwischenspeicher: Ausweich-Liste der Region
        assertEquals(StarterCoins.FALLBACK, starter.cached("CH"))
        assertEquals(StarterCoins.FALLBACK_US, starter.cached("US"))

        val expected = listOf("BTC", "ETH", "XRP", "BNB", "SOL")
        assertEquals(expected, starter.fresh("CH")!!.map { it.symbol })
        assertEquals("Bitcoin", starter.cached("CH").first().name)
        assertEquals(expected, starter.fresh("CH")!!.map { it.symbol })
        assertEquals("zweiter Aufruf aus dem Zwischenspeicher", 1, http.requested.size)
        // Andere Start-Börse: eigener Eintrag
        assertEquals(StarterCoins.FALLBACK_US, starter.cached("US"))
    }

    @Test
    fun publicPairListStepsInForAnUnsyncedList() = runBlocking {
        // Gespeicherte Paarliste noch nie abgeglichen: Basen aus der öffentlichen Kursliste von Binance.US
        val http = RouteHttp { url ->
            when {
                markets in url -> Reply(ranking)
                url == "https://api.binance.us/api/v3/ticker/price" ->
                    Reply("""[{"symbol":"BTCUSDT"},{"symbol":"ETHUSDT"},{"symbol":"BNBUSDT"},{"symbol":"SOLUSDT"},""" +
                        """{"symbol":"DOGEUSDT"},{"symbol":"USDT"},{"symbol":"ETHBTC"}]""")
                else -> null
            }
        }
        assertEquals(listOf("BTC", "ETH", "BNB", "SOL", "DOGE"), repo(http, exchanges(lastSync = 0L)).fresh("US")!!.map { it.symbol })

        // Öffentliche Liste fehlt: die (unabgeglichene) gespeicherte Liste gilt doch
        val noList = RouteHttp { url -> if (markets in url) Reply(ranking) else null }
        assertEquals(5, repo(noList, exchanges(lastSync = 0L)).fresh("CH")!!.size)

        // Neues Gerät ohne Zwischenspeicher: keine Liste überhaupt bzw. Rangliste offline → kein Ergebnis
        fun newDevice(http: RouteHttp, exchanges: ExchangeRepository) =
            StarterCoinsRepository(FolderContext(temp.newFolder()), http.client, exchanges)
        assertNull(newDevice(noList, exchanges(lastSync = 0L, pairs = emptyList())).fresh("DE"))
        assertNull(newDevice(RouteHttp { null }, exchanges(lastSync = 1L)).fresh("CH"))
    }

    @Test
    fun universeIsKeptAndSurvivesAFailedRefresh() = runBlocking {
        var online = true
        val http = RouteHttp { url -> if (online && markets in url && "per_page=40" in url) Reply(ranking) else null }
        val starter = repo(http)
        val coins = starter.universe()!!
        assertEquals(listOf("BTC", "ETH", "XRP", "BNB", "SOL", "DOGE", "ADA", "TRX", "LINK"), coins.map { it.symbol })
        assertEquals(1e12, coins.first().marketCap!!, 1.0)
        assertEquals(coins, starter.universe())
        assertEquals(1, http.requested.size)

        // Erzwungen neu, aber offline: letzter Stand
        online = false
        assertEquals(coins, starter.universe(force = true))
        assertEquals(2, http.requested.size)
        // Ohne Stand und offline: nichts
        assertNull(StarterCoinsRepository(FolderContext(temp.newFolder()), http.client, exchanges(1L)).universe())
    }

    @Test
    fun pricesOfTheStarterCoins() = runBlocking {
        val tickers = """[{"symbol":"BTCUSDT","lastPrice":"60000","priceChangePercent":"1.5"},""" +
            """{"symbol":"ETHUSDT","lastPrice":"0","priceChangePercent":"2"},""" +
            """{"symbol":"SOLUSDT","lastPrice":"150","priceChangePercent":"x"},{"symbol":"DOGEUSDT","lastPrice":"1"}]"""
        val http = RouteHttp { url ->
            when {
                url.startsWith("https://data-api.binance.vision/api/v3/ticker/24hr?symbols=") -> Reply(tickers)
                url.startsWith("https://api.binance.us/api/v3/ticker/24hr?symbols=") -> Reply(tickers)
                else -> null
            }
        }
        val starter = repo(http)
        assertTrue(starter.prices(listOf(" ", ""), "CH").isEmpty())
        val prices = starter.prices(listOf("btc", "ETH", "SOL", "BTC"), "CH")
        // ETH ohne gültigen Kurs fehlt, DOGE war nicht gefragt
        assertEquals(mapOf("BTC" to StarterPrice(60000.0, 1.5), "SOL" to StarterPrice(150.0, null)), prices)
        assertEquals(prices, starter.prices(listOf("BTC", "ETH", "SOL"), "CH"))
        assertEquals("60 s im Speicher", 1, http.requested.size)

        // Binance.US in den USA; offline: leer, nichts gemerkt
        assertEquals(prices, starter.prices(listOf("BTC", "ETH", "SOL"), "US"))
        assertEquals(1, http.count("api.binance.us"))
        assertTrue(repo(RouteHttp { null }).prices(listOf("BTC"), "CH").isEmpty())
    }
}
