package com.cryptochecker.app.data

import com.cryptochecker.app.data.remote.Reply
import com.cryptochecker.app.data.remote.RouteHttp
import com.cryptochecker.app.domain.logos.CoinLogos
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Coin- und Aktiennamen ([CoinNames]) mit Testordner und Interceptor: TradFi-Paare und -Kürzel (gespeichert),
 * Auszug nur ohne gespeicherte Namen, Listen-Namen mit Vorrang der gespeicherten, Rangliste ganz oder als Lückenfüller,
 * Nasdaq-Symbolliste höchstens wöchentlich und nach einem Fehler frühestens nach einer Stunde.
 */
class CoinNamesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val nasdaq = CoinLogos.NASDAQ_LISTED_URL
    private val other = CoinLogos.OTHER_LISTED_URL

    private val nasdaqListed = "Symbol|Security Name|Market Category|Test Issue|Financial Status|Round Lot Size|ETF|NextShares\n" +
        "NVDA|NVIDIA Corporation - Common Stock|Q|N|N|100|N|N\n" +
        "ZXZZT|NASDAQ TEST STOCK|G|Y|N|100|N|N\n" +
        "File Creation Time: 1118202612:00|||||||"

    private val otherListed = "ACT Symbol|Security Name|Exchange|CQS Symbol|ETF|Round Lot Size|Test Issue|NASDAQ Symbol\n" +
        "CAT|Caterpillar, Inc. Common Stock|N|CAT|N|100|N|CAT\n" +
        "NVDA|Duplicate Corp. Common Stock|N|NVDA|N|100|N|NVDA"

    private fun names(context: FolderContext, http: RouteHttp = RouteHttp { null }) =
        CoinNames(context, http.client, Dispatchers.IO)

    @Test
    fun `tradfi pairs are stored and survive a restart`() {
        val context = FolderContext(temp.newFolder())
        val first = names(context)
        val pair = CoinLogos.pairKey("BinanceFutures", "nvda", "usdt", "PERPETUAL")
        assertFalse(first.isTradFi("BinanceFutures", "NVDA", "USDT", "PERPETUAL"))
        assertTrue(first.setTradFiPairs(setOf(pair)))
        assertFalse("unverändert", first.setTradFiPairs(setOf(pair)))
        assertTrue(first.isTradFi("BinanceFutures", "NVDA", "USDT", "PERPETUAL"))
        assertFalse(first.isTradFi("Binance", "NVDA", "USDT", "PERPETUAL"))

        val restarted = names(context)
        assertEquals(setOf(pair), restarted.tradFiPairs.value)
        assertTrue(restarted.setTradFiBases(setOf("NVDA")))
        assertFalse(restarted.setTradFiBases(setOf("NVDA")))
        assertEquals(setOf("NVDA"), names(context).tradFiBases)
    }

    @Test
    fun `snapshot only fills an empty list and yields to list names`() = runBlocking {
        val context = FolderContext(temp.newFolder())
        val coins = names(context)
        assertTrue(coins.isEmpty)
        coins.showSnapshotIfEmpty { mapOf("BTC" to "Bitcoin (Auszug)", "DOGE" to "Dogecoin") }
        assertEquals(2, coins.size)
        // Zweiter Auszug: Namen sind schon da, der Auszug wird gar nicht erst gebaut
        coins.showSnapshotIfEmpty { throw AssertionError("Auszug nicht nötig") }

        // Frische Listen haben Vorrang vor dem Auszug; fehlende Namen bleiben aus dem Auszug
        coins.publishListNames(mapOf("BTC" to "Bitcoin", "ETH" to "Ethereum"))
        assertEquals("Bitcoin", coins.names.value["BTC"])
        assertEquals("Dogecoin", coins.names.value["DOGE"])
        assertEquals("Ethereum", coins.names.value["ETH"])
        assertEquals("Bitcoin", coins.name("Binance", "btc", "USDT", "NONE"))
        assertNull(coins.name("Binance", "XYZ", "USDT", "NONE"))
        assertTrue(File(context.noBackupFilesDir, "coin_logos/names.txt").isFile)

        // Nach dem Neustart aus der Datei; gespeicherte Namen behalten Vorrang vor neuen Listen-Namen
        val restarted = names(context)
        restarted.loadSavedNames()
        assertEquals("Bitcoin", restarted.names.value["BTC"])
        restarted.publishListNames(mapOf("BTC" to "Bitcoin neu", "SOL" to "Solana"))
        assertEquals("Bitcoin", restarted.names.value["BTC"])
        assertEquals("Solana", restarted.names.value["SOL"])
    }

    @Test
    fun `ranking names replace fully or only fill gaps`() = runBlocking {
        val context = FolderContext(temp.newFolder())
        val coins = names(context)
        coins.applyRankingNames(mapOf("BTC" to "Bitcoin", "ETH" to "Ethereum"), full = true)
        val partial = coins.applyRankingNames(mapOf("BTC" to "Bitcoin neu", "SOL" to "Solana"), full = false)
        assertEquals(mapOf("BTC" to "Bitcoin neu", "SOL" to "Solana", "ETH" to "Ethereum"), partial)
        val full = coins.applyRankingNames(mapOf("ADA" to "Cardano"), full = true)
        assertEquals(mapOf("ADA" to "Cardano"), full)
        assertEquals(mapOf("ADA" to "Cardano"), coins.names.value)

        // Die Datei schreibt der Aufrufer (mit dem Ordner der Logos); ein neuer Prozess liest sie
        File(context.noBackupFilesDir, "coin_logos").mkdirs()
        coins.writeNamesFile(full)
        val restarted = names(context)
        restarted.loadSavedNames()
        assertEquals("Cardano", restarted.names.value["ADA"])
    }

    @Test
    fun `stock names come from the nasdaq lists once a week`() = runBlocking {
        val context = FolderContext(temp.newFolder())
        val http = RouteHttp { url ->
            when (url) {
                nasdaq -> Reply(nasdaqListed)
                other -> Reply(otherListed)
                else -> null
            }
        }
        val coins = names(context, http)
        // Ohne TradFi-Kürzel keine Abfrage
        coins.refreshStockNames()
        assertEquals(0, http.requested.size)

        coins.setTradFiBases(setOf("NVDA", "CAT"))
        coins.setTradFiPairs(setOf(CoinLogos.pairKey("BinanceFutures", "CAT", "USDT", "PERPETUAL")))
        coins.refreshStockNames()
        assertEquals(listOf(nasdaq, other), http.requested.toList())
        assertEquals("NVIDIA Corporation", coins.names.value["TRADFI:NVDA"])
        assertEquals("Caterpillar, Inc.", coins.names.value["TRADFI:CAT"])
        assertFalse("Test-Einträge fallen weg", coins.names.value.containsKey("TRADFI:ZXZZT"))
        assertEquals("Caterpillar, Inc.", coins.name("BinanceFutures", "CAT", "USDT", "PERPETUAL"))
        assertNull("kein TradFi-Paar", coins.name("Binance", "CAT", "USDT", "NONE"))

        // Innerhalb der Woche kein neuer Abruf
        coins.refreshStockNames()
        assertEquals(2, http.requested.size)

        // Neuer Prozess: Aktiennamen aus der Datei, ebenfalls ohne Abruf
        val restarted = names(context, http)
        restarted.refreshStockNames()
        assertEquals(2, http.requested.size)
        assertEquals("NVIDIA Corporation", restarted.names.value["TRADFI:NVDA"])
    }

    @Test
    fun `failed stock list is retried at the earliest after an hour`() = runBlocking {
        val context = FolderContext(temp.newFolder())
        var online = false
        val http = RouteHttp { url -> if (online && url == nasdaq) Reply(nasdaqListed) else null }
        val coins = names(context, http)
        coins.setTradFiBases(setOf("NVDA"))
        coins.refreshStockNames()
        assertEquals(2, http.requested.size)
        assertNull(coins.names.value["TRADFI:NVDA"])
        assertFalse(File(context.noBackupFilesDir, "coin_logos/stock_names.txt").exists())

        online = true
        coins.refreshStockNames()
        assertEquals("innerhalb der Stunde kein neuer Versuch", 2, http.requested.size)
        assertNull(coins.names.value["TRADFI:NVDA"])
    }
}
