package com.cryptochecker.app.ui

import com.cryptochecker.app.util.SourceGuard
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kleine Ehrlichkeits-Regeln der Oberfläche, beide Plattformen:
 *  - Vergleich: die feste Spalte des gewählten Zeitraums entfällt (nie zweimal «7T» mit zwei Werten).
 *  - Store-Build: 7 Tipps auf die Version zählen und melden nichts (es gibt keine Entwickleroptionen).
 *  - iOS-Sprachausgabe: Einstellungsseite und Meldung «Kurs vorlesen» sagen «nur bei offener App».
 *  - Kurs-Overlay: der Hinweis sagt, dass es nach dem Ende der App mit der nächsten Aktualisierung zurückkommt
 *    (es startet mit jedem Prozessstart, also auch mit dem nächsten Hintergrund-Lauf).
 */
class UiHonestyGuardTest {

    private fun source(path: String): String =
        listOf(File(path), File("app/$path")).first { it.exists() }.readText()

    private fun ios(path: String): String {
        val rel = "../../ios/$path"
        return SourceGuard.ios(listOf(File(rel), File(rel.removePrefix("../"))).firstOrNull { it.exists() }, "ios/$path").readText()
    }

    @Test
    fun vergleichOhneDoppelteSpalte() {
        val android = source("src/main/java/com/cryptochecker/app/ui/features/watchlist/CompareScreen.kt")
        assertTrue(android.contains(".filterIndexed { i, (label, _) -> i == 0 || label != rangeShort }"))
        val swift = ios("App/Features/Watchlist/CompareScreen.swift")
        assertTrue(swift.contains("let values = all.enumerated().filter { \$0.offset == 0 || \$0.element.0 != rangeLabel }.map(\\.element)"))
    }

    @Test
    fun entwicklerFreischaltungNurImDebugBuild() {
        val android = source("src/main/java/com/cryptochecker/app/ui/features/settings/SettingsAboutPages.kt")
        assertTrue(android.contains("}.takeIf { BuildConfig.DEBUG }"))
        val swift = ios("App/Features/Settings/SettingsAboutPages.swift")
        assertTrue(swift.contains("AboutContent(onVersionTap: versionTap)"))
        assertTrue(swift.contains("guard DeveloperOptions.available else { return nil }"))
        assertFalse(swift.contains("AboutContent(onVersionTap: versionTapped)"))
    }

    @Test
    fun iosSprachausgabeNurBeiOffenerApp() {
        assertTrue(ios("App/Features/Settings/SettingsAlertsSpeechPages.swift")
            .contains("subtitle: L(\"settings_tts_hint_silent\") + \" \" + L(\"alarm_speak_ios_open_only\"),"))
        assertTrue(ios("App/Features/Watchlist/PairEdits.swift")
            .contains("ToastMessage(L(\"watchlist_tts_speech_on\") + \" \" + L(\"alarm_speak_ios_open_only\"))"))
    }

    @Test
    fun overlayKommtMitDemNaechstenProzessstartZurueck() {
        val app = source("src/main/java/com/cryptochecker/app/CryptoCheckerApp.kt")
        val onCreate = app.substringAfter("override fun onCreate()")
        assertTrue(onCreate.contains("priceOverlay.get().start()"))
        val row = source("src/main/java/com/cryptochecker/app/ui/features/settings/SettingsPriceOverlay.kt")
        assertTrue(row.contains("stringResource(R.string.settings_price_overlay_restart_hint)"))
    }
}
