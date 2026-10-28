package com.cryptochecker.app.data.remote.candles

import com.cryptochecker.app.data.remote.CandleInterval
import com.cryptochecker.app.domain.activity.HourCandle
import com.cryptochecker.marketdata.model.FuturesContractType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Zeit- und Kennungs-Helfer der eigenen Kerzen ([CandleTime]) und die Grenzfälle der OKX-Kerzen ([OkxCandles]):
 * unterstützte Paare, Wochenkerzen, Fehlercode, unlesbare Antwort, zweite Seite ohne Antwort.
 */
class CandleTimeOkxTest {

    private val hour = CandleTime.HOUR

    private fun pair(
        marketKey: String,
        pairId: String?,
        contract: FuturesContractType = FuturesContractType.NONE,
        base: String = "BTC",
        quote: String = "USDT",
    ) = CandlePair(marketKey, marketKey, base, quote, pairId, contract)

    /** Kerzenquelle mit festen Antworten je Adresse; merkt sich die Anfragen. */
    private class Http(private val replies: Map<String, String>) : CandleHttp {
        val requested = mutableListOf<String>()
        override suspend fun get(url: String): String? {
            requested += url
            return replies[url]
        }
    }

    private val isoFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    // ---------------- CandleTime ----------------

    @Test
    fun alignedAndWindowStartUseTheUtcGrid() {
        assertEquals(3 * hour, CandleTime.aligned(3 * hour + 59_999L, hour))
        // Vor 1970: abrunden, nicht Richtung null
        assertEquals(-hour, CandleTime.aligned(-1L, hour))
        assertEquals(8 * hour, CandleTime.windowStart(10 * hour + 5L, hour, 3))
        assertEquals(10 * hour, CandleTime.windowStart(10 * hour + 5L, hour, 1))
        assertEquals(10 * hour, CandleTime.windowStart(10 * hour + 5L, hour, 0))
    }

    @Test
    fun pairIdAcceptsOnlySafeCharacters() {
        val bitfinex = pair("Bitfinex", "tBTC:USD", base = "BTC", quote = "USD")
        assertEquals("tBTC:USD", CandleTime.pairId(bitfinex, ":"))
        assertNull("«:» nur, wenn erlaubt", CandleTime.pairId(bitfinex))
        assertEquals("BTC-USDT", CandleTime.pairId(pair("Kucoin", " BTC-USDT ")))
        assertEquals("BTC_USDT", CandleTime.pairId(pair("GateIo", "BTC_USDT")))
        assertNull(CandleTime.pairId(pair("GateIo", "BTC/USDT")))
        assertNull("nur ASCII", CandleTime.pairId(pair("GateIo", "ÄBC")))
        assertNull(CandleTime.pairId(pair("GateIo", "  ")))
        assertNull(CandleTime.pairId(pair("GateIo", null)))
    }

    @Test
    fun parseIsoReadsUtcTimesWithAndWithoutMillis() {
        assertEquals(Instant.parse("2025-10-14T00:00:00Z").toEpochMilli(), CandleTime.parseIso("2025-10-14T00:00:00"))
        assertEquals(Instant.parse("2025-10-14T13:30:00.500Z").toEpochMilli(), CandleTime.parseIso("2025-10-14T13:30:00.5Z"))
        // Leerzeichen statt «T», mehr als drei Nachkommastellen: auf Millisekunden gekürzt
        assertEquals(Instant.parse("2025-10-14T13:30:00.123Z").toEpochMilli(), CandleTime.parseIso(" 2025-10-14 13:30:00.123456 "))
        assertEquals(Instant.parse("2024-02-29T23:59:59Z").toEpochMilli(), CandleTime.parseIso("2024-02-29T23:59:59Z"))
        assertEquals(-1000L, CandleTime.parseIso("1969-12-31T23:59:59"))
        assertNull(CandleTime.parseIso(null))
        assertNull(CandleTime.parseIso(""))
        assertNull(CandleTime.parseIso("2025-10-14"))
        assertNull(CandleTime.parseIso("14.10.2025 13:30:00"))
    }

