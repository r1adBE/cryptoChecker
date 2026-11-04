package com.cryptochecker.app.wearable

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.watch.WatchPair
import com.cryptochecker.marketdata.watch.WatchPhoneQuotes
import com.cryptochecker.marketdata.watch.WatchSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kurse für die Uhr aus der Merkliste ([PhoneWatchFeed.quotesJson]): Kürzel nur Spot gegen USDT (keine Futures, keine
 * DEX-Pools), beide Listen der Uhr; Favoriten mit Paar genau dieses Paar; nichts bei «Immer selbst abrufen». Die
 * Kennung einer Börse («BTCUSDT», «XBTEUR») macht kein Pool — Pool nur bei DexScreener, mit der Kennung im Schlüssel.
 * Wie iOS `PhoneWatchLinkTests`.
 */
class PhoneWatchFeedTest {

    /** Wie die Merkliste speichert: Börsen-Paare mit ihrer Kennung (Binance «BTCUSDT»), DexScreener «chain/address». */
    private fun watch(base: String, quote: String = "USDT", price: Double = 10.0, time: Long = 1_000L,
                      contract: FuturesContractType = FuturesContractType.NONE, exchange: String = "Binance",
                      pairId: String? = "$base$quote") =
        WatchEntity(exchangeKey = exchange, exchangeName = exchange, baseAsset = base, quoteAsset = quote,
            contractType = contract, pairId = pairId, lastPrice = price, lastUpdate = time, change24h = 1.5)

    @Test
    fun spotUsdtOfBothLists() {
        val watches = listOf(
            watch("BTC", price = 67_000.0),
            watch("ETH", contract = FuturesContractType.PERPETUAL, price = 3_000.0),
            watch("SOL", exchange = "DexScreener", pairId = "solana/8sLbNZoA1cfnvMJLPfp98ZLAnFSYCFApfJKMbiXNLwxj", price = 150.0),
            watch("ADA", price = 0.5),
        )
        val settings = WatchSettings(mode = WatchSettings.Mode.OWN, own = listOf("BTC", "ETH", "SOL"), phone = listOf("ADA"))
        val quotes = WatchPhoneQuotes.fromJson(PhoneWatchFeed.quotesJson(watches, settings))
        assertEquals(listOf("BTC", "ADA"), quotes?.map { it.symbol })
        assertEquals(67_000.0, quotes?.first()?.price)
        assertEquals("Binance", quotes?.first()?.exchange)
    }

    /** Binance-Spot mit Kennung «BTCUSDT»: Spot, Schlüssel ohne «Pool», die Uhr darf ihn selbst abfragen. */
    @Test
    fun exchangePairIdStaysSpot() {
        val btc = watch("BTC", price = 67_000.0).copy(favorite = true)
        assertEquals("", PhoneWatchFeed.watchContract(btc))
        val (_, pairs) = PhoneWatchSync.favorites(listOf(btc))
        assertEquals(listOf("BTC/USDT|Binance|"), pairs.map { it.key })
        assertEquals(listOf("BTC"), pairs.map { it.label })
        assertTrue(pairs.single().selfFetchable)
    }

    /** Zwei DexScreener-Pools mit gleicher Basis/Quote: eigene Schlüssel, die Uhr bekommt den Kurs des markierten. */
    @Test
    fun twoPoolsStayApart() {
        val marked = watch("PEPE", exchange = "DexScreener", pairId = "ethereum/0xa43fe16908251ee70ef74718545e4fe6c5ccec9f",
            price = 4e-9).copy(favorite = true)
        val other = watch("PEPE", exchange = "DexScreener", pairId = "ethereum/0x11950d141ecb863f01007add7d1a342041227b58",
            price = 5e-9, time = 2_000L)
        val (_, pairs) = PhoneWatchSync.favorites(listOf(marked, other))
        assertEquals(listOf("PEPE/USDT|DexScreener|Pool995b79ee"), pairs.map { it.key })
        assertEquals("PEPE/USDT|DexScreener|Pool0275864c", WatchPair.of("PEPE", "USDT", "DexScreener",
            PhoneWatchFeed.watchContract(other))?.key)
        val settings = WatchSettings(mode = WatchSettings.Mode.PHONE, phone = listOf("PEPE"), pairs = pairs)
        val quotes = WatchPhoneQuotes.fromJson(PhoneWatchFeed.quotesJson(listOf(marked, other), settings))!!
        assertEquals(listOf("PEPE/USDT|DexScreener|Pool995b79ee"), quotes.map { it.symbol })
        assertEquals(listOf(4e-9), quotes.map { it.price })
    }

    /**
     * «Wie Handy»: die Uhr bekommt das Paar des Favoriten (Binance Futures, Perp) samt dessen Kurs — nicht einen
     * Spot-Kurs einer anderen Börse. Wie iOS `testSourceFollowsThePhonesPair`.
     */
    @Test
    fun sourceFollowsThePhonesPair() {
        val bank = watch("BANK", price = 0.0712, contract = FuturesContractType.PERPETUAL, exchange = "Binance Futures")
            .copy(favorite = true)
        val spot = watch("BANK", price = 0.07, exchange = "Bybit")
        val eur = watch("BTC", quote = "EUR", price = 60_000.0, exchange = "Kraken", pairId = "XBTEUR")
            .copy(favorite = true, sortOrder = 1)
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
