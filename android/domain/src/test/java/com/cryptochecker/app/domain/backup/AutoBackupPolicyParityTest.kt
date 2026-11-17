package com.cryptochecker.app.domain.backup

import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Automatische Sicherung als gemeinsame Testfälle (`testdata/parity/auto_backup.json`, Kopie in iOS `Tests/Parity/`,
 * gelesen von `AutoBackupPolicyParityTests.swift`): Fälligkeit, Dateiname, eigene Dateien und was gelöscht wird.
 */
class AutoBackupPolicyParityTest {

    private val data = ParityJson.load("auto_backup.json")

    @Test
    fun constantsAsShared() {
        assertEquals(data["intervalMillis"].num()!!.toLong(), AutoBackupPolicy.INTERVAL_MILLIS)
        assertEquals(data["retryAfterMillis"].num()!!.toLong(), AutoBackupPolicy.RETRY_AFTER_MILLIS)
        assertEquals(data["keep"].num()!!.toInt(), AutoBackupPolicy.KEEP)
    }

    @Test
    fun due() {
        data["due"].list().also { assertTrue(it.isNotEmpty()) }.forEach { raw ->
            val c = raw.obj()
            val due = AutoBackupPolicy.isDue(
                c["enabled"] as Boolean,
                c["lastSuccessAt"].num()!!.toLong(),
                c["lastAttemptAt"].num()!!.toLong(),
                c["now"].num()!!.toLong()
            )
            assertEquals(c["name"] as String, c["due"], due)
        }
    }

    @Test
    fun fileName() {
        data["fileName"].list().also { assertTrue(it.isNotEmpty()) }.forEach { raw ->
            val c = raw.obj()
            val name = AutoBackupPolicy.fileName(c["millis"].num()!!.toLong(), ZoneId.of(c["zone"] as String))
            assertEquals(c["name"] as String, c["file"], name)
        }
    }

    @Test
    fun ownFiles() {
        data["own"].list().also { assertTrue(it.isNotEmpty()) }.forEach { raw ->
            val c = raw.obj()
            assertEquals(c["name"] as String, c["own"], AutoBackupPolicy.isOwnFile(c["name"] as String))
        }
    }

    @Test
    fun toDelete() {
        data["toDelete"].list().also { assertTrue(it.isNotEmpty()) }.forEach { raw ->
            val c = raw.obj()
            val names = c["names"].list().map { it as String }
            val want = c["delete"].list().map { it as String }
            assertEquals(c["name"] as String, want, AutoBackupPolicy.toDelete(names, c["keep"].num()!!.toInt()))
        }
    }
}