    @Test
    fun formatIsoMatchesJavaTimeAndRoundTrips() {
        assertEquals("1970-01-01T00:00:00.000Z", CandleTime.formatIso(0L))
        assertEquals("1969-12-31T23:59:59.999Z", CandleTime.formatIso(-1L))
        val samples = listOf(
            Instant.parse("2024-02-29T12:34:56.789Z").toEpochMilli(),
            Instant.parse("2000-03-01T00:00:00Z").toEpochMilli(),
            Instant.parse("2026-12-31T23:59:59.001Z").toEpochMilli(),
            Instant.parse("1900-03-01T06:07:08.009Z").toEpochMilli(),
        )
        for (millis in samples) {
            val text = CandleTime.formatIso(millis)
            assertEquals(isoFormat.format(Instant.ofEpochMilli(millis)), text)
            assertEquals(millis, CandleTime.parseIso(text))
        }
    }

    @Test
    fun fourHoursDropsTheCutFirstBlock() {
        fun h(i: Int) = HourCandle(i * hour, open = 10.0 + i, high = 20.0 + i, low = 5.0 + i, close = 11.0 + i, volume = 1.0, quoteVolume = 2.0)
        assertNull(CandleTime.fourHours(null))
        // Stunden 3 … 7, ungeordnet: Block 0–4 h hat nur eine Kerze und fällt weg
        val four = CandleTime.fourHours(listOf(h(7), h(3), h(5), h(4), h(6)))!!
        assertEquals(1, four.size)
        val c = four.single()
        assertEquals(4 * hour, c.openTime)
        assertEquals(14.0, c.open, 0.0)
        assertEquals(27.0, c.high, 0.0)
        assertEquals(9.0, c.low, 0.0)
        assertEquals(18.0, c.close, 0.0)
        assertEquals(4.0, c.volume, 0.0)
        assertEquals(8.0, c.quoteVolume!!, 0.0)
    }

    @Test
    fun rawCountAndBaseFromQuote() {
        assertEquals(44, CandleTime.rawCount(CandleInterval.H4, 10))
        assertEquals(10, CandleTime.rawCount(CandleInterval.H1, 10))
        assertEquals(10, CandleTime.rawCount(CandleInterval.D1, 10))
        assertEquals(25.0, CandleTime.baseFromQuote(100.0, 4.0), 0.0)
        assertTrue(CandleTime.baseFromQuote(-1.0, 4.0).isNaN())
        assertTrue(CandleTime.baseFromQuote(100.0, 0.0).isNaN())
        assertTrue(CandleTime.baseFromQuote(Double.NaN, 4.0).isNaN())
        assertTrue(CandleTime.baseFromQuote(100.0, Double.POSITIVE_INFINITY).isNaN())
    }

    // ---------------- OKX ----------------

    @Test
    fun okxSupportsSpotAndPerpetualsOnly() {
        assertTrue(OkxCandles.supports(pair("Okex", null)))
        assertTrue(OkxCandles.supports(pair("OkexFutures", null, FuturesContractType.PERPETUAL)))
        assertTrue(OkxCandles.supports(pair("OkexFutures", "BTC-USD-SWAP", FuturesContractType.INVERSE_PERPETUAL)))
        assertFalse(OkxCandles.supports(pair("OkexFutures", null)))
        assertFalse(OkxCandles.supports(pair("OkexFutures", "BTC-USDT-250627", FuturesContractType.QUARTERLY)))
        // Kennung muss zur Art passen: Spot nie «-SWAP», Perpetual immer
        assertFalse(OkxCandles.supports(pair("Okex", "BTC-USDT-SWAP")))
        assertFalse(OkxCandles.supports(pair("OkexFutures", "BTC-USDT", FuturesContractType.PERPETUAL)))
        assertFalse(OkxCandles.supports(pair("Okex", null, FuturesContractType.QUARTERLY)))
        assertFalse("unsichere Kennung", OkxCandles.supports(pair("Okex", "BTC USDT")))
        assertFalse("Kürzel nicht ASCII", OkxCandles.supports(pair("Okex", null, base = "BTC€")))
    }

