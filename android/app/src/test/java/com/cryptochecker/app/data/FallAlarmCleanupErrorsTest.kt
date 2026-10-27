package com.cryptochecker.app.data

import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F-25: Die einmalige Bereinigung «fällt um ≥ 100 %» (Paar- und Portfolio-Alarme) zählt gefangene Fehler
 * im Bereich «alarms» ([com.cryptochecker.app.domain.diagnostics.BackgroundErrors.ALARMS]) — ausser «Schlüssel gerade
 * nicht lesbar» (kein Fehler, nur Hinweis; wie `appExceptionHandler`). Room läuft nur auf dem Gerät, darum am Quelltext.
 * iOS: `disableInvalidFallAlarmsOnce` wirft nicht (nicht lesbare Portfolio-Datei = Schlüssel fehlt, nicht gezählt).
 */
class FallAlarmCleanupErrorsTest {

    @Test
    fun cleanupFailuresAreCounted() {
        for (name in listOf("FallAlarmCleanup.kt", "portfolio/PortfolioFallAlarmCleanup.kt")) {
            val path = "src/main/java/com/cryptochecker/app/data/$name"
            val text = SourceGuard.android(listOf(File(path), File("app/$path"), File("../data/$path"), File("data/$path"), File("../domain/$path"), File("domain/$path")).firstOrNull { it.isFile }, path).readText()
            val failure = text.substringAfter("}.onFailure {", "").substringBefore("\n            }")
            assertTrue(name, failure.contains("if (!it.isDatabaseKeyUnavailable()) BackgroundErrorCounter.record(BackgroundErrors.ALARMS, error = it)"))
        }
    }
}
