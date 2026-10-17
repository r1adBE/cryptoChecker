package com.cryptochecker.app.settings

import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.local.model.WatchEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keine Mitteilungs-Flut beim ersten Start (Build 42) — wie `NotificationDefaultsTests` (iOS). */
class NotificationDefaultsTest {

    @Test
    fun newInstallHasOngoingOff() {
        assertFalse(AppSettings().ongoingNotifications)
        assertFalse(AppSettings.ongoingForMigration(existingInstall = false))
    }

    /** Bestehende Installation ohne gespeicherten Schlüssel behält den bisherigen Standard (an). */
    @Test
    fun existingInstallKeepsOngoingOn() {
        assertTrue(AppSettings.LEGACY_ONGOING_NOTIFICATIONS)
        assertTrue(AppSettings.ongoingForMigration(existingInstall = true))
    }

    @Test
    fun newPairsStartWithPriceNotification() {
        // Entscheid 16. Oktober 2026: neue Paare zeigen den Kurs in der Mitteilungsleiste (vorher aus)
        assertTrue(WatchRepository.DEFAULT_NOTIFICATION_ENABLED)
        // Sicherung ohne Feld (bis Build 41 war jedes Paar an): bleibt an — wie iOS
        assertTrue(WatchRepository.LEGACY_NOTIFICATION_ENABLED)
        // Gespeicherte/gesicherte Paare: Entity-Standard unverändert (kein Schemawechsel)
        assertTrue(WatchEntity(marketKey = "Kraken", marketName = "Kraken", baseAsset = "BTC", quoteAsset = "EUR").notificationEnabled)
    }
}
