package com.cryptochecker.app.notification

import com.cryptochecker.app.R
import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Portfolio-Alarme sind immer neutral — wie iOS `PortfolioAlarmNotice.content()`: keine Zahl des Alarms (Schwelle,
 * Messwert, Prozent) in irgendeinem Feld der Mitteilung, unabhängig von der Portfolio-Sperre und davon, ob das System
 * sensible Inhalte auf dem Sperrbildschirm zeigt. Kursalarme folgen weiter der Sperre (`lockScreenPrivacy`).
 * Gegenstück iOS: `testPortfolioAlarmContentNeverShowsNumbers`, `PortfolioAlarmLockScreenTests`.
 */
class PortfolioAlarmLockScreenTest {

    private val source: String by lazy {
        val rel = "java/com/cryptochecker/app/notification/AppNotifierAlarms.kt"
        SourceGuard.android(main(rel), rel).readText(Charsets.UTF_8)
    }

    /** Rumpf von `showPortfolioAlarm` bis zur nächsten Funktion. */
    private val portfolio: String by lazy {
        source.substringAfter("suspend fun AppNotifier.showPortfolioAlarm(").substringBefore("\n}\n")
    }

    @Test
    fun inhaltIstNurTitelUndNeutralerSatz() {
        val notice = PortfolioAlarmNotice.content { id -> "text-$id" }
        assertEquals("text-${R.string.notification_portfolio_alarm_title}", notice.title)
        assertEquals("text-${R.string.notification_portfolio_alarm_locked}", notice.text)
        assertEquals(listOf(notice.title, notice.text), notice.fields)
    }

    /** Die Funktion bekommt nur die Kennung: Schwelle, Messwert und Währung können gar nicht in die Mitteilung. */
    @Test
    fun mitteilungBekommtKeineZahlDesAlarms() {
        assertTrue(source.contains("suspend fun AppNotifier.showPortfolioAlarm(alarmId: Long): Boolean {"))
        for (forbidden in listOf(
            "PortfolioAlarmTexts.", "notification_portfolio_alarm_text", "settings.appLock", "measured", "threshold",
            "usdtAssumed", "hidePortfolioAmounts", "PriceFormat", "setTicker", "setSubText", "setContentInfo",
            "contentDescription", "addLine", "setSummaryText",
        )) {
            assertFalse(forbidden, portfolio.contains(forbidden))
        }
        // Jedes Textfeld kommt aus PortfolioAlarmNotice (Titel bzw. neutraler Satz), auch die öffentliche Fassung
        assertTrue(portfolio.contains("val notice = PortfolioAlarmNotice.content { context.getString(it) }"))
        assertTrue(portfolio.contains("val title = notice.title\n    val body = notice.text\n"))
        assertEquals(2, Regex("""\.setContentTitle\(title\)""").findAll(portfolio).count())
        assertEquals(2, Regex("""\.setContentText\(body\)""").findAll(portfolio).count())
        assertEquals(1, Regex("""\.bigText\(body\)""").findAll(portfolio).count())
        assertEquals(5, Regex("""\.(setContentTitle|setContentText|bigText|setTicker|setSubText)\(""").findAll(portfolio).count())
        // Der Aufrufer reicht nur die Kennung weiter
        val rel = "java/com/cryptochecker/app/data/portfolio/PortfolioAlarms.kt"
        val caller = SourceGuard.android(main(rel), rel).readText(Charsets.UTF_8)
        assertTrue(caller.contains("notifier.showPortfolioAlarm(alarm.id)"))
    }

    /** Titel und neutraler Satz enthalten in keiner Sprache eine Ziffer oder einen Platzhalter. */
    @Test
    fun neutralerTextOhneZifferUndPlatzhalterInAllenSprachen() {
        val res = SourceGuard.android(main("res"), "src/main/res")
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

    @Test
    fun zusaetzlichPrivatUndKursalarmeUnveraendert() {
        assertTrue(portfolio.contains("    builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)"))
        assertFalse(portfolio.contains("lockScreenPrivacy("))
        // Kursalarme unverändert: nur mit Sperre privat
        assertTrue(source.substringBefore("suspend fun AppNotifier.showPortfolioAlarm(").contains("builder.lockScreenPrivacy("))
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
