package com.cryptochecker.app.data.local

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Bedingte Alarm-UPDATEs der Aktualisierung prüfen alle die Alarm-Währung (Room-Abfragen laufen nur auf dem Gerät,
 * darum am Quelltext): seit Build 102 auch [WatchDao.rearmAlarmIfUnchanged] — wie markAlarmTriggered.
 */
class WatchDaoAlarmQueriesTest {

    private fun source(): String {
        val path = "src/main/java/com/cryptochecker/app/data/local/WatchDao.kt"
        return listOf(File(path), File("app/$path")).first { it.exists() }.readText()
    }

    /** Text der @Query direkt vor `fun name(`. */
    private fun queryOf(name: String): String {
        val text = source()
        val end = text.indexOf("fun $name(")
        val start = text.lastIndexOf("@Query(", end)
        return text.substring(start, end)
    }

    @Test
    fun bedingteUpdatesPruefenDieWaehrung() {
        for (name in listOf("markAlarmTriggered", "setAlarmReferenceIfUnchanged", "rearmAlarmIfUnchanged")) {
            assertTrue(name, queryOf(name).contains("currency IS :currency"))
        }
    }

    /** Seit Build 102: Paare löschen entfernt auch die letzten Prüfungen ihrer Alarme aus dem Alarm-Protokoll. */
    @Test
    fun paarLoeschenRaeumtDasAlarmProtokollAuf() {
        val path = "src/main/java/com/cryptochecker/app/data/WatchRepository.kt"
        val text = listOf(File(path), File("app/$path")).first { it.exists() }.readText()
        for (name in listOf("deleteWatch(id: Long)", "deleteWatches(ids: Collection<Long>)", "deleteAllWatches()")) {
            val body = text.substringAfter("suspend fun $name").substringBefore("\n    }\n")
            assertTrue(name, body.contains("forgetDeletedAlarms()"))
        }
        assertTrue(text.substringAfter("private suspend fun forgetDeletedAlarms()").contains("alarmLogStore.retainAlarms("))
    }
}
