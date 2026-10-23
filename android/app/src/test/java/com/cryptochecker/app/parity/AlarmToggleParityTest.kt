package com.cryptochecker.app.parity

import com.cryptochecker.app.data.AlarmJson
import com.cryptochecker.app.data.local.model.AlarmCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Review FUN3-02/UX3-06: «fällt um ≥ 100 %» lässt sich per Schalter nicht einschalten
 * (`testdata/parity/alarm_toggle.json`); Gegenstück: `AlarmToggleParityTests.swift`.
 */
class AlarmToggleParityTest {

    private val data = ParityJson.load("alarm_toggle.json")

    @Test
    fun toggleMatchesFixture() {
        val cases = data["cases"].list()
        assertTrue(cases.size >= 5)
        cases.forEach { raw ->
            val c = raw.obj()
            assertEquals(
                c["name"] as String, c["allowed"],
                AlarmJson.toggleAllowed(
                    AlarmCondition.valueOf(c["condition"] as String), (c["threshold"] as Number).toDouble(),
                    enable = c["enable"] as Boolean,
                ),
            )
        }
    }

    /** Schalter (Karte, Übersicht) und Schreibweg nutzen dieselbe Regel. */
    @Test
    fun switchesAndWritePathUseTheRule() {
        fun source(path: String) = listOf(File(path), File("app/$path")).first { it.exists() }.readText()
        val base = "src/main/java/com/cryptochecker/app"
        assertTrue(source("$base/ui/features/alarms/AlarmCard.kt").contains("enabled = AlarmJson.toggleAllowed("))
        val overview = source("$base/ui/features/alarms/AlarmsOverviewScreen.kt")
        assertTrue(overview.contains("AlarmJson.toggleAllowed(") && overview.contains("enabled = canToggle"))
        val repo = source("$base/data/WatchRepository.kt").substringAfter("suspend fun setAlarmEnabled(")
        assertTrue(repo.substringBefore("watchDao.setAlarmEnabled(").contains("AlarmJson.toggleAllowed("))
    }
}
