package com.cryptochecker.app.widget

import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.marketdata.model.FuturesContractType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Gemerkte Zeilen der Listen-Widgets ([ListWidgetSnapshotCodec]): was Kopf und Zeilen brauchen, ohne Notiz. */
class ListWidgetSnapshotCodecTest {
    private val full = WatchEntity(
        id = 7, exchangeKey = "binance_futures", exchangeName = "Binance Futures", baseAsset = "BTC", quoteAsset = "USDT",
        contractType = FuturesContractType.PERPETUAL, pairId = "BTCUSDT", sortOrder = 3, notificationEnabled = false,
        lastPrice = 62_000.5, previousPrice = 61_000.0, lastUpdate = 1_700_000_000_000, lastError = null, favorite = true,
        groupName = "Main", note = "privat", change24h = -1.25, disabled = false, priceChangedAt = 1_699_999_000_000,
        exchangeTime = 1_699_999_990_000,
    )

    @Test
    fun roundTripKeepsWhatTheWidgetShows() {
        val back = ListWidgetSnapshotCodec.decode(ListWidgetSnapshotCodec.encode(listOf(full)))!!.single()
        // Die Notiz wird nicht gemerkt
        assertEquals(full.copy(note = null), back)
    }

    @Test
    fun missingValuesStayMissing() {
        val bare = WatchEntity(exchangeKey = "kraken", exchangeName = "Kraken", baseAsset = "ETH", quoteAsset = "EUR",
            change24h = Double.NaN)
        val back = ListWidgetSnapshotCodec.decode(ListWidgetSnapshotCodec.encode(listOf(bare)))!!.single()
        assertEquals(bare.copy(change24h = null), back)
    }

    @Test
    fun brokenEntryShowsNothing() {
        assertNull(ListWidgetSnapshotCodec.decode("{"))
        assertNull(ListWidgetSnapshotCodec.decode("""[{"id":1}]"""))
        assertEquals(emptyList<WatchEntity>(), ListWidgetSnapshotCodec.decode("[]"))
    }
}
