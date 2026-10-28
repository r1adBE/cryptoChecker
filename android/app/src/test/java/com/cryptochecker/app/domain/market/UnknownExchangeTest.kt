package com.cryptochecker.app.domain.market

import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.exchange.UnknownExchange
import com.cryptochecker.marketdata.util.ExchangesConfigUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Platzhalter-Börse für gespeicherte, nicht mehr bekannte Schlüssel; wie `UnknownExchangeTests` (ExchangesTests.swift). */
class UnknownExchangeTest {

    @Test
    fun unknownKeyGivesAnEmptyPlaceholder() {
        val exchange = ExchangesConfigUtils.getExchangeByKey("RemovedExchange")
        assertTrue(exchange is UnknownExchange)
        assertEquals("UnknownExchange", exchange.key)
        // Keine Paare zum Anlegen, keine Abfrage
        assertEquals(true, exchange.currencyPairs?.isEmpty())
        assertEquals("", exchange.tickerAddress(0, PairQuery("BTC", "USD", null, FuturesContractType.NONE)))
    }
}
