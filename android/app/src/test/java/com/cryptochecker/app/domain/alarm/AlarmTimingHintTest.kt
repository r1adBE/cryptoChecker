package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.util.StringResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hinweis «Wann Alarme geprüft werden» — wie `testAlarmTimingHint` (iOS, AlarmLogicTests). */
class AlarmTimingHintTest {

    @Test
    fun showsOnceAndNotWithTheFirstAlarmDialog() {
        assertTrue(AlarmTimingHint.shouldShow(alreadyShown = false, firstAlarmDialog = false))
        // Schon gezeigt
        assertFalse(AlarmTimingHint.shouldShow(alreadyShown = true, firstAlarmDialog = false))
        // Bestätigung des ersten Alarms zeigt denselben Satz: nicht zwei Dialoge
        assertFalse(AlarmTimingHint.shouldShow(alreadyShown = false, firstAlarmDialog = true))
    }

    @Test
    fun settingsPathJoinsLevels() {
        assertEquals(
            "Einstellungen › Kurs-Aktualisierung",
            AlarmTimingHint.settingsPath("Einstellungen", " Kurs-Aktualisierung "),
        )
        assertEquals("A › B", AlarmTimingHint.settingsPath("A", "", "B"))
    }

    /**
     * Der Satz ist der ganze Text der Bestätigung nach dem ersten Alarm — in allen 31 Sprachen
     * höchstens [AlarmTimingHint.MAX_CHARS] Zeichen (Android mit eingesetztem Namen «Pünktlich prüfen»),
     * ein Satz und für beide Plattformen vorhanden (iOS liest dieselben Texte).
     */
    @Test
    fun hintIsOneShortSentenceInEveryLanguage() {
        val languages = StringResources.byLanguage()
        assertEquals(31, languages.size)
        for ((folder, strings) in languages) {
            val exact = strings.getValue("settings_exact_checks")
            val android = strings.getValue("alarm_timing_hint_android").replace("%1\$s", exact)
            val ios = strings.getValue("alarm_timing_hint_ios")
            for ((name, text) in listOf("android" to android, "ios" to ios)) {
                assertTrue("$folder $name: ${text.length} Zeichen", text.length <= AlarmTimingHint.MAX_CHARS)
                assertFalse("$folder $name: Platzhalter übrig", text.contains('%'))
            }
        }
    }
}
