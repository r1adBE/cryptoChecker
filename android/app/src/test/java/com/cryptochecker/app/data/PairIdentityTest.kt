package com.cryptochecker.app.data

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.marketdata.model.FuturesContractType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Paar eines Eintrags, mit dem Kurse gespeichert werden ([PriceWrite.pair]): genau die Spalten, die
 * `WatchDao.updatePriceForPair` vergleicht — «Paar bearbeiten» (gleiche Id) ergibt ein anderes Paar.
 */
class PairIdentityTest {

    private val btc = WatchEntity(
        id = 7, marketKey = "Binance", marketName = "Binance", baseAsset = "BTC", quoteAsset = "USDT",
        contractType = FuturesContractType.NONE, pairId = "BTCUSDT",
    )

    @Test
    fun identityUsesTheStoredColumns() {
        assertEquals(PairIdentity("Binance", "BTC", "USDT", "NONE", "BTCUSDT"), btc.pairIdentity)
        // Kurs, Zeit, Gruppe … gehören nicht dazu
        assertEquals(btc.pairIdentity, btc.copy(lastPrice = 1.0, groupName = "A", lastUpdate = 5).pairIdentity)
    }

    @Test
    fun editedPairIsADifferentIdentity() {
        val edited = btc.copy(baseAsset = "ETH", pairId = "ETHUSDT")
        assertNotEquals(btc.pairIdentity, edited.pairIdentity)
        assertNotEquals(btc.pairIdentity, btc.copy(marketKey = "Bybit").pairIdentity)
        assertNotEquals(btc.pairIdentity, btc.copy(contractType = FuturesContractType.PERPETUAL).pairIdentity)
        // Kennung null ist ein eigener Wert (SQL vergleicht mit IS)
        assertNotEquals(btc.pairIdentity, btc.copy(pairId = null).pairIdentity)
    }
}
