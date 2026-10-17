package com.cryptochecker.app.parity

import com.cryptochecker.app.data.BackupPasswordStrength
import com.cryptochecker.app.data.BackupVersion
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Schwache/starke Passwörter und Versionen der Sicherung (`testdata/parity/backup_passwords.json`); Gegenstück:
 * `BackupPasswordParityTests.swift`.
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
