package com.cryptochecker.app.data.remote

import com.cryptochecker.app.data.FolderContext
import com.cryptochecker.app.data.MarketExtraCodecs
import com.cryptochecker.app.data.StarterCoinsRepository
import com.cryptochecker.app.data.cycle.CycleCacheStore
import com.cryptochecker.app.data.proxyOf
import com.cryptochecker.app.domain.ExchangeRepository
import com.cryptochecker.app.domain.market.TurnoverSample
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.model.ExchangePairsInfo
import com.cryptochecker.marketdata.model.ExchangePair
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Eingaben für «Heute auffällig» ([UnusualDataSource]) über einen Interceptor: Coins der Rangliste mit Binance-Paar,
 * 24-h-Ticker (eingefrorene und unlesbare fallen weg), Funding der Perpetuals in %, ohne Funding-Quelle trotzdem
 * Ticker, «üblicher» Umsatz aus den Tageswerten auf dem Gerät, unbekanntes Symbol (400) mit neuer Coin-Liste.
 */
class UnusualDataSourceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: FolderContext
    private lateinit var cache: CycleCacheStore

    @Before
    fun setUp() {
        RouteHttp.releaseChain()
        context = FolderContext(temp.newFolder())
        cache = CycleCacheStore(context, ioDispatcher = Dispatchers.IO)
    }

    private val symbols = listOf("BTC", "ETH", "XRP", "BNB", "SOL", "DOGE", "ADA", "TRX", "LINK")

    private val ranking = symbols.mapIndexed { i, s ->
        """{"symbol":"${s.lowercase()}","name":"Coin $s","market_cap_rank":${i + 1},"market_cap":${1e12 / (i + 1)}}"""
    }.joinToString(",", "[", "]")

    private val exchanges = proxyOf(
        ExchangeRepository::class.java,
        mapOf(
            "getExchangeList" to { listOf(ExchangeInfo("Binance", "Binance")) },
            "getExchangeCurrencyPairsInfo" to {
                ExchangePairsInfo(lastSyncDate = 1L, pairs = symbols.map { ExchangePair(it, "USDT", it + "USDT") })
            },
        ),
    )

    private val tickerUrl = "https://data-api.binance.vision/api/v3/ticker/24hr?symbols="
    private val premiumIndex = "https://fapi.binance.com/fapi/v1/premiumIndex"

    /** 24-h-Ticker: ETH unlesbar, XRP ohne Abschlüsse, BNB eingefroren, SOL ohne gültigen Umsatz, dazu ein BTC-Paar. */
    private fun tickers(now: Long = System.currentTimeMillis()): String {
        val stale = now - 3 * 60 * 60_000L
        return listOf(
            """{"symbol":"BTCUSDT","priceChangePercent":"1.5","quoteVolume":"2000000000","closeTime":$now,"count":100}""",
            """{"symbol":"ETHUSDT","priceChangePercent":"x","quoteVolume":"1"}""",
            """{"symbol":"XRPUSDT","priceChangePercent":"4","quoteVolume":"1","count":0}""",
            """{"symbol":"BNBUSDT","priceChangePercent":"4","quoteVolume":"1","closeTime":$stale}""",
            """{"symbol":"SOLUSDT","priceChangePercent":"-7.25","quoteVolume":"-5"}""",
            """{"symbol":"DOGEBTC","priceChangePercent":"9","quoteVolume":"1"}""",
            """{"symbol":"dogeusdt","priceChangePercent":"12","quoteVolume":"300000000"}""",
        ).joinToString(",", "[", "]")
    }

    private val funding = """[{"symbol":"BTCUSDT","lastFundingRate":"0.0001"},{"symbol":"SOLUSDT","lastFundingRate":"x"},""" +
        """{"symbol":"DOGEUSD_PERP","lastFundingRate":"0.5"},{"symbol":"DOGEUSDT","lastFundingRate":"-0.00025"}]"""

    private fun source(http: RouteHttp) = UnusualDataSource(
        http.client,
        StarterCoinsRepository(context, http.client, exchanges, ioDispatcher = Dispatchers.IO),
        cache,
        ioDispatcher = Dispatchers.IO,
    )

    @Test
    fun `live tickers with funding become the coins of the day`() = runBlocking {
        val http = RouteHttp { url ->
            when {
                "api.coingecko.com" in url -> Reply(ranking)
                url.startsWith(tickerUrl) -> Reply(tickers())
                url == premiumIndex -> Reply(funding)
                else -> null
            }
        }
        val input = source(http).fetchInput()
        assertEquals(listOf("BTC", "SOL", "DOGE"), input.coins.map { it.symbol })
        val btc = input.coins[0]
        assertEquals("Coin BTC", btc.name)
        assertEquals(1.5, btc.change24h, 0.0)
        assertEquals(2e9, btc.quoteVolume!!, 0.0)
        assertEquals(1e12, btc.marketCap!!, 1.0)
        assertEquals(0.01, btc.fundingPercent!!, 1e-12)
        val sol = input.coins[1]
        assertNull("Umsatz < 0 fehlt", sol.quoteVolume)
        assertNull("Funding unlesbar", sol.fundingPercent)
        assertEquals(-0.025, input.coins[2].fundingPercent!!, 1e-12)
        assertTrue("erst ab 5 Tagen eigener Wert", input.ownTypical.isEmpty())

        // Der Tageswert des Umsatz-Anteils ist gespeichert (nur Coins mit Umsatz)
        val history = cache.read("unusual_turnover", MarketExtraCodecs.turnoverHistory)!!.value
        assertEquals(setOf("BTC", "DOGE"), history.keys)
        assertEquals(LocalDate.now().toEpochDay(), history.getValue("BTC").single().day)
    }

    @Test
    fun `without funding the tickers still count and own history sets the typical turnover`() = runBlocking {
        val today = LocalDate.now().toEpochDay()
        val past = (1..5).map { TurnoverSample(today - it, 0.001 * it) }
        cache.write("unusual_turnover", mapOf("BTC" to past), System.currentTimeMillis(), MarketExtraCodecs.turnoverHistory)
        val http = RouteHttp { url ->
            when {
                "api.coingecko.com" in url -> Reply(ranking)
                url.startsWith(tickerUrl) -> Reply(tickers())
                else -> null
            }
        }
        val input = source(http).fetchInput()
        assertEquals(3, input.coins.size)
        assertTrue(input.coins.all { it.fundingPercent == null })
        assertEquals(setOf("BTC"), input.ownTypical.keys)
        assertEquals("Median der Vortage", 0.003, input.ownTypical.getValue("BTC"), 1e-12)
        assertEquals(6, cache.read("unusual_turnover", MarketExtraCodecs.turnoverHistory)!!.value.getValue("BTC").size)
    }

    @Test
    fun `unknown symbol reloads the coin list once`() = runBlocking {
        var tickerCalls = 0
        val http = RouteHttp { url ->
            when {
                "api.coingecko.com" in url -> Reply(ranking)
                url.startsWith(tickerUrl) -> if (++tickerCalls == 1) Reply("""{"code":-1121}""", 400) else Reply(tickers())
                else -> null
            }
        }
        val input = source(http).fetchInput()
        assertEquals(3, input.coins.size)
        assertEquals(2, http.count(tickerUrl))
        assertEquals("Coin-Liste erzwungen neu", 2, http.count("api.coingecko.com"))
    }

    @Test
    fun `without coins or tickers there is no input`() = runBlocking {
        try {
            source(RouteHttp { null }).fetchInput()
            fail("ohne Coins")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Keine Coins"))
        }
        val empty = RouteHttp { url ->
            when {
                "api.coingecko.com" in url -> Reply(ranking)
                url.startsWith(tickerUrl) -> Reply("[]")
                else -> null
            }
        }
        try {
            source(empty).fetchInput()
            fail("ohne Ticker")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Keine Ticker"))
        }
    }
}
