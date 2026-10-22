package com.cryptochecker.app.parity

import com.cryptochecker.app.data.AlarmJson
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Paar-Alarme aus der Sicherung (`testdata/parity/backup_alarms.json`); Gegenstück: `BackupAlarmsParityTests.swift`. */
class BackupAlarmsParityTest {

    /** Rohes JSON (mit «NaN»/«Infinity» als Text) über org.json, wie es die App liest. */
    private val cases: JSONArray = JSONObject(File(ParityJson.dir(), "backup_alarms.json").readText()).getJSONArray("cases")

    @Test
    fun alarmsMatchFixture() {
        assertTrue(cases.length() >= 6)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val alarm = AlarmJson.fromJson(c.getJSONObject("alarm"))
            assertEquals(c.getString("name"), c.getBoolean("valid"), alarm != null)
            // Zeitraum nach dem Lesen (gekürzt, kein Überlauf)
            if (c.has("windowHours")) assertEquals(c.getString("name"), c.getInt("windowHours"), alarm?.windowHours)
            // Seit Build 104: fallend ≥ 100 % ausgeschaltet übernommen
            if (c.has("expectedEnabled")) assertEquals(c.getString("name"), c.getBoolean("expectedEnabled"), alarm?.enabled)
        }
        // Liste: ungültige werden gezählt, nicht still verworfen
        val list = JSONArray().apply { for (i in 0 until cases.length()) put(cases.getJSONObject(i).getJSONObject("alarm")) }
        val parsed = AlarmJson.parse(list)
        val expectedValid = (0 until cases.length()).count { cases.getJSONObject(it).getBoolean("valid") }
        assertEquals(expectedValid, parsed.items.size)
        assertEquals(cases.length() - expectedValid, parsed.invalid)
    }
}
