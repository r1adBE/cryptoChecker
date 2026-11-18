package com.cryptochecker.app.data.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Einträge im Zwischenspeicher: nur Buchstaben und Ziffern im Namen, damit kein Coin-Kürzel einen Pfad bildet. */
class PortfolioHistorySourceNamesTest {
    @Test
    fun assetNamesAreShortAndAlphanumeric() {
        assertTrue(PortfolioHistorySource.isAsset("BTC"))
        assertTrue(PortfolioHistorySource.isAsset("1000SATS"))
        assertFalse(PortfolioHistorySource.isAsset(""))
        assertFalse(PortfolioHistorySource.isAsset("../ETC"))
        assertFalse(PortfolioHistorySource.isAsset("A".repeat(16)))
    }

    @Test
    fun cacheNamesDropEverythingButLettersAndDigits() {
        assertEquals("phist_BTC", PortfolioHistorySource.cacheName("B/T.C"))
        assertEquals("phisto_ETH", PortfolioHistorySource.ownCacheName("ETH"))
        assertEquals("phistc_SOL", PortfolioHistorySource.chainCacheName("../SOL"))
    }

    @Test
    fun usdtUsdPairsAreKrakenAndCoinbase() {
        assertEquals("USDT", PortfolioHistorySource.USDT_USD_PAIR.base)
        assertEquals("USD", PortfolioHistorySource.USDT_USD_PAIR.quote)
        assertEquals("USDT-USD", PortfolioHistorySource.USDT_USD_FILL_PAIR.pairId)
    }
}