    @Test
    fun okxWithoutUsableAnswerHasNoCandles() = runBlocking {
        val spot = pair("Okex", null)
        val weekly = Http(emptyMap())
        assertNull(OkxCandles.fetch(spot, CandleInterval.W1, 10, weekly))
        assertTrue("Wochenkerzen ohne Anfrage", weekly.requested.isEmpty())

        val daily = "https://www.okx.com/api/v5/market/candles?instId=BTC-USDT&bar=1Dutc&limit=2"
        val errorCode = Http(mapOf(daily to """{"code":"51001","msg":"Instrument ID does not exist","data":[]}"""))
        assertNull(OkxCandles.fetch(spot, CandleInterval.D1, 2, errorCode))
        assertEquals(listOf(daily), errorCode.requested)

        assertNull(OkxCandles.fetch(spot, CandleInterval.D1, 2, Http(mapOf(daily to "<html>Bad Gateway</html>"))))

        val hourly = Http(emptyMap())
        assertNull(OkxCandles.fetch(spot, CandleInterval.H1, 2, hourly))
        assertEquals(listOf("https://www.okx.com/api/v5/market/candles?instId=BTC-USDT&bar=1H&limit=2"), hourly.requested)

        val unsupported = Http(emptyMap())
        assertNull(OkxCandles.fetch(pair("Okex", "BTC-USDT-SWAP"), CandleInterval.H1, 2, unsupported))
        assertTrue(unsupported.requested.isEmpty())
    }

    @Test
    fun okxKeepsTheFirstPageWhenTheOlderPageFails() = runBlocking {
        val day = CandleInterval.D1.millis
        val newest = 20_000L * day
        // 300 Tageskerzen, neueste zuerst (wie OKX)
        val rows = (0 until 300).joinToString(",") { i ->
            """["${newest - i * day}","100","110","90","105","2","200","210","1"]"""
        }
        val url = "https://www.okx.com/api/v5/market/candles?instId=BTC-USDT&bar=1Dutc"
        val http = Http(mapOf("$url&limit=300" to """{"code":"0","msg":"","data":[$rows]}"""))
        val candles = OkxCandles.fetch(pair("Okex", null), CandleInterval.D1, 302, http)!!
        assertEquals(300, candles.size)
        assertEquals(newest - 299 * day, candles.first().openTime)
        assertEquals(newest, candles.last().openTime)
        assertEquals(listOf("$url&limit=300", "$url&after=${newest - 299 * day}&limit=2"), http.requested)
    }

    @Test
    fun okxParseVolumeDependsOnContract() {
        val json = """{"code":"0","msg":"","data":["kaputt",["1700000000000","100","110","90","105","7","3","300","1"]]}"""
        val spot = OkxCandles.parse(json, swap = false)!!.single()
        assertEquals(7.0, spot.volume, 0.0)
        assertEquals(300.0, spot.quoteVolume!!, 0.0)
        // Swap: «vol» zählt Kontrakte, Menge aus «volCcy»
        assertEquals(3.0, OkxCandles.parse(json, swap = true)!!.single().volume, 0.0)
        assertNull(OkxCandles.parse("""{"code":"0"}""", swap = false))
        assertNull(OkxCandles.parse("""{"code":"50011","msg":"Too Many Requests"}""", swap = false))
        assertTrue(OkxCandles.isRateLimited("""{"code":"50011","msg":"Too Many Requests"}"""))
        assertFalse(OkxCandles.isRateLimited("""{"code":"0","msg":"","data":[]}"""))
    }
}
