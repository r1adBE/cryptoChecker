package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.ui.features.alarms.AlarmCreateGuard
import com.cryptochecker.app.util.SourceGuard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Doppeltipp beim Anlegen eines Portfolio-Alarms: nur ein INSERT (seit Build 99, [AlarmCreateGuard] wie bei den
 * Paar-Alarmen). iOS: `PortfolioAlarmSheet` mit `AlarmCreateGuard`.
 */
class PortfolioAlarmCreateGuardTest {

    private fun source(path: String): String =
        listOf(File(path), File("app/$path")).first { it.exists() }.readText()

    @Test
    fun zweimalSpeichernWaehrendDasAnlegenLaeuftGibtEinenAlarm() = runBlocking {
        val guard = AlarmCreateGuard()
        val release = CompletableDeferred<Unit>()
        var inserts = 0
        // Wie addAlarm: jeder Tipp «Speichern» startet über den Wächter (angelegt wird immer neu)
        repeat(2) { guard.launch(this, guarded = true) { release.await(); inserts++ } }
        release.complete(Unit)
        while (guard.busy) kotlinx.coroutines.yield()
        assertEquals(1, inserts)
    }

    @Test
    fun addAlarmLaeuftUeberDenWaechter() {
        val text = source("src/main/java/com/cryptochecker/app/ui/features/portfolio/PortfolioEditActions.kt")
        val body = text.substringAfter("fun PortfolioViewModel.addAlarm(").substringBefore("\n}\n")
        assertTrue(body.contains("alarmCreateGuard.launch(viewModelScope, guarded = true)"))
        assertTrue(!body.contains("viewModelScope.launch"))
    }

    @Test
    fun iosBlattHatDenWaechter() {
        val path = "../../ios/App/Features/Portfolio/PortfolioInsightsViews.swift"
        val file = SourceGuard.ios(listOf(File(path), File(path.removePrefix("../"))).firstOrNull { it.exists() }, "ios/…/PortfolioInsightsViews.swift")
        val text = file.readText().substringAfter("struct PortfolioAlarmSheet")
        assertTrue(text.contains("createGuard.begin(creating: true)"))
    }
}
