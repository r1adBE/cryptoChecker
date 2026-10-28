package com.cryptochecker.app.settings

import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.local.model.WatchEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keine Mitteilungs-Flut beim ersten Start — wie `NotificationDefaultsTests` (iOS). */
class NotificationDefaultsTest {

    @Test
    fun newInstallHasOngoingOff() {
        assertFalse(AppSettings().ongoingNotifications)
    }

    @Test
    fun newPairsStartWithPriceNotification() {
        // Neue Paare zeigen den Kurs in der Mitteilungsleiste
        assertTrue(WatchRepository.DEFAULT_NOTIFICATION_ENABLED)
        // Gespeicherte/gesicherte Paare: Entity-Standard unverändert (kein Schemawechsel)
        assertTrue(WatchEntity(exchangeKey = "Kraken", exchangeName = "Kraken", baseAsset = "BTC", quoteAsset = "EUR").notificationEnabled)
    }
}
