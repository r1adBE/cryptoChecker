package com.cryptochecker.app.data.remote

import com.cryptochecker.app.data.CycleCacheStore
import com.cryptochecker.app.data.FolderContext
import com.cryptochecker.app.data.TokenIdentityStore
import com.cryptochecker.app.data.testTokenIdentityStore
import com.cryptochecker.app.domain.market.DataFreshness
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

/**
 * Hoch/Tief je Zeitraum für den Hoch/Tief-Alarm ([NearExtremeDataSource]): Tageskerzen der Kette (Paar, sonst
 * <BASE>USDT), Zwischenspeicher im Speicher und als Datei, eine Stunde Ruhe nach «keine Kerzen»; dazu die Prüfung,
 * ob die Reihe für ein Paar gilt (Name und Kursband).
 */
class NearExtremeDataSourceTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun chainOpen() = RouteHttp.releaseChain()

    private lateinit var root: File
    private lateinit var names: TokenIdentityStore

    @Before
    fun setUp() {
        root = temp.newFolder()
        names = testTokenIdentityStore(root)
    }

    private fun source(http: RouteHttp) =
        NearExtremeDataSource(CandleDataSource(http.client, names), CycleCacheStore(FolderContext(root)), names)

    /** 370 Tageskerzen: Schluss 100 + Index, die laufende (heutige) Kerze zählt nicht zu den Zeiträumen. */
    private val year = Klines.binance(370) { 100.0 + it }

    @Test
    fun rangesPerWindowAreKeptInMemoryAndOnDisk() = runBlocking {
        val http = RouteHttp { url -> if ("binance.vision/api/v3/klines?symbol=BTCUSDT&interval=1d&limit=370" in url) Reply(year) else null }
        val first = source(http)
        val ranges = first.ranges(" btc ", "usdt")!!
        assertEquals("USDT", ranges.currency)
        assertEquals(DataFreshness.BINANCE, ranges.provider)
        assertEquals(469.0, ranges.lastClose, 0.0)
        assertEquals(setOf(30, 90, 365), ranges.ranges.keys)
        // 30 Tage: die abgeschlossenen Kerzen 339…368
        assertEquals(468.0 * 1.01, ranges.ranges.getValue(30).high, 1e-9)
        assertEquals(439.0 * 0.99, ranges.ranges.getValue(30).low, 1e-9)
        assertEquals(104.0 * 0.99, ranges.ranges.getValue(365).low, 1e-9)

        // Dieselbe Instanz: aus dem Speicher, keine zweite Anfrage
        first.ranges("BTC", "USDT")
        assertEquals(1, http.requested.size)

        // Neue Instanz ohne Netz: aus der Datei (gleicher Inhalt)
        val offline = RouteHttp { null }
        val fromDisk = source(offline).ranges("BTC", "USDT")!!
        assertEquals(ranges.ranges, fromDisk.ranges)
        assertEquals(ranges.lastClose, fromDisk.lastClose, 0.0)
        assertEquals(DataFreshness.BINANCE, fromDisk.provider)
        assertTrue(offline.requested.isEmpty())
    }

    @Test
    fun otherQuoteFallsBackToUsdt() = runBlocking {
        val http = RouteHttp { url -> if ("binance.vision/api/v3/klines?symbol=ETHUSDT&interval=1d" in url) Reply(year) else null }
        val ranges = source(http).ranges("ETH", "EUR")!!
        assertEquals("USDT", ranges.currency)
        assertTrue("erst das Paar selbst", http.requested.first().contains("symbol=ETHEUR"))
    }

    @Test
    fun missingPairsRestForAnHour() = runBlocking {
        val http = RouteHttp { null }
        val source = source(http)
        assertNull(source.ranges("ABC", "USDT"))
        val asked = http.requested.size
        assertTrue(asked > 0)
        assertNull(source.ranges("ABC", "USDT"))
        assertEquals("kein zweiter Versuch innerhalb der Stunde", asked, http.requested.size)
        // USD-Paar ohne Kerzen: kein Ausweg über USDT
        assertNull(source.ranges("ABC", "USD"))
        // Leere Angaben: gar nicht gefragt
        val before = http.requested.size
        assertNull(source.ranges(" ", "USDT"))
        assertNull(source.ranges("ABC", ""))
        assertEquals(before, http.requested.size)
    }

    @Test
    fun tooShortSeriesGivesNoRanges() = runBlocking {
        // Zwei Tageskerzen reichen für keinen Zeitraum
        val http = RouteHttp { url -> if ("symbol=SOLUSDT&interval=1d" in url) Reply(Klines.binance(2) { 5.0 }) else null }
        assertNull(source(http).ranges("SOL", "USDT"))
    }

    @Test
    fun seriesMustMatchNameAndPrice() = runBlocking {
        val http = RouteHttp { url -> if ("symbol=CATUSDT&interval=1d" in url) Reply(year) else null }
        val source = source(http)
        val ranges = source.ranges("CAT", "USDT")!!
        // Kurs nahe dem letzten Schluss, keine Namen bekannt: gilt
        assertTrue(source.seriesAllowed(ranges, "Kraken", "CAT", price = 470.0, quoteToSeries = 1.0))
        // Kurs weit daneben, ohne Kurs oder ohne Umrechnung: gilt nicht
        assertFalse(source.seriesAllowed(ranges, "Kraken", "CAT", price = 47.0, quoteToSeries = 1.0))
        assertFalse(source.seriesAllowed(ranges, "Kraken", "CAT", price = null, quoteToSeries = 1.0))
        assertFalse(source.seriesAllowed(ranges, "Kraken", "CAT", price = 470.0, quoteToSeries = null))
        // Anderer Token gleichen Kürzels (Name an der Börse widerspricht Binance): gilt nie
        names.saveExchange("Kraken", mapOf("CAT" to "Simon's Cat"))
        names.saveBinance(mapOf("CAT" to "Catcoin"))
        assertFalse(source.seriesAllowed(ranges, "Kraken", "CAT", price = 470.0, quoteToSeries = 1.0))
    }
}
