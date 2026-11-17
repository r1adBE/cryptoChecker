package com.cryptochecker.app.notification

import com.cryptochecker.app.util.SourceGuard
import com.cryptochecker.resources.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Portfolio-Alarme sind immer neutral — wie iOS `PortfolioAlarmNotice.content()`: keine Zahl des Alarms (Schwelle,
 * Messwert, Prozent) in irgendeinem Feld der Mitteilung, unabhängig von der Portfolio-Sperre und davon, ob das System
 * sensible Inhalte auf dem Sperrbildschirm zeigt. Kursalarme folgen weiter der Sperre (`lockScreenPrivacy`).
 * Gegenstück iOS: `testPortfolioAlarmContentNeverShowsNumbers`, `PortfolioAlarmLockScreenTests`.
 */
class PortfolioAlarmLockScreenTest {

    @Test
    fun inhaltIstNurTitelUndNeutralerSatz() {
        val notice = PortfolioAlarmNotice.content { id -> "text-$id" }
        assertEquals("text-${R.string.notification_portfolio_alarm_title}", notice.title)
        assertEquals("text-${R.string.notification_portfolio_alarm_locked}", notice.text)
        assertEquals(listOf(notice.title, notice.text), notice.fields)
    }

    // Die echte Mitteilung (alle Textfelder, öffentliche Fassung, privat): Gerätetest PortfolioAlarmNoticeDeviceTest
    // ; die Funktion bekommt nur die Kennung des Alarms (Signatur).

    /** Titel und neutraler Satz enthalten in keiner Sprache eine Ziffer oder einen Platzhalter. */
    @Test
    fun neutralerTextOhneZifferUndPlatzhalterInAllenSprachen() {
        val res = SourceGuard.android(main("../../../resources/src/main/res"), "../resources/src/main/res")
        val folders = res.listFiles { f -> f.isDirectory && File(f, "strings_notifications.xml").exists() }.orEmpty()
        assertTrue("${folders.size} Sprachen", folders.size >= 75)
        for (folder in folders) {
            val xml = File(folder, "strings_notifications.xml").readText(Charsets.UTF_8)
            for (name in listOf("notification_portfolio_alarm_title", "notification_portfolio_alarm_locked")) {
                val value = Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                    .find(xml)?.groupValues?.get(1)
                assertTrue("${folder.name}: $name fehlt", !value.isNullOrBlank())
                assertFalse("${folder.name}: $name = $value", value!!.any { it.isDigit() } || value.contains('%'))
            }
        }
    }

    private fun main(rel: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (prefix in listOf("", "app/", "android/app/")) {
                val f = File(dir, "${prefix}src/main/$rel")
                if (f.exists()) return f
            }
            dir = dir.parentFile
        }
        return null
    }
}
