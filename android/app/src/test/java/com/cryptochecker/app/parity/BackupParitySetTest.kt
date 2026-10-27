package com.cryptochecker.app.parity

import com.cryptochecker.app.R
import com.cryptochecker.app.data.AlarmJson
import com.cryptochecker.app.data.BackupPasswordStrength
import com.cryptochecker.app.data.BackupRestorePolicy
import com.cryptochecker.app.data.BackupVersion
import com.cryptochecker.app.ui.features.settings.BackupMessage
import com.cryptochecker.app.ui.features.settings.BackupResultPresentation
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// Kleine Testklassen dieses Themas in einer Datei (R, Build 147); jede Klasse unverändert.

// ---- aus BackupAlarmsParityTest

/** Paar-Alarme aus der Sicherung (`testdata/parity/backup_alarms.json`); Gegenstück: `BackupAlarmsParityTests`. */
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
            // Fallend ≥ 100 % ausgeschaltet übernommen
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

// ---- aus BackupPasswordParityTest

/**
 * Schwache/starke Passwörter und Versionen der Sicherung (`testdata/parity/backup_passwords.json`); Gegenstück:
 * `BackupPasswordParityTests`.
 */
class BackupPasswordParityTest {

    private val root = JSONObject(File(ParityJson.dir(), "backup_passwords.json").readText())

    @Test
    fun passwordsMatchFixture() {
        val weak = root.getJSONArray("weak")
        val strong = root.getJSONArray("strong")
        assertTrue(weak.length() >= 20 && strong.length() >= 6)
        for (i in 0 until weak.length()) assertTrue(weak.getString(i), BackupPasswordStrength.isWeak(weak.getString(i)))
        for (i in 0 until strong.length()) assertFalse(strong.getString(i), BackupPasswordStrength.isWeak(strong.getString(i)))
    }

    @Test
    fun versionsMatchFixture() {
        val cases = root.getJSONArray("versions")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val raw = c.opt("version").takeIf { it != JSONObject.NULL }
            when (val expected = c.get("result")) {
                is Number -> assertEquals(name, expected.toInt(), BackupVersion.check(raw))
                "newer" -> try {
                    BackupVersion.check(raw); fail(name)
                } catch (_: BackupVersion.NewerVersionException) {
                }
                else -> try {
                    BackupVersion.check(raw); fail(name)
                } catch (_: IllegalArgumentException) {
                }
            }
        }
    }
}

// ---- aus BackupResultDialogParityTest

/**
 * Ergebnis von Sichern/Wiederherstellen als Dialog oder Toast (`testdata/parity/backup_result_dialog.json`).
 * Gegenstück: `Tests/BackupResultDialogParityTests.swift`.
 */
class BackupResultDialogParityTest {

    private val fixture = ParityJson.load("backup_result_dialog.json")

    @Test
    fun entscheidetWieGemeinsameTestfaelle() {
        val cases = fixture["cases"].list()
        assertTrue(cases.size >= 35)
        for (raw in cases) {
            val c = raw.obj()
            val name = c["description"] as String
            val note = (c["addressServerNote"] as String?)?.let { BackupRestorePolicy.AddressServerNote.valueOf(it) }
            val actual = BackupResultPresentation.asDialog(
                key = c["key"] as String,
                text = c["text"] as String,
                droppedPairs = c["droppedPairs"].num()!!.toInt(),
                droppedAlarms = c["droppedAlarms"].num()!!.toInt(),
                appLockKept = c["appLockKept"] as Boolean,
                addressServerNote = note,
                fontScale = (c["fontScale"].num() ?: 1.0).toFloat(),
            )
            assertEquals(name, c["expected"] as String, if (actual) "dialog" else "toast")
        }
    }

    @Test
    fun grenzeUndSchluesselWieGemeinsameTestfaelle() {
        assertEquals(fixture["toastMaxChars"].num()!!.toInt(), BackupResultPresentation.TOAST_MAX_CHARS)
        assertEquals(fixture["longKeys"].list().map { it as String }.toSet(), BackupResultPresentation.LONG_KEYS)
        val ranges = fixture["wideRanges"].list().map { r -> r.list().map { (it as String).toInt(16) } }
        assertEquals(ranges, BackupResultPresentation.WIDE_RANGES.map { listOf(it.first, it.last) })
    }

    /** Breite: schmal 1, breit 2 (auch ausserhalb der BMP); Grenze sinkt mit grösserer Schrift, steigt nie über 80. */
    @Test
    fun breiteUndGrenze() {
        assertEquals(3, BackupResultPresentation.displayWidth("abc"))
        assertEquals(4, BackupResultPresentation.displayWidth("日本"))
        assertEquals(2, BackupResultPresentation.displayWidth("\uD840\uDC00"))
        assertEquals(1, BackupResultPresentation.displayWidth("\uFF71"))
        assertEquals(80.0, BackupResultPresentation.toastLimit(0.85f), 0.0)
        assertEquals(40.0, BackupResultPresentation.toastLimit(2f), 0.0)
    }

    /** Die langen Fehlermeldungen zeigen genau die Texte, deren Namen in LONG_KEYS stehen. */
    @Test
    fun langeMeldungenNutzenDieGenanntenTexte() {
        assertEquals(R.string.backup_not_enough_memory, BackupResultPresentation.messageRes(BackupMessage.NotEnoughMemory))
        assertEquals(R.string.backup_storage_kept, BackupResultPresentation.messageRes(BackupMessage.StorageKept))
        assertEquals(R.string.backup_restore_storage_in_memory, BackupResultPresentation.messageRes(BackupMessage.StorageInMemory))
        assertEquals(
            R.string.backup_restore_journal_unavailable,
            BackupResultPresentation.messageRes(BackupMessage.JournalUnavailable),
        )
        assertEquals(R.string.backup_restored, BackupResultPresentation.messageRes(BackupMessage.Restored(1, 0)))
    }
}
