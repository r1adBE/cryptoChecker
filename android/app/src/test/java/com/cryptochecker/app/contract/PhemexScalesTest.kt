package com.cryptochecker.app.contract

import com.cryptochecker.marketdata.model.market.PhemexScales
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phemex-Faktoren aus `/public/products` ([PhemexScales]): Preis (Ep) je Produkt (ohne Angabe kein Kurs), Werte (Ev)
 * je Währung, Standard 8 nur ohne Angabe. Gegenstück: `Tests/PhemexScalesTests.swift`.
 */
class PhemexScalesTest {

    private val data = JSONObject(
        """{"currencies":[{"currency":"BTC","valueScale":8},{"currency":"SHIB","valueScale":2},{"currency":"VPAD","valueScale":4},
        {"currency":"USDT","valueScale":8},{"currency":"NEW","valueScale":6}],
        "products":[{"symbol":"sBTCUSDT","type":"Spot","baseCurrency":"BTC","quoteCurrency":"USDT","priceScale":8},
        {"symbol":"sNOSCALEUSDT","type":"Spot","baseCurrency":"NOSCALE","quoteCurrency":"USDT"},
        {"symbol":"sNEWUSDT","type":"Spot","baseCurrency":"NEW","quoteCurrency":"USDT","priceScale":6},
        {"symbol":"BTCUSD","type":"Perpetual","priceScale":4,"baseCurrency":"BTC","quoteCurrency":"USD"}]}""",
    )

    @Test
    fun scalesFromProducts() {
        val s = PhemexScales.parse(data)
        // Preis je Produkt aus der Angabe; Spot ohne priceScale, fremdes Symbol und Kontrakte: kein Teiler (nie 8 geraten)
        assertEquals(1e8, s.priceDivisor("sBTCUSDT")!!, 0.0)
        assertEquals(1e6, s.priceDivisor("sNEWUSDT")!!, 0.0)
        assertNull(s.priceDivisor("sNOSCALEUSDT"))
        assertNull(s.priceDivisor("sMISSINGUSDT"))
        assertNull(s.priceDivisor("BTCUSD"))
        // Werte je Währung: SHIB 2, VPAD 4, NEW 6 aus der Liste
        assertEquals(1e2, s.valueDivisor("SHIB"), 0.0)
        assertEquals(1e4, s.valueDivisor("VPAD"), 0.0)
        assertEquals(1e6, s.valueDivisor("NEW"), 0.0)
        assertEquals(1e8, s.valueDivisor("USDT"), 0.0)
        assertEquals("NEW" to "USDT", s.currencies("sNEWUSDT", null, null))
    }

    @Test
    fun fallbackWithoutProductList() {
        val s = PhemexScales()
        // Ohne Produktliste: kein Preisteiler; Werte 10^8, nur die dokumentierten Ausnahmen (SHIB 2, VPAD 4)
        assertNull(s.priceDivisor("sSHIBUSDT"))
        assertEquals(1e2, s.valueDivisor("SHIB"), 0.0)
        assertEquals(1e4, s.valueDivisor("vpad"), 0.0)
        assertEquals(1e8, s.valueDivisor("ETH"), 0.0)
        assertEquals(1e8, s.valueDivisor(null), 0.0)
        // Basis/Kotierung aus dem Symbol geschätzt, Einzelabfrage mit bekannten Währungen
        assertEquals("SHIB" to "USDT", s.currencies("sSHIBUSDT", null, null))
        assertEquals("VPAD" to "USDT", s.currencies("sVPADUSDT", "VPAD", "USDT"))
        assertEquals(null to null, s.currencies("sXYZ", null, null))
    }

    private val ticker = """{"error":null,"id":0,"result":[{"lastEp":6200010000000,"symbol":"sBTCUSDT","timestamp":1728540000123456789}]}"""

