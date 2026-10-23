package com.cryptochecker.app.ui.features.alarms

import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Review FUN4-R2: Portfolio-Alarme haben keinen Bearbeiten-Dialog – die Zeile mit ungültiger Schwelle
 * nennt darum «löschen und neu anlegen» (die angebotene Aktion: Löschen-Knopf bzw. Kontextmenü), nicht «anpassen».
 * Beide Plattformen; Text in allen Sprachen.
 */
class PortfolioAlarmInvalidHintTest {

    private fun file(path: String): File? = listOf(File(path), File("app/$path")).firstOrNull { it.exists() }

    @Test
    fun androidPortfolioZeileNenntLoeschenUndNeuAnlegen() {
        val text = SourceGuard.android(
            file("src/main/java/com/cryptochecker/app/ui/features/alarms/AlarmsOverviewScreen.kt"), "AlarmsOverviewScreen.kt"
        ).readText()
        val portfolio = text.substringAfter("PortfolioAlarmLogic.exceedsFall(alarm.kind, alarm.threshold)")
        assertTrue(portfolio.contains("R.string.portfolio_alarm_threshold_invalid_recreate"))
        assertFalse(portfolio.contains("R.string.alarm_threshold_invalid_fix"))
        // Die genannte Aktion gibt es in der Zeile
        assertTrue(portfolio.contains("onDelete(alarm)"))
    }

    @Test
    fun iosPortfolioZeileNenntLoeschenUndNeuAnlegen() {
        val path = "../../ios/App/Features/Alarms/AlarmsOverviewScreen.swift"
        val text = SourceGuard.ios(
            listOf(File(path), File(path.removePrefix("../"))).firstOrNull { it.exists() }, "ios/…/AlarmsOverviewScreen.swift"
        ).readText().substringAfter("private func portfolioCard(")
        assertTrue(text.contains("\"portfolio_alarm_threshold_invalid_recreate\""))
        assertFalse(text.contains("\"alarm_threshold_invalid_fix\""))
        assertTrue(text.contains("deletePortfolio = alarm"))
    }

    @Test
    fun textInAllenSprachen() {
        val res = SourceGuard.android(file("src/main/res"), "src/main/res")
        val folders = res.listFiles { f -> f.isDirectory && File(f, "strings_r104_g.xml").exists() }.orEmpty()
        assertTrue(folders.size >= 31)
        for (folder in folders) {
            val xml = File(folder, "strings_r106_b.xml")
            assertTrue("${folder.name}: fehlt", xml.exists() && xml.readText().contains("name=\"portfolio_alarm_threshold_invalid_recreate\""))
        }
    }
}
