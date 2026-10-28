package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.CacheCodec
import com.cryptochecker.app.domain.alarm.PortfolioAlarmInput
import com.cryptochecker.app.domain.alarm.PortfolioAlarmKind
import com.cryptochecker.app.domain.portfolio.HistoricCloseChain
import com.cryptochecker.app.domain.portfolio.PortfolioDayRange
import com.cryptochecker.app.domain.portfolio.PortfolioHistory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Speicherformat der Tageskerzen des Wertverlaufs ([PortfolioHistoryCodecs]), Hilfen der Ausweich-Kette
 * ([chainProvider], [chainCandlePair], [withChain]) und Eingabe des Portfolio-Alarms ([toInput]).
 */
class PortfolioHistoryCodecsTest {

    private fun <T> roundTrip(codec: CacheCodec<T>, value: T): T = codec.decode(JSONObject(codec.encode(value).toString()))

    @Test
    fun closesRoundTripWithRanges() {
        val entry = CoinCloses(
            closes = mapOf(20_001L to 101.5, 20_000L to 100.0, 20_002L to 99.25),
            span = 730,
            ranges = mapOf(20_000L to PortfolioDayRange(95.0, 104.0, 94.5), 20_002L to PortfolioDayRange(101.5, 102.0, 98.0)),
        )
        assertEquals(entry, roundTrip(PortfolioHistoryCodecs.closes, entry))
    }

    @Test
    fun closesAreWrittenSortedWithoutNonFiniteDays() {
        val entry = CoinCloses(closes = mapOf(3L to 3.0, 1L to 1.0, 2L to Double.NaN), span = 10)
        val days = PortfolioHistoryCodecs.closes.encode(entry).getJSONArray("days")
        assertEquals(2, days.length())
        assertEquals(1L, days.getJSONArray(0).getLong(0))
        assertEquals(3L, days.getJSONArray(1).getLong(0))
        // Ohne Eröffnung/Hoch/Tief nur Tag und Schluss
        assertEquals(2, days.getJSONArray(0).length())
        assertEquals(mapOf(1L to 1.0, 3L to 3.0), roundTrip(PortfolioHistoryCodecs.closes, entry).closes)
    }

    @Test
    fun invalidRangesAreDroppedOnRead() {
        val entry = CoinCloses(
            closes = mapOf(1L to 10.0, 2L to 20.0),
            span = 5,
            ranges = mapOf(1L to PortfolioDayRange(0.0, 11.0, 9.0), 2L to PortfolioDayRange(19.0, 21.0, 18.0)),
        )
        val back = roundTrip(PortfolioHistoryCodecs.closes, entry)
        assertEquals(mapOf(1L to 10.0, 2L to 20.0), back.closes)
        // Eröffnung 0 ist ungültig: Kerze ohne Docht statt falscher Werte
        assertEquals(mapOf(2L to PortfolioDayRange(19.0, 21.0, 18.0)), back.ranges)
    }

    @Test
    fun olderEntriesWithoutSpanUseTheYear() {
        val days = JSONArray().put(JSONArray().put(7L).put(1.5)).put(JSONArray().put(8L).put(2.5).put(2.0).put(3.0))
        val decoded = PortfolioHistoryCodecs.closes.decode(JSONObject().put("days", days))
        assertEquals(PortfolioHistory.MAX_DAYS, decoded.span)
        assertEquals(mapOf(7L to 1.5, 8L to 2.5), decoded.closes)
        // Zeile mit vier Werten ist unvollständig: keine Spanne
        assertTrue(decoded.ranges.isEmpty())
        assertNull(decoded.staleSince)
    }

    @Test
    fun emptyEntryMeansNoPair() {
        val empty = CoinCloses(emptyMap(), span = 366)
        assertEquals(empty, roundTrip(PortfolioHistoryCodecs.closes, empty))
        assertThrows(Exception::class.java) { PortfolioHistoryCodecs.closes.decode(JSONObject()) }
    }

