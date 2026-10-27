package com.cryptochecker.app.overlay

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Preis-Overlay: Paare wie die dauerhafte Kurs-Mitteilung, Einrasten am Rand, «×»-Bereich. */
class PriceOverlayRulesTest {

    private val on = AppSettings(priceOverlay = true, ongoingNotifications = true, priceNotifications = true)

    private fun watch(id: Long, notify: Boolean = true, disabled: Boolean = false, price: Double? = 1.0) =
        WatchEntity(id = id, marketKey = "binance", marketName = "Binance", baseAsset = "C$id", quoteAsset = "USDT",
            notificationEnabled = notify, disabled = disabled, lastPrice = price)

    @Test
    fun pairsFollowTheOngoingNotification() {
        val watches = listOf(watch(1), watch(2, notify = false), watch(3, disabled = true), watch(4, price = null),
            watch(5), watch(6), watch(7))
        assertEquals(listOf(1L, 5L, 6L), PriceOverlayRules.pairs(watches, on, canDraw = true).map { it.id })
        assertTrue(PriceOverlayRules.pairs(watches, on, canDraw = false).isEmpty())
        assertTrue(PriceOverlayRules.pairs(watches, on.copy(ongoingNotifications = false), canDraw = true).isEmpty())
        assertTrue(PriceOverlayRules.pairs(watches, on.copy(priceNotifications = false), canDraw = true).isEmpty())
        assertTrue(PriceOverlayRules.pairs(watches, on.copy(priceOverlay = false), canDraw = true).isEmpty())
    }

    @Test
    fun snapsToTheNearerEdge() {
        assertEquals(0, PriceOverlayRules.snapX(100, 200, 1080))
        assertEquals(880, PriceOverlayRules.snapX(700, 200, 1080))
        assertEquals(500, PriceOverlayRules.clampY(500, 100, 2000))
        assertEquals(1900, PriceOverlayRules.clampY(2500, 100, 2000))
        assertEquals(0, PriceOverlayRules.clampY(-20, 100, 2000))
    }

    @Test
    fun closeZoneAtTheBottomCenter() {
        assertTrue(PriceOverlayRules.inCloseZone(540, 1950, 1080, 2000, 200))
        assertFalse(PriceOverlayRules.inCloseZone(100, 1950, 1080, 2000, 200))
        assertFalse(PriceOverlayRules.inCloseZone(540, 1000, 1080, 2000, 200))
    }
}
