package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.domain.exceptions.toMarketFailure
import com.cryptochecker.app.domain.refresh.MarketFailure
import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.market.Hyperliquid
import com.cryptochecker.marketdata.model.market.Lighter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Kein Eintrag für das Paar» in einer gültigen Antwort («No data», Hyperliquid «Unknown coin») bestätigt nach dem
 * Fehlen in der vollständigen Liste «nicht mehr gehandelt»; Lighter ohne gespeicherte Kennung sucht das richtige
 * Symbol statt des leeren. Wie `MissingEntryNotTradedTests.swift`; Textfälle in `not_traded.json`.
 */
class MissingEntryNotTradedTest {

    @Test
    fun missingEntryIsAStatementAboutThePair() {
        val noData = MarketParseException(MarketParseException.NO_DATA)
        val unknownCoin = MarketParseException("Unknown coin: FOO")
        assertEquals(MarketFailure.UnknownInstrument, noData.toMarketFailure())
        assertEquals(MarketFailure.UnknownInstrument, unknownCoin.toMarketFailure())
        assertTrue(NotTraded.confirmedBySingle(false, noData.message, noData.toMarketFailure()))
        assertTrue(NotTraded.confirmedBySingle(false, unknownCoin.message, unknownCoin.toMarketFailure()))
        // Gespeicherter Text ohne Art: gleich
        assertTrue(NotTraded.confirmedBySingle(false, "No data"))
        assertTrue(NotTraded.confirmedBySingle(false, "Unknown coin: FOO"))
    }

    @Test
    fun otherParseErrorsStayErrors() {
        val other = MarketParseException("No data for field last")
        assertEquals(MarketFailure.Parse, other.toMarketFailure())
        assertFalse(NotTraded.confirmedBySingle(false, other.message, other.toMarketFailure()))
        assertFalse(NotTraded.isMissingEntry("no data available"))
        assertFalse(NotTraded.isMissingEntry(null))
        // Kurs da: nie
        assertFalse(NotTraded.confirmedBySingle(true, "No data", MarketFailure.UnknownInstrument))
    }

    @Test
    fun lighterWithoutPairIdDerivesTheSymbol() {
        val body = """{"code":200,"order_book_stats":[
            {"symbol":"BTC","last_trade_price":67000.5,"daily_price_change":1.2},
            {"symbol":"ETH/USDC","last_trade_price":3200.25,"daily_price_change":-0.4}
        ]}"""
        val perp = PairQuery("BTC", "USDC", null, FuturesContractType.PERPETUAL)
        assertEquals(67000.5, Lighter().readTicker(0, body, SimpleTicker(), perp).last, 0.0)
        val spot = PairQuery("ETH", "USDC", null, FuturesContractType.NONE)
        assertEquals(3200.25, Lighter().readTicker(0, body, SimpleTicker(), spot).last, 0.0)
        // Gespeicherte Kennung hat Vorrang
        val stored = PairQuery("X", "USDC", "BTC", FuturesContractType.NONE)
        assertEquals(67000.5, Lighter().readTicker(0, body, SimpleTicker(), stored).last, 0.0)
        // Wirklich fehlendes Symbol bleibt «unbekannt» (bestätigt nach dem Fehlen in der Liste)
        val gone = PairQuery("GONE", "USDC", null, FuturesContractType.PERPETUAL)
        val err = assertThrows(MarketParseException::class.java) { Lighter().readTicker(0, body, SimpleTicker(), gone) }
        assertEquals("Unknown symbol: GONE", err.message)
    }

    @Test
    fun hyperliquidValidAnswerHasNoErrorText() {
        val info = PairQuery("FOO", "USDC", "FOO", FuturesContractType.PERPETUAL)
        // Gültige Antwort (Liste): kein Fehlertext, damit «Unknown coin» bleibt
        val err = assertThrows(MarketParseException::class.java) {
            Hyperliquid().readError(0, """ [{"universe":[]},[]]""", info)
        }
        assertEquals("No error text", err.message)
        // Fehler als blosser Text: dessen Anfang
        assertEquals("Failed to deserialize", Hyperliquid().readError(0, "Failed to deserialize", info))
    }
}