    @Test
    fun ownCodecKeepsTheRoute() {
        val value = OwnCloses(
            route = "Kraken|XBTUSD|NONE",
            entry = CoinCloses(mapOf(5L to 60_000.5), span = 400, ranges = mapOf(5L to PortfolioDayRange(59_000.0, 61_000.0, 58_500.0))),
        )
        assertEquals(value, roundTrip(PortfolioHistoryCodecs.own, value))
        // Ohne Weg nicht lesbar (gilt als nicht gemerkt)
        val withoutRoute = PortfolioHistoryCodecs.closes.encode(value.entry)
        assertThrows(Exception::class.java) { PortfolioHistoryCodecs.own.decode(withoutRoute) }
    }

    @Test
    fun chainProviderComesFromTheRoute() {
        assertEquals("Kraken", chainProvider("Kraken|XBTUSD|NONE"))
        // Schlüssel «Okex» heisst in der App «OKX»
        assertEquals("OKX", chainProvider("${HistoricCloseChain.OKX}|BTC-USDT|NONE"))
        assertEquals("Bybit", chainProvider("Bybit"))
    }

    @Test
    fun chainCandlePairTreatsUsdcAsUsdt() {
        val pair = chainCandlePair(HistoricCloseChain.COINBASE, "BTC", HistoricCloseChain.ChainPair("USDC", "BTC-USDC"))
        assertEquals(HistoricCloseChain.COINBASE, pair.marketKey)
        assertEquals("Coinbase", pair.marketName)
        assertEquals("BTC", pair.base)
        assertEquals("USDT", pair.quote)
        assertEquals("BTC-USDC", pair.pairId)
        val okx = chainCandlePair(HistoricCloseChain.OKX, "ETH", HistoricCloseChain.ChainPair("USDT", "ETH-USDT"))
        assertEquals("OKX", okx.marketName)
        assertEquals("USDT", okx.quote)
        val kraken = chainCandlePair(HistoricCloseChain.KRAKEN, "BTC", HistoricCloseChain.ChainPair("USD", "XBTUSD"))
        assertEquals("USD", kraken.quote)
        assertEquals("Kraken|XBTUSD|NONE", kraken.cacheKey)
    }

    @Test
    fun withChainNamesOnlyCoinsInTheResult() {
        val prices = PortfolioDailyPrices(closes = mapOf("ABC" to mapOf(1L to 1.0)))
        assertSame(prices, prices.withChain(emptyMap()))
        val chain = mapOf(
            "ABC" to OwnCloses("Okex|ABC-USDT|NONE", CoinCloses(mapOf(1L to 1.0), 366)),
            "XYZ" to OwnCloses("Kraken|XYZUSD|NONE", CoinCloses(mapOf(1L to 2.0), 366)),
        )
        val named = prices.withChain(chain)
        assertEquals(mapOf("ABC" to "OKX"), named.chainSources)
        assertEquals(prices.closes, named.closes)
    }

    @Test
    fun alarmEntityToInput() {
        val entity = PortfolioAlarmEntity(
            id = 3,
            kind = PortfolioAlarmKind.VALUE_ABOVE,
            threshold = 50_000.0,
            currency = "CHF",
            enabled = false,
            repeating = true,
            referenceAt = 11L,
            lastTriggeredAt = 12L,
            lastTriggeredValue = 51_000.0,
        )
        assertEquals(
            PortfolioAlarmInput(
                kind = PortfolioAlarmKind.VALUE_ABOVE,
                threshold = 50_000.0,
                currency = "CHF",
                enabled = false,
                referenceAt = 11L,
                lastTriggeredAt = 12L,
            ),
            entity.toInput(),
        )
        val defaults = PortfolioAlarmEntity(kind = PortfolioAlarmKind.CHANGE_UP, threshold = 5.0).toInput()
        assertTrue(defaults.enabled)
        assertNull(defaults.currency)
        assertEquals(0L, defaults.referenceAt)
    }
}
