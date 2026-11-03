package com.cryptochecker.app.wearable

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.watch.WatchPhoneQuotes
import com.cryptochecker.marketdata.watch.WatchSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Kurse für die Uhr aus der Merkliste ([PhoneWatchFeed.quotesJson]): Kürzel nur Spot gegen USDT (keine Futures, keine
 * DEX-Pools mit Kennung), beide Listen der Uhr; Favoriten mit Paar genau dieses Paar; nichts bei «Immer selbst
 * abrufen». Wie iOS `PhoneWatchLinkTests`.
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

    /**
     * «Wie Handy»: die Uhr bekommt das Paar des Favoriten (Binance Futures, Perp) samt dessen Kurs — nicht einen
     * Spot-Kurs einer anderen Börse. Wie iOS `testSourceFollowsThePhonesPair`.
     */
    @Test
    fun sourceFollowsThePhonesPair() {
        val bank = watch("BANK", price = 0.0712, contract = FuturesContractType.PERPETUAL)
            .copy(exchangeName = "Binance Futures", favorite = true)
        val spot = watch("BANK", price = 0.07).copy(exchangeName = "Bybit")
        val eur = watch("BTC", quote = "EUR", price = 60_000.0).copy(exchangeName = "Kraken", favorite = true, sortOrder = 1)
        val (phone, pairs) = PhoneWatchSync.favorites(listOf(bank, spot, eur))
        assertEquals(listOf("BANK", "BTC"), phone)
        assertEquals(listOf("BANK/USDT|Binance Futures|Perp", "BTC/EUR|Kraken|"), pairs.map { it.key })
        assertEquals(listOf("BANK Perp", "BTC/EUR"), pairs.map { it.label })
        val settings = WatchSettings(mode = WatchSettings.Mode.PHONE, own = listOf("BANK"), phone = phone, pairs = pairs)
        val quotes = WatchPhoneQuotes.fromJson(PhoneWatchFeed.quotesJson(listOf(bank, spot, eur), settings))!!
        assertEquals(listOf("BANK", "BANK/USDT|Binance Futures|Perp", "BTC/EUR|Kraken|"), quotes.map { it.symbol })
        assertEquals(listOf("Bybit", "Binance Futures", "Kraken"), quotes.map { it.exchange })
        assertEquals(listOf(0.07, 0.0712, 60_000.0), quotes.map { it.price })
    }

    @Test
    fun nothingWhenTheWatchAlwaysFetchesItself() {
        val settings = WatchSettings(source = WatchSettings.Source.SELF)
        assertNull(PhoneWatchFeed.quotesJson(listOf(watch("BTC")), settings))
    }
}