    @Test
    fun neverGuessesScalesAndPersistsThemWithThePairList() {
        val file = java.io.File.createTempFile("phemex_scales", ".json").apply { delete() }
        try {
            val market = com.cryptochecker.marketdata.model.market.Phemex()
            PhemexScales.store = null
            PhemexScales.current = PhemexScales()
            // Ohne Produktliste: kein Kurs (statt eines um Zehnerpotenzen falschen), Sammelabfrage holt zuerst die Liste
            assertEquals(2, market.bulkTickersNumOfRequests)
            assertEquals("https://api.phemex.com/public/products", market.getBulkTickersUrl(0))
            assertEquals("https://api.phemex.com/md/spot/ticker/24hr/all", market.getBulkTickersUrl(1))
            assertThrows(Exception::class.java) { market.parseBulkTickersDetailed(1, ticker) }
            // Erste Anfrage = Produktliste: Faktoren merken und in der Datei ablegen
            PhemexScales.store = PhemexScales.FileStore(file)
            val products = """{"code":0,"data":$data}"""
            assertTrue(market.parseBulkTickersDetailed(0, products).tickers.isEmpty())
            assertTrue(PhemexScales.current.loaded)
            assertTrue(file.isFile)
            assertEquals(1, market.bulkTickersNumOfRequests)
            assertEquals(62000.1, market.parseBulkTickersDetailed(1, ticker).tickers.getValue("sBTCUSDT").last, 1e-9)
            // «Neustart»: Speicher leer, die Datei liefert dieselben Faktoren
            PhemexScales.current = PhemexScales()
            PhemexScales.store = PhemexScales.FileStore(file)
            val restored = PhemexScales.current
            assertTrue(restored.loaded)
            assertEquals(1e6, restored.priceDivisor("sNEWUSDT")!!, 0.0)
            assertEquals(1e2, restored.valueDivisor("SHIB"), 0.0)
            assertEquals("NEW" to "USDT", restored.currencies("sNEWUSDT", null, null))
            // Kaputte oder fremde Datei: nichts geladen
            assertNull(PhemexScales.decode("{\"v\":99,\"price\":{\"x\":1}}"))
            assertNull(PhemexScales.decode("nonsense"))
        } finally {
            PhemexScales.store = null
            PhemexScales.current = PhemexScales.parse(data)
            file.delete()
        }
    }

    @Test
    fun loadsMissingScalesForSingleTickersAndRefreshesDaily() {
        val market = com.cryptochecker.marketdata.model.market.Phemex()
        val info = com.cryptochecker.marketdata.model.PairQuery(
            "BTC", "USDT", "sBTCUSDT", com.cryptochecker.marketdata.model.FuturesContractType.NONE,
        )
        val products = """{"code":0,"data":$data}"""
        val single = """{"error":null,"id":0,"result":{"lastEp":6200010000000,"symbol":"sBTCUSDT"}}"""
        try {
            PhemexScales.store = null
            PhemexScales.current = PhemexScales()
            // Einzelabfrage ohne Faktoren (weniger Paare als für die Sammelabfrage): erste Anfrage = Produktliste
            assertEquals(2, market.tickerCallCount(info))
            assertEquals("https://api.phemex.com/public/products", market.tickerAddress(0, info))
            val err = assertThrows(Exception::class.java) {
                market.readTicker(0, products, com.cryptochecker.marketdata.model.SimpleTicker(), info)
            }
            assertEquals(PhemexScales.JUST_LOADED, err.message)
            // Danach geladen: nächste Abfrage liefert den Kurs (kein Dauerfehler)
            assertTrue(PhemexScales.current.loaded)
            assertEquals(1, market.tickerCallCount(info))
            assertEquals("https://api.phemex.com/md/spot/ticker/24hr?symbol=sBTCUSDT", market.tickerAddress(0, info))
            assertEquals(62000.1, market.readTicker(0, single, com.cryptochecker.marketdata.model.SimpleTicker(), info).last, 1e-9)

            // Älter als einen Tag: Kurs zuerst, Produktliste als Zusatzanfrage (Einzel- und Sammelabfrage)
            val day = PhemexScales.REFRESH_MILLIS
            PhemexScales.current = PhemexScales.parse(JSONObject(data.toString()), System.currentTimeMillis() - day - 1000)
            assertTrue(PhemexScales.current.isStale())
            assertEquals(2, market.tickerCallCount(info))
            assertEquals("https://api.phemex.com/md/spot/ticker/24hr?symbol=sBTCUSDT", market.tickerAddress(0, info))
            assertEquals("https://api.phemex.com/public/products", market.tickerAddress(1, info))
            val ticker = market.readTicker(0, single, com.cryptochecker.marketdata.model.SimpleTicker(), info)
            market.readTicker(1, products, ticker, info)
            assertEquals(62000.1, ticker.last, 1e-9)
            assertTrue(!PhemexScales.current.isStale())

            PhemexScales.current = PhemexScales.parse(JSONObject(data.toString()), System.currentTimeMillis() - day - 1000)
            assertEquals(2, market.bulkTickersNumOfRequests)
            assertEquals("https://api.phemex.com/md/spot/ticker/24hr/all", market.getBulkTickersUrl(0))
            assertEquals("https://api.phemex.com/public/products", market.getBulkTickersUrl(1))
            // Scheitert die Auffrischung, bleiben die Kurse vollständig (eigene Gruppe)
            assertEquals(0, market.bulkTickersRequestGroup(0))
            assertEquals(1, market.bulkTickersRequestGroup(1))
            assertTrue(market.parseBulkTickersDetailed(1, products).tickers.isEmpty())
            assertEquals(1, market.bulkTickersNumOfRequests)
            // Ältere Datei ohne Zeitpunkt: gilt als veraltet, Faktoren bleiben nutzbar
            val old = PhemexScales.decode("""{"v":1,"price":{"sBTCUSDT":8}}""")!!
            assertTrue(old.loaded && old.isStale())
            assertTrue(!PhemexScales.decode(PhemexScales.current.encode())!!.isStale())
        } finally {
            PhemexScales.store = null
            PhemexScales.current = PhemexScales.parse(data)
        }
    }

