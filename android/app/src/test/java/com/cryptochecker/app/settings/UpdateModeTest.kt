package com.cryptochecker.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Wie aktuell?»: Übernahme aus den früheren Schaltern, Sicherung und abgeleitete Schalter — wie iOS
 * `UpdateModeTests`. «Immer kurz» (Vordergrunddienst) ist seit 1.0 entfernt: alte Werte werden zu
 * «Live, solange offen».
 */
class UpdateModeTest {

    @Test
    fun defaultIsLiveWhileOpen() {
        assertEquals(UpdateMode.SPARSAM, UpdateMode.DEFAULT)
        assertEquals(UpdateMode.SPARSAM, AppSettings().updateMode)
    }

    @Test
    fun selectableWithoutImmerKurz() {
        assertEquals(listOf(UpdateMode.SPARSAM, UpdateMode.LIVE_OFFEN), UpdateMode.selectable)
    }

    @Test
    fun legacy_serviceOrWebSocketIsLiveElseSparsam() {
        // «Häufig aktualisieren» an → Live, solange offen (kein Dauerdienst mehr)
        assertEquals(UpdateMode.LIVE_OFFEN, UpdateMode.fromLegacy(true, true, true))
        assertEquals(UpdateMode.LIVE_OFFEN, UpdateMode.fromLegacy(true, null, null))
        assertEquals(UpdateMode.LIVE_OFFEN, UpdateMode.fromLegacy(true, false, true))
        assertEquals(UpdateMode.LIVE_OFFEN, UpdateMode.fromLegacy(false, true, false))
        assertEquals(UpdateMode.LIVE_OFFEN, UpdateMode.fromLegacy(null, true, null))
        // Sonst Sparsam — auch wer die Hintergrund-Aktualisierung aus hatte
        assertEquals(UpdateMode.SPARSAM, UpdateMode.fromLegacy(false, false, true))
        assertEquals(UpdateMode.SPARSAM, UpdateMode.fromLegacy(false, false, false))
        assertEquals(UpdateMode.SPARSAM, UpdateMode.fromLegacy(null, null, false))
        // Nichts gespeichert: nichts zu übernehmen
        assertNull(UpdateMode.fromLegacy(null, null, null))
    }

    @Test
    fun storedImmerKurzReadsAsLive() {
        assertEquals(UpdateMode.LIVE_OFFEN, UpdateMode.fromNameOrNull("IMMER_KURZ"))
        assertEquals(UpdateMode.LIVE_OFFEN, UpdateMode.fromNameOrNull(" immer_kurz "))
        assertEquals(UpdateMode.SPARSAM, UpdateMode.fromNameOrNull("sparsam"))
        assertNull(UpdateMode.fromNameOrNull("TURBO"))
        assertNull(UpdateMode.fromNameOrNull(null))
        assertEquals(UpdateMode.LIVE_OFFEN, UpdateMode.IMMER_KURZ.withoutService())
        assertEquals(UpdateMode.SPARSAM, UpdateMode.SPARSAM.withoutService())
    }

    @Test
    fun migration_rewritesImmerKurzOnce() {
        // Gespeichertes «Immer kurz» wird einmalig als LIVE_OFFEN geschrieben, danach nicht mehr
        assertTrue(UpdateMode.needsMigration("IMMER_KURZ"))
        assertTrue(UpdateMode.needsMigration(null))
        assertTrue(UpdateMode.needsMigration("TURBO"))
        assertFalse(UpdateMode.needsMigration("LIVE_OFFEN"))
        assertFalse(UpdateMode.needsMigration("SPARSAM"))
    }

    @Test
    fun resolve_storedValueWins() {
        assertEquals(UpdateMode.SPARSAM, UpdateMode.resolve("SPARSAM", true, true, true, existingInstall = true))
        assertEquals(UpdateMode.LIVE_OFFEN, UpdateMode.resolve(" immer_kurz ", null, null, null, existingInstall = true))
        assertEquals(UpdateMode.LIVE_OFFEN, UpdateMode.resolve("IMMER_KURZ", false, false, true, existingInstall = false))
    }

    @Test
    fun resolve_unknownStoredFallsBackToLegacyOrDefault() {
        assertEquals(UpdateMode.LIVE_OFFEN, UpdateMode.resolve("TURBO", true, false, true, existingInstall = true))
        assertEquals(UpdateMode.SPARSAM, UpdateMode.resolve(null, null, null, null, existingInstall = false))
    }

    @Test
    fun resolve_existingInstallWithoutLegacyKeysKeepsOldBehaviour() {
        // Android speichert nur geänderte Schlüssel: bestehende Installation ohne die Schalter = ab Werk
        // (Hintergrund an, Live-Kurse aus) → Sparsam; neue Installation → Standard
        assertEquals(UpdateMode.SPARSAM, UpdateMode.resolve(null, null, null, null, existingInstall = true))
        assertEquals(UpdateMode.SPARSAM, UpdateMode.resolve("", null, null, null, existingInstall = false))
    }

    @Test
    fun backup_newKeyThenLegacyElseUnchanged() {
        assertEquals(UpdateMode.SPARSAM, UpdateMode.fromBackup("SPARSAM", true, true, true))
        // Ältere Sicherung mit «Immer kurz» bzw. «Häufig aktualisieren» → Live, solange offen
        assertEquals(UpdateMode.LIVE_OFFEN, UpdateMode.fromBackup("IMMER_KURZ", true, true, true))
        assertEquals(UpdateMode.LIVE_OFFEN, UpdateMode.fromBackup(null, true, false, true))
        assertEquals(UpdateMode.LIVE_OFFEN, UpdateMode.fromBackup("unbekannt", false, true, null))
        assertNull(UpdateMode.fromBackup(null, null, null, null))
    }

    @Test
    fun legacyFlags_roundTripForOlderVersions() {
        UpdateMode.entries.forEach { mode ->
            val f = mode.legacyFlags
            assertTrue(f.backgroundUpdates)
            // «Häufig aktualisieren» wird nie mehr als an geschrieben
            assertFalse(f.liveService)
            // Ältere Version liest die Schalter, diese Version übernimmt sie wieder zur selben Wahl
            assertEquals(mode.withoutService(), UpdateMode.fromLegacy(f.liveService, f.liveWebSocket, f.backgroundUpdates))
        }
        assertFalse(UpdateMode.SPARSAM.legacyFlags.liveWebSocket)
        assertTrue(UpdateMode.LIVE_OFFEN.legacyFlags.liveWebSocket)
    }

    @Test
    fun derivedSwitches() {
        assertFalse(AppSettings(updateMode = UpdateMode.SPARSAM).liveWebSocket)
        assertTrue(AppSettings(updateMode = UpdateMode.LIVE_OFFEN).liveWebSocket)
    }

    /** «Kurs vorlesen» wirkt nur mit Sprachausgabe an und «Nur Alarme» aus (Standard: beides nicht). */
    @Test
    fun speaksPairPricesNeedsSpeechWithoutAlarmsOnly() {
        assertFalse(AppSettings().speaksPairPrices)
        assertFalse(AppSettings(ttsEnabled = true, ttsAlarmsOnly = true).speaksPairPrices)
        assertFalse(AppSettings(ttsEnabled = false, ttsAlarmsOnly = false).speaksPairPrices)
        assertTrue(AppSettings(ttsEnabled = true, ttsAlarmsOnly = false).speaksPairPrices)
    }
}
