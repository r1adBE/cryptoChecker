package com.cryptochecker.app.domain.market

import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.market.UnknownMarket
import com.cryptochecker.marketdata.util.MarketsConfigUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Platzhalter-Börse für gespeicherte, nicht mehr bekannte Schlüssel; wie `UnknownMarketTests` (MarketsTests.swift). */
class UnknownMarketTest {

    @Test
    fun unknownKeyGivesAnEmptyPlaceholder() {
        val market = MarketsConfigUtils.getMarketByKey("RemovedExchange")
        assertTrue(market is UnknownMarket)
        assertEquals("UnknownMarket", market.key)
        // Keine Paare zum Anlegen, keine Abfrage
        assertEquals(true, market.currencyPairs?.isEmpty())
        assertEquals("", market.tickerAddress(0, PairQuery("BTC", "USD", null, FuturesContractType.NONE)))
    }
}