    @Test
    fun inactiveBulkRowsAreSkipped() {
        PhemexScales.current = PhemexScales.parse(data)
        val body = """{"result":[{"symbol":"sBTCUSDT","lastEp":6200010000000},{"symbol":"sOLDUSDT","lastEp":0},
            {"symbol":"sNONEUSDT","lastEp":null},{"symbol":"sBADUSDT"}]}"""
        val parsed = com.cryptochecker.marketdata.model.market.Phemex().parseBulkTickersDetailed(0, body)
        // Inaktiv (0/null) still weg; fehlendes Feld bleibt ein Fehler
        assertEquals(setOf("sBTCUSDT"), parsed.tickers.keys)
        assertEquals(setOf("sBADUSDT"), parsed.failed)
    }

    @Test
    fun symbolWithoutPriceScaleIsAnErrorNotAGuess() {
        val market = com.cryptochecker.marketdata.model.market.Phemex()
        try {
            PhemexScales.store = null
            PhemexScales.current = PhemexScales.parse(data)
            // Sammelabfrage: fremdes oder skalenloses Symbol landet in «failed», statt durch 10^8 geteilt zu werden
            // (zwei lesbare Zeilen, sonst gälte die Antwort als Formatfehler: mehr unlesbare als lesbare)
            val body = """{"result":[{"symbol":"sBTCUSDT","lastEp":6200010000000},{"symbol":"sNEWUSDT","lastEp":1500000},
                {"symbol":"sMISSINGUSDT","lastEp":123456},{"symbol":"sNOSCALEUSDT","lastEp":123456}]}"""
            val parsed = market.parseBulkTickersDetailed(0, body)
            assertEquals(setOf("sBTCUSDT", "sNEWUSDT"), parsed.tickers.keys)
            assertEquals(setOf("sMISSINGUSDT", "sNOSCALEUSDT"), parsed.failed)
            // Einzelabfrage: Fehler mit Symbol
            val info = com.cryptochecker.marketdata.model.PairQuery(
                "MISSING", "USDT", "sMISSINGUSDT", com.cryptochecker.marketdata.model.FuturesContractType.NONE,
            )
            val single = """{"error":null,"id":0,"result":{"lastEp":123456,"symbol":"sMISSINGUSDT"}}"""
            val err = assertThrows(Exception::class.java) {
                market.readTicker(0, single, com.cryptochecker.marketdata.model.SimpleTicker(), info)
            }
            assertEquals(PhemexScales.UNKNOWN_SCALE.format("sMISSINGUSDT"), err.message)
        } finally {
            PhemexScales.store = null
            PhemexScales.current = PhemexScales.parse(data)
        }
    }
}
