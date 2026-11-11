package com.cryptochecker.app.data.remote

import com.cryptochecker.app.domain.market.GasNetwork
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Netzwerkgebühren ([GasDataSource]) mit einem Interceptor als Anbieter: eth_feeHistory, sonst eth_gasPrice je
 * Knoten der Reihe nach, mempool.space für Bitcoin, Preise von Binance, sonst Coinbase; Herkunft, Zwischenspeicher
 * und Blockhöhe für die Halving-Schätzung.
 */
class GasDataSourceTest {

    /** Anfragen je Schlüssel: GET «host/pfad», POST «host methode» (JSON-RPC). */
    private val hits = ConcurrentHashMap<String, Int>()

    private fun key(request: Request): String {
        val url = request.url
        val body = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }
            ?: return url.host + url.encodedPath
        val method = when {
            "eth_feeHistory" in body -> "eth_feeHistory"
            "eth_gasPrice" in body -> "eth_gasPrice"
            else -> "?"
        }
        return url.host + " " + method
    }

    private fun source(replies: Map<String, String>) = GasDataSource(
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val key = key(request)
            hits.merge(key, 1) { a, b -> a + b }
            val body = replies[key] ?: throw IOException("offline: $key")
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build(),
    )

    // Grundgebühr des nächsten Blocks 2 gwei, Trinkgelder 1/2/3 gwei
    private val feeHistory = """{"jsonrpc":"2.0","id":1,"result":{"oldestBlock":"0x1",""" +
        """"baseFeePerGas":["0x3b9aca00","0x77359400"],""" +
        """"reward":[["0x3b9aca00","0x77359400","0xb2d05e00"],["0x3b9aca00","0x77359400","0xb2d05e00"]]}}"""

    private val binancePrices = """[{"symbol":"ETHUSDT","price":"2500.00"},{"symbol":"POLUSDT","price":"0.40"},""" +
        """{"symbol":"BNBUSDT","price":"600.00"},{"symbol":"BTCUSDT","price":"60000.00"}]"""

    private val mempool = """{"fastestFee":12,"halfHourFee":8,"hourFee":5,"economyFee":3,"minimumFee":1}"""

    private val fullReplies = mapOf(
        "ethereum-rpc.publicnode.com eth_feeHistory" to feeHistory,
        // Base ohne eth_feeHistory: einfacher Gaspreis 0.1 gwei für alle drei Stufen
        "base-rpc.publicnode.com eth_gasPrice" to """{"jsonrpc":"2.0","id":1,"result":"0x5f5e100"}""",
        "data-api.binance.vision/api/v3/ticker/price" to binancePrices,
        "mempool.space/api/v1/fees/recommended" to mempool,
    )

    @Test
    fun reportCombinesNodesMempoolAndPrices() = runBlocking {
        val sourced = source(fullReplies).fetchSourced()
        val report = sourced.value
        assertEquals(listOf(GasNetwork.ETHEREUM, GasNetwork.BASE), report.evm.map { it.network })

        val eth = report.evm[0]
        assertEquals(3.0, eth.slowGwei, 1e-12)
        assertEquals(4.0, eth.normalGwei, 1e-12)
        assertEquals(5.0, eth.fastGwei, 1e-12)
        // 4 gwei × 21 000 Gas × 2500 USD
        assertEquals(0.21, eth.transferUsd!!, 1e-9)

        val base = report.evm[1]
        assertEquals(0.1, base.slowGwei, 1e-12)
        assertEquals(0.1, base.normalGwei, 1e-12)
        assertEquals(0.1, base.fastGwei, 1e-12)
        assertEquals(0.1e-9 * 21_000.0 * 2500.0, base.transferUsd!!, 1e-12)
        assertEquals(1, hits["base-rpc.publicnode.com eth_feeHistory"])
        assertEquals(1, hits["base-rpc.publicnode.com eth_gasPrice"])

        val btc = report.btc
        assertNotNull(btc)
        assertEquals(12.0, btc!!.fast, 0.0)
        assertEquals(8.0, btc.normal, 0.0)
        assertEquals(5.0, btc.slow, 0.0)
        // 8 sat/vB × 140 vB × 60 000 USD
        assertEquals(0.672, btc.transferUsd!!, 1e-9)

        // Arbitrum: beide Knoten der Reihe nach gefragt, beide ohne Antwort → fehlt im Bericht
        assertEquals(1, hits["arbitrum-one-rpc.publicnode.com eth_gasPrice"])
        assertEquals(1, hits["arb1.arbitrum.io eth_gasPrice"])
        assertTrue(report.evm.none { it.network == GasNetwork.ARBITRUM })

        assertEquals("publicnode.com, mempool.space", sourced.provider)
        assertNull("Coinbase nur, wenn Binance keine Preise liefert", hits["api.coinbase.com/v2/exchange-rates"])
    }

    @Test
    fun resultIsCachedWithinTheWindow() = runBlocking {
        val gas = source(fullReplies)
        val first = gas.fetchSourced()
        val second = gas.fetchSourced()
        assertSame(first, second)
        assertEquals(first.value, gas.fetch())
        assertEquals(1, hits["mempool.space/api/v1/fees/recommended"])
        // Höchstalter 0: neu laden
        gas.fetch(maxAgeMillis = 0L)
        assertEquals(2, hits["mempool.space/api/v1/fees/recommended"])
    }

    @Test
    fun withoutAnyFeeDataItThrowsAndCachesNothing() = runBlocking {
        val gas = source(mapOf("data-api.binance.vision/api/v3/ticker/price" to binancePrices))
        try {
            gas.fetch()
            fail("Fehler erwartet")
        } catch (e: IllegalStateException) {
            assertEquals("Keine Gebührendaten", e.message)
        }
        // Nichts gemerkt: der nächste Aufruf fragt erneut
        try {
            gas.fetch()
            fail("Fehler erwartet")
        } catch (e: IllegalStateException) {
            assertEquals(2, hits["mempool.space/api/v1/fees/recommended"])
        }
    }

    @Test
    fun secondNodeAndCoinbasePricesStepIn() = runBlocking {
        val sourced = source(
            mapOf(
                // Erster Knoten meldet einen Fehler und kennt eth_gasPrice nicht → nächster Knoten
                "ethereum-rpc.publicnode.com eth_feeHistory" to
                    """{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"method not found"}}""",
                // Ohne Trinkgelder: alle drei Stufen = Grundgebühr 20 gwei
                "eth.llamarpc.com eth_feeHistory" to """{"jsonrpc":"2.0","id":1,"result":{"baseFeePerGas":["0x0","0x4a817c800"]}}""",
                // Leere Preisliste von Binance → Kurse von Coinbase (1 USD = x Coin)
                "data-api.binance.vision/api/v3/ticker/price" to "[]",
                "api.coinbase.com/v2/exchange-rates" to """{"data":{"currency":"USD","rates":{"ETH":"0.0005","BTC":"0.00002"}}}""",
                // Nur «fastestFee»: normal und langsam übernehmen den Wert
                "mempool.space/api/v1/fees/recommended" to """{"fastestFee":10}""",
            ),
        ).fetchSourced()
        val report = sourced.value
        assertEquals(listOf(GasNetwork.ETHEREUM), report.evm.map { it.network })
        val eth = report.evm.single()
        assertEquals(20.0, eth.slowGwei, 1e-12)
        assertEquals(20.0, eth.normalGwei, 1e-12)
        assertEquals(20.0, eth.fastGwei, 1e-12)
        assertEquals(20e-9 * 21_000.0 * 2000.0, eth.transferUsd!!, 1e-9)
        assertEquals(1, hits["ethereum-rpc.publicnode.com eth_gasPrice"])
        assertNull("dritter Knoten nicht nötig", hits["cloudflare-eth.com eth_feeHistory"])

        val btc = report.btc!!
        assertEquals(10.0, btc.normal, 0.0)
        assertEquals(10.0, btc.slow, 0.0)
        assertEquals(10.0 * 140.0 * 1e-8 * 50_000.0, btc.transferUsd!!, 1e-9)
        assertEquals("llamarpc.com, mempool.space", sourced.provider)
    }

    @Test
    fun withoutPricesTheFeesStayButCostIsUnknown() = runBlocking {
        val report = source(mapOf("mempool.space/api/v1/fees/recommended" to mempool)).fetch()
        assertTrue(report.evm.isEmpty())
        assertEquals(8.0, report.btc!!.normal, 0.0)
        assertNull(report.btc!!.transferUsd)
    }

    @Test
    fun bitcoinBlocksFromMempool() = runBlocking {
        val height = "mempool.space/api/blocks/tip/height"
        val adjustment = "mempool.space/api/v1/difficulty-adjustment"
        val blocks = source(mapOf(height to "870000\n", adjustment to """{"progressPercent":50.1,"timeAvg":597000}""")).bitcoinBlocks()
        assertEquals(870000, blocks!!.first)
        assertEquals(597.0, blocks.second, 1e-12)

        // Mittlere Blockzeit 0, unlesbare Höhe oder fehlende Antwort: keine Schätzung
        assertNull(source(mapOf(height to "870000", adjustment to """{"timeAvg":0}""")).bitcoinBlocks())
        assertNull(source(mapOf(height to "abc", adjustment to """{"timeAvg":600000}""")).bitcoinBlocks())
        assertNull(source(mapOf(height to "870000")).bitcoinBlocks())
    }
}
