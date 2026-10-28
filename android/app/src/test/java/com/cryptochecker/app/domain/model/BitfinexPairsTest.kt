package com.cryptochecker.app.domain.model

import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.exchange.Bitfinex
import org.junit.Assert.assertEquals
import org.junit.Test

/** Bitfinex-Alt-Kürzel werden übersetzt (Suche «ATOM» findet «ATOUSD»), die Kennung bleibt. Wie iOS `BitfinexPairsTests`. */
class BitfinexPairsTest {

    @Test
    fun legacyCodesAreTranslated() {
        val body = """[["BTCUST","ATOUSD","DSHBTC","IOTUSD","WBTUSD","ALGUSD","QTMUSD","YYWUSD","DATUSD","MNAUSD","AMPUSD",
            "TSDUSD","BTCUDC","XCHUSD","MATIC:USD","TESTBTC:TESTUSD","BTCUSD"]]"""
        val pairs = ArrayList<ExchangePair>()
        Bitfinex().readPairList(0, body, pairs)
        assertEquals(
            listOf(
                "BTC/USDT", "ATOM/USD", "DASH/BTC", "IOTA/USD", "WBTC/USD", "ALGO/USD", "QTUM/USD", "YOYOW/USD",
                "DATA/USD", "MANA/USD", "AMPL/USD", "TUSD/USD", "BTC/USDC", "XCH/USD", "MATIC/USD", "BTC/USD",
            ),
            pairs.map { "${it.base}/${it.quote}" },
        )
        assertEquals("tATOUSD", pairs[1].pairId)
        assertEquals("tMATIC:USD", pairs[14].pairId)
    }
}
