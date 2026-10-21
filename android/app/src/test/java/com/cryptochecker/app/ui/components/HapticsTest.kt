package com.cryptochecker.app.ui.components

import android.view.HapticFeedbackConstants
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.domain.portfolio.AddressValidation
import com.cryptochecker.app.ui.features.portfolio.addWatchedHaptic
import com.cryptochecker.app.ui.features.settings.BackupMessage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Haptik-Katalog: Ereignis → `HapticFeedbackConstants` (gleiche Tabelle wie iOS `HapticsTests`). Die Konstanten
 * sind Compile-Zeit-Zahlen; das SDK wird übergeben, damit beide Zweige (API 30+ und 26–29) ohne Gerät laufen.
 */
class HapticsTest {

    @Test
    fun `ab API 30 Bestätigen und Ablehnen`() {
        val expected = mapOf(
            HapticEvent.SELECTION to HapticFeedbackConstants.CLOCK_TICK,
            HapticEvent.LIGHT_IMPACT to HapticFeedbackConstants.KEYBOARD_TAP,
            HapticEvent.LONG_PRESS to HapticFeedbackConstants.LONG_PRESS,
            HapticEvent.SUCCESS to HapticFeedbackConstants.CONFIRM,
            HapticEvent.WARNING to HapticFeedbackConstants.REJECT,
            HapticEvent.ERROR to HapticFeedbackConstants.REJECT,
            HapticEvent.DESTRUCTIVE to HapticFeedbackConstants.LONG_PRESS,
        )
        assertEquals(HapticEvent.entries.toSet(), expected.keys)
        for ((event, constant) in expected) assertEquals(event.name, constant, Haptics.constantFor(event, sdk = 30))
    }

    @Test
    fun `API 26 bis 29 mit Ersatz für Bestätigen und Ablehnen`() {
        for (sdk in 26..29) {
            assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, Haptics.constantFor(HapticEvent.SUCCESS, sdk))
            assertEquals(HapticFeedbackConstants.LONG_PRESS, Haptics.constantFor(HapticEvent.WARNING, sdk))
            assertEquals(HapticFeedbackConstants.LONG_PRESS, Haptics.constantFor(HapticEvent.ERROR, sdk))
            // Ohne Ersatz nötig: gleich wie ab API 30
            assertEquals(HapticFeedbackConstants.CLOCK_TICK, Haptics.constantFor(HapticEvent.SELECTION, sdk))
            assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, Haptics.constantFor(HapticEvent.LIGHT_IMPACT, sdk))
            assertEquals(HapticFeedbackConstants.LONG_PRESS, Haptics.constantFor(HapticEvent.DESTRUCTIVE, sdk))
        }
    }

    @Test
    fun `Sicherung fertig ist Erfolg, alles andere Fehler`() {
        assertEquals(HapticEvent.SUCCESS, BackupMessage.haptic(BackupMessage.Exported))
        assertEquals(HapticEvent.SUCCESS, BackupMessage.haptic(BackupMessage.Restored(watches = 3, alarms = 1)))
        listOf(
            BackupMessage.Failed, BackupMessage.TooLarge, BackupMessage.ExportTooLarge, BackupMessage.NewerVersion,
            BackupMessage.NotVerified, BackupMessage.NotEnoughMemory, BackupMessage.StorageKept, BackupMessage.StorageInMemory,
        ).forEach { assertEquals(it.toString(), HapticEvent.ERROR, BackupMessage.haptic(it)) }
    }

    @Test
    fun `Adresse hinzufügen wie iOS`() {
        assertEquals(HapticEvent.SUCCESS, addWatchedHaptic(PortfolioRepository.AddWatchedResult.Added(7)))
        assertEquals(HapticEvent.WARNING, addWatchedHaptic(PortfolioRepository.AddWatchedResult.Duplicate))
        assertEquals(HapticEvent.WARNING, addWatchedHaptic(PortfolioRepository.AddWatchedResult.LimitReached))
        assertEquals(
            HapticEvent.WARNING,
            addWatchedHaptic(PortfolioRepository.AddWatchedResult.Invalid(AddressValidation.Problem.entries.first())),
        )
        assertEquals(HapticEvent.ERROR, addWatchedHaptic(null))
    }
}
