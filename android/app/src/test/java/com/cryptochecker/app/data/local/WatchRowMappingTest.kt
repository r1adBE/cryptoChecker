package com.cryptochecker.app.data.local

import com.cryptochecker.app.domain.model.AlarmCondition
import com.cryptochecker.app.domain.model.AlarmEntity
import com.cryptochecker.app.data.local.model.AlarmWithWatchRow
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.data.local.model.toDomain
import com.cryptochecker.app.data.local.model.toRow
import com.cryptochecker.marketdata.model.FuturesContractType
import org.junit.Assert.assertEquals
import org.junit.Test

/** Tabellenzeile (Room, :data) und Fachmodell (:domain) tragen dieselben Werte: hin und zurück geht nichts verloren. */
class WatchRowMappingTest {

    private val watch = WatchEntity(
        id = 7, exchangeKey = "kraken", exchangeName = "Kraken", baseAsset = "BTC", quoteAsset = "CHF",
        contractType = FuturesContractType.PERPETUAL, pairId = "XBTCHF", sortOrder = 3, notificationEnabled = false,
        ttsEnabled = true, lastPrice = 101.5, previousPrice = 99.25, lastUpdate = 1_700_000_000_000,
        notifiedPrice = 100.0, notifiedAt = 1_699_999_000_000, lastError = "offline", favorite = true,
        groupName = "Gruppe", note = "Notiz", change24h = -1.25, disabled = true,
        priceChangedAt = 1_699_999_500_000, exchangeTime = 1_699_999_900_000,
    )

    private val alarm = AlarmEntity(
        id = 9, watchId = 7, condition = AlarmCondition.MOVE_PERCENT_WINDOW, threshold = 2.5, enabled = false,
        repeating = true, sound = false, vibrate = false, speak = true, referencePrice = 98.0,
        lastTriggeredAt = 1_699_000_000_000, lastTriggeredPrice = 97.0, windowHours = 4,
        referenceAt = 1_698_000_000_000, currency = "CHF",
    )

    @Test
    fun watchRoundTripKeepsEveryValue() {
        assertEquals(watch, watch.toRow().toDomain())
        // Standardwerte des Fachmodells (leere Felder) ebenso
        val plain = WatchEntity(exchangeKey = "binance", exchangeName = "Binance", baseAsset = "ETH", quoteAsset = "USDT")
        assertEquals(plain, plain.toRow().toDomain())
    }

    @Test
    fun alarmRoundTripKeepsEveryValue() {
        assertEquals(alarm, alarm.toRow().toDomain())
        val plain = AlarmEntity(watchId = 1, condition = AlarmCondition.PRICE_ABOVE, threshold = 1.0)
        assertEquals(plain, plain.toRow().toDomain())
    }

    @Test
    fun alarmWithWatchMapsBothParts() {
        val mapped = AlarmWithWatchRow(alarm = alarm.toRow(), watch = watch.toRow()).toDomain()
        assertEquals(alarm, mapped.alarm)
        assertEquals(watch, mapped.watch)
    }
}
