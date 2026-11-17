package com.cryptochecker.app.ui.features.alarms

import com.cryptochecker.app.util.SourceGuard
import com.cryptochecker.resources.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Portfolio-Alarme haben keinen Bearbeiten-Dialog – die Zeile mit ungültiger Schwelle
 * nennt darum «löschen und neu anlegen» (die angebotene Aktion: Löschen-Knopf bzw. Kontextmenü), nicht «anpassen».
 * Beide Plattformen; Text in allen Sprachen.
 */
class PortfolioAlarmInvalidHintTest {

    private fun file(path: String): File? = listOf(File(path), File("app/$path")).firstOrNull { it.exists() }

    /** Text der Zeile als Funktion; iOS `PortfolioAlarmLineTests`. */
    @Test
    fun zeileNenntLoeschenUndNeuAnlegen() {
        assertEquals(R.string.portfolio_alarm_threshold_invalid_recreate, portfolioAlarmLineRes(invalid = true, repeating = true))
        assertEquals(R.string.portfolio_alarm_threshold_invalid_recreate, portfolioAlarmLineRes(invalid = true, repeating = false))
        assertNotEquals(R.string.alarm_threshold_invalid_fix, portfolioAlarmLineRes(invalid = true, repeating = false))
        assertEquals(R.string.alarm_repeating, portfolioAlarmLineRes(invalid = false, repeating = true))
        assertEquals(R.string.alarm_once, portfolioAlarmLineRes(invalid = false, repeating = false))
    }

    @Test
    fun textInAllenSprachen() {
        val res = SourceGuard.android(file("../resources/src/main/res"), "../resources/src/main/res")
        val folders = res.listFiles { f -> f.isDirectory && File(f, "strings_portfolio.xml").exists() }.orEmpty()
        assertTrue(folders.size >= 75)
        for (folder in folders) {
            val xml = File(folder, "strings_portfolio.xml")
            assertTrue("${folder.name}: fehlt", xml.exists() && xml.readText().contains("name=\"portfolio_alarm_threshold_invalid_recreate\""))
        }
    }
}
