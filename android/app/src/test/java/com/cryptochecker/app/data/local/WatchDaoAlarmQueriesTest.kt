package com.cryptochecker.app.data.local

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bedingte Alarm-UPDATEs der Aktualisierung prüfen alle die Alarm-Währung (Room-Abfragen laufen nur auf dem Gerät,
 * darum am Quelltext): auch [WatchDao.rearmAlarmIfUnchanged] — wie markAlarmTriggered.
 */
class WatchDaoAlarmQueriesTest {

    private fun source(): String {
        val path = "src/main/java/com/cryptochecker/app/data/local/WatchDao.kt"
        return listOf(
            File(path),
            File("app/$path"),
            File("../data/$path"),
            File("data/$path"),
            File("../domain/$path"),
            File("domain/$path")
        ).first {
            it.exists()
        }.readText()
    }

    /** Text der @Query direkt vor `fun name(`. */
    private fun queryOf(name: String): String {
        val text = source()
        val end = text.indexOf("fun $name(")
        val start = text.lastIndexOf("@Query(", end)
        return text.substring(start, end)
    }

    @Test
    fun conditionalUpdatesCheckTheCurrency() {
        for (name in listOf("markAlarmTriggered", "setAlarmReferenceIfUnchanged", "rearmAlarmIfUnchanged")) {
            assertTrue(name, queryOf(name).contains("currency IS :currency"))
        }
    }

    /** Paare löschen entfernt auch die letzten Prüfungen ihrer Alarme aus dem Alarm-Protokoll. */
    @Test
    fun deletingPairCleansUpTheAlarmLog() {
        val path = "src/main/java/com/cryptochecker/app/data/WatchRepository.kt"
        val file = listOf(
            File(path),
            File("app/$path"),
            File("../data/$path"),
            File("data/$path"),
            File("../domain/$path"),
            File("domain/$path")
        ).first {
            it.exists()
        }
        val text = file.readText()
        for (name in listOf("deleteWatch(id: Long)", "deleteWatches(ids: Collection<Long>)", "deleteAllWatches()")) {
            val body = text.substringAfter("suspend fun $name").substringBefore("\n    }\n")
            assertTrue(name, body.contains("forgetDeletedAlarms()"))
        }
        // Aufräumen in der Erweiterungsdatei neben dem Repository
        val cleanup = File(file.parentFile, "WatchRepositoryAlarmCleanup.kt").readText()
        assertTrue(cleanup.substringAfter("suspend fun WatchRepository.forgetDeletedAlarms()").contains("alarmLogStore.retainAlarms("))
    }
}
