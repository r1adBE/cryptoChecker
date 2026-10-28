package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.ExchangeRepository
import com.cryptochecker.app.data.FolderContext
import com.cryptochecker.app.data.fakePortfolioDao
import com.cryptochecker.app.data.fakeWatchDao
import com.cryptochecker.app.data.proxyOf
import com.cryptochecker.app.data.remote.CandleDataSource
import com.cryptochecker.app.data.remote.Reply
import com.cryptochecker.app.data.remote.RouteHttp
import com.cryptochecker.app.data.testTokenIdentityStore
import com.cryptochecker.app.data.unusedExchangeRepository
import com.cryptochecker.app.domain.alarm.DepegReadingStore
import com.cryptochecker.app.domain.model.ExchangePairsInfo
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.FuturesContractType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Coin-Liste für die Suche im Erfassen-Blatt ([PortfolioPriceSource.coins]): Binance-Spot-Paare gegen USDT im Handel,
 * sonst die gespeicherte Paarliste der Börse «Binance», sonst Coinbase-USD-Produkte; einen Tag gemerkt (auch über einen
 * Neustart), sonst die ältere Liste.
 */
class PortfolioCoinListTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: FolderContext

    @Before
    fun setUp() {
        context = FolderContext(temp.newFolder())
    }

    private fun source(http: RouteHttp, exchanges: ExchangeRepository = unusedExchangeRepository()) = PortfolioPriceSource(
        context, http.client, CandleDataSource(http.client, testTokenIdentityStore(temp.newFolder())), exchanges,
        fakeWatchDao { emptyList() }, fakePortfolioDao(), FxRateSource(http.client),
        testTokenIdentityStore(temp.newFolder()), DepegReadingStore { error("keine Messung nötig") },
    )

    private val exchangeInfo = "https://data-api.binance.vision/api/v3/exchangeInfo?permissions=SPOT"
    private val coinbaseProducts = "https://api.exchange.coinbase.com/products"

    @Test
    fun binanceSpotListIsKeptForADay() = runBlocking {
        val body = """{"symbols":[""" +
            """{"baseAsset":"btc","quoteAsset":"USDT","status":"TRADING"},""" +
            """{"baseAsset":"ETH","quoteAsset":"USDT","status":"TRADING"},""" +
            """{"baseAsset":"OLD","quoteAsset":"USDT","status":"BREAK"},""" +
            """{"baseAsset":"SOL","quoteAsset":"BTC","status":"TRADING"},""" +
            """{"baseAsset":"BAD-1","quoteAsset":"USDT","status":"TRADING"}]}"""
        val http = RouteHttp { url -> if (url.startsWith(exchangeInfo)) Reply(body) else null }
        val prices = source(http)
        assertEquals(listOf("BTC", "ETH", "USDT"), prices.coins())
        assertEquals(listOf("BTC", "ETH", "USDT"), prices.coins())
        assertEquals("zweiter Aufruf aus dem Speicher", 1, http.requested.size)

        // Neustart: Liste aus den Einstellungen, ohne Netz
        val offline = RouteHttp { null }
        assertEquals(listOf("BTC", "ETH", "USDT"), source(offline).coins())
        assertTrue(offline.requested.isEmpty())
    }

    @Test
    fun storedPairListStepsIn() = runBlocking {
        val pairs = ExchangePairsInfo(
            pairs = listOf(
                ExchangePair("SOL", "USDT", "SOLUSDT"),
                ExchangePair("ADA", "BTC", "ADABTC"),
                ExchangePair("BTC", "USDT", "BTCUSDT", FuturesContractType.PERPETUAL),
            ),
        )
        val exchanges = proxyOf(ExchangeRepository::class.java, mapOf("getExchangeCurrencyPairsInfo" to { pairs }))
        // Binance-Spot nicht erreichbar: nur Spot-Paare gegen USDT der gespeicherten Liste
        assertEquals(listOf("SOL", "USDT"), source(RouteHttp { null }, exchanges).coins())
    }

    @Test
    fun coinbaseIsTheLastSource() = runBlocking {
        val products = """[{"base_currency":"DOT","quote_currency":"USD"},""" +
            """{"base_currency":"ATOM","quote_currency":"USD","trading_disabled":true},""" +
            """{"base_currency":"LINK","quote_currency":"EUR"}]"""
        val http = RouteHttp { url -> if (url == coinbaseProducts) Reply(products) else null }
        assertEquals(listOf("DOT", "USDT"), source(http).coins())
        assertEquals(1, http.count(exchangeInfo))
    }

    @Test
    fun noSourceGivesAnEmptyList() = runBlocking {
        val http = RouteHttp { null }
        assertTrue(source(http).coins().isEmpty())
        // Beim nächsten Mal erneut versucht (nichts gemerkt)
        assertTrue(source(http).coins().isEmpty())
        assertEquals(2, http.count(coinbaseProducts))
    }
}
