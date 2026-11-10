package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.settings.SettingsConfirm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rückfragen in Einstellungen › Portfolio (`testdata/parity/settings_confirm.json`). Gegenstück: `SettingsConfirmParityTests.swift`. */
class SettingsConfirmParityTest {

    private val data = ParityJson.load("settings_confirm.json")

    @Test
    fun einstandsmethodeWieGemeinsameTestfaelle() {
        val cases = data["costMethod"].list()
        assertTrue(cases.size >= 5)
        for (raw in cases) {
            val c = raw.obj()
            val current = CostMethod.valueOf(c["current"] as String)
            val chosen = CostMethod.valueOf(c["chosen"] as String)
            assertEquals("$current → $chosen", c["expected"] as Boolean, SettingsConfirm.costMethod(current, chosen))
        }
    }

    @Test
    fun systemsicherungWieGemeinsameTestfaelle() {
        for (raw in data["systemBackup"].list()) {
            val c = raw.obj()
            assertEquals(c["expected"] as Boolean, SettingsConfirm.systemBackup(c["enabling"] as Boolean))
        }
    }
}
