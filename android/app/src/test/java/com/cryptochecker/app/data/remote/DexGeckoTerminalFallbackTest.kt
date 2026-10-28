package com.cryptochecker.app.data.remote

import com.cryptochecker.app.domain.exceptions.HttpMarketError
import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.obj
import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.market.DexScreener
import com.cryptochecker.marketdata.model.market.OneTrading
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * GeckoTerminal als Reserve der DEX-Kurse ([SingleTickerFallback.loadAfterMissing]): kennt DexScreener den
 * Pool nicht oder antwortet mit 5xx, kommt der Kurs von GeckoTerminal; eine Bremse von GeckoTerminal pausiert
 * DexScreener nie (anderer Anbieter, [SingleTickerFallback.sameHost]). Wie iOS `ExchangeListParityTests`.
 */
class DexGeckoTerminalFallbackTest {

    private val address = "0x88e6a0c2ddd26feeb64f039a2c41296fcb3f5640"
    private val pool = PairQuery("WETH", "USDC", "ethereum/$address", FuturesContractType.NONE)
    private val dexUrl = "https://api.dexscreener.com/latest/dex/pairs/ethereum/$address"
    private val geckoUrl = "https://api.geckoterminal.com/api/v2/networks/eth/pools/$address"
    private val hits = HashMap<String, Int>()

    private fun geckoBody(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "testdata/exchanges").isDirectory) dir = dir.parentFile
        val file = File(dir ?: throw IllegalStateException("testdata/exchanges nicht gefunden"),
            "testdata/exchanges/exchange_dexscreener.json")
        return ParityJson.parse(file.readText(Charsets.UTF_8)).obj()["cases"].list().map { it.obj() }
            .first { it["kind"] == "singleFallback" && it["expect"].obj()["error"] != true }["body"] as String
    }

    private fun source(replies: Map<String, Pair<Int, String>>) = MarketRemoteDataSource(
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            hits[url] = (hits[url] ?: 0) + 1
            val (code, body) = replies[url] ?: throw IOException("offline: $url")
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("status $code")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build(),
    )

    @Test
    fun unknownPoolTakesThePriceFromGeckoTerminal() = runBlocking {
        val ticker = source(mapOf(dexUrl to (200 to """{"schemaVersion":"1.0.0","pairs":null}"""), geckoUrl to (200 to geckoBody())))
            .fetchMarketTicker(DexScreener(), pool)
        assertEquals(2323.61, ticker.last, 1e-9)
        assertEquals(-1.41, ticker.change24hPercent!!, 1e-9)
        assertTrue("Liquidität aus reserve_in_usd", (ticker.liquidityUsd ?: 0.0) > 1_000_000.0)
        assertEquals(1, hits[geckoUrl])
    }

    @Test
    fun serverErrorTakesThePriceFromGeckoTerminal() = runBlocking {
        val ticker = source(mapOf(dexUrl to (502 to "Bad Gateway"), geckoUrl to (200 to geckoBody())))
            .fetchMarketTicker(DexScreener(), pool)
        assertEquals(2323.61, ticker.last, 1e-9)
    }

    @Test
    fun geckoTerminalRateLimitKeepsTheOriginalError() = runBlocking {
        val limited = """{"status":{"error_code":429,"error_message":"rate limit"}}"""
        try {
            source(mapOf(dexUrl to (200 to """{"pairs":null}"""), geckoUrl to (429 to limited))).fetchMarketTicker(DexScreener(), pool)
            fail("Fehler erwartet")
        } catch (e: MarketParseException) {
            assertEquals(DexScreener.POOL_NOT_FOUND, e.message)
        }
        try {
            source(mapOf(dexUrl to (500 to "x"), geckoUrl to (429 to limited))).fetchMarketTicker(DexScreener(), pool)
            fail("Fehler erwartet")
        } catch (e: HttpMarketError) {
            // Der Serverfehler von DexScreener, nicht die Bremse von GeckoTerminal (sie würde DexScreener pausieren)
            assertEquals(500, e.httpCode)
        }
    }

    @Test
    fun sameHostOnlyForTheExchangeItself() {
        val btcEur = PairQuery("BTC", "EUR", "BTC_EUR", FuturesContractType.NONE)
        assertTrue(SingleTickerFallback.sameHost(OneTrading(), 0, btcEur, "https://api.onetrading.com/fast/v1/market-ticker"))
        assertFalse(SingleTickerFallback.sameHost(DexScreener(), 0, pool, geckoUrl))
    }

    @Test
    fun onlyPoolNotFoundOpensTheFallback() {
        val dex = DexScreener()
        assertTrue(dex.tickerFallbackOnParseError(0, MarketParseException(DexScreener.POOL_NOT_FOUND)))
        assertFalse(dex.tickerFallbackOnParseError(0, MarketParseException(MarketParseException.NO_DATA)))
        assertFalse(OneTrading().tickerFallbackOnParseError(0, MarketParseException(DexScreener.POOL_NOT_FOUND)))
    }
}
