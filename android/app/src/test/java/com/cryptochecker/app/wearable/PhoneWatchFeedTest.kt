package com.cryptochecker.app.wearable

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.watch.WatchPhoneQuotes
import com.cryptochecker.marketdata.watch.WatchSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Kurse für die Uhr aus der Merkliste ([PhoneWatchFeed.quotesJson]): nur Spot gegen USDT (keine Futures, keine
 * DEX-Pools mit Kennung), beide Listen der Uhr, nichts bei «Immer selbst abrufen». Wie iOS `PhoneWatchLinkTests`.
 */
class PhoneWatchFeedTest {

    private fun watch(base: String, quote: String = "USDT", price: Double = 10.0, time: Long = 1_000L,
                      contract: FuturesContractType = FuturesContractType.NONE, pairId: String? = null) =
        WatchEntity(exchangeKey = "binance", exchangeName = "Binance", baseAsset = base, quoteAsset = quote,
            contractType = contract, pairId = pairId, lastPrice = price, lastUpdate = time, change24h = 1.5)

    @Test
    fun spotUsdtOfBothLists() {
        val watches = listOf(
            watch("BTC", price = 67_000.0),
            watch("ETH", contract = FuturesContractType.PERPETUAL, price = 3_000.0),
            watch("SOL", pairId = "solana:pool", price = 150.0),
            watch("ADA", price = 0.5),
        )
        val settings = WatchSettings(mode = WatchSettings.Mode.OWN, own = listOf("BTC", "ETH", "SOL"), phone = listOf("ADA"))
        val quotes = WatchPhoneQuotes.fromJson(PhoneWatchFeed.quotesJson(watches, settings))
        assertEquals(listOf("BTC", "ADA"), quotes?.map { it.symbol })
        assertEquals(67_000.0, quotes?.first()?.price)
        assertEquals("Binance", quotes?.first()?.exchange)
    }

    @Test
    fun nothingWhenTheWatchAlwaysFetchesItself() {
        val settings = WatchSettings(source = WatchSettings.Source.SELF)
        assertNull(PhoneWatchFeed.quotesJson(listOf(watch("BTC")), settings))
    }
}
