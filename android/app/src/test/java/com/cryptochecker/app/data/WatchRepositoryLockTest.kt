package com.cryptochecker.app.data

import com.cryptochecker.app.data.local.WatchlistWriteRefusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Sperre der Merkliste im Repository (seit Build 103, Review F-08): Jeder Schreibweg des Nutzers fragt zuerst
 * [userWriteRefused] (zentral, nicht in jeder Oberfläche), Hintergrundarbeit nicht. Room läuft nur auf dem Gerät, darum
 * am Quelltext wie [com.cryptochecker.app.data.local.WatchDaoAlarmQueriesTest]; die Drosselung des Hinweises
 * ([WatchlistWriteRefusal]) rein.
 */
class WatchRepositoryLockTest {

    private fun source(name: String): String {
        val path = "src/main/java/com/cryptochecker/app/data/$name"
        return listOf(File(path), File("app/$path")).first { it.exists() }.readText()
    }

    /**
     * Rumpf eines Mitglieds von `WatchRepository`: bis zum nächsten Mitglied auf Klassen-Ebene (auch bei Funktionen in
     * Ausdrucksform, die nicht mit «}» enden).
     */
    private fun member(text: String, signature: String): String {
        assertEquals(signature, 1, text.split(signature).size - 1)
        val rest = text.substringAfter(signature)
        val next = Regex("""\n    (?:(?:private |internal |override )*(?:suspend )?fun |val |var |companion object|/\*\*|@)""").find(rest)
        return if (next == null) rest else rest.substring(0, next.range.first)
    }

    @Test
    fun everyUserWriteAsksTheLock() {
        val repo = source("WatchRepository.kt")
        val userWrites = listOf(
            "suspend fun changePair(", "suspend fun setMissingPercentReferences(", "suspend fun deleteWatch(id: Long)",
            "suspend fun deleteWatches(", "suspend fun deleteAllWatches()",
            "suspend fun setNotificationEnabled(", "suspend fun setFavorite(", "suspend fun move(", "suspend fun reorder(",
            "suspend fun setNote(", "suspend fun setGroup(", "suspend fun applyGroupEdit(", "suspend fun clearGroup(",
            "suspend fun setTtsEnabled(", "suspend fun setDisabled(", "suspend fun setNotifiedPrice(",
            "suspend fun saveAlarm(", "suspend fun saveAlarmMerged(", "suspend fun deleteAlarm(",
            "suspend fun setAlarmEnabled(",
        )
        for (signature in userWrites) {
            assertTrue(signature, member(repo, signature).contains("userWriteRefused("))
        }
    }

    @Test
    fun addingPairsAsksTheLock() {
        val add = source("WatchRepositoryAdd.kt")
        // «Rückgängig» seit Build 103 ebenfalls dort (Obergrenze der Merkliste)
        for (signature in listOf("fun WatchRepository.tryAddWatch(", "fun WatchRepository.addWatches(", "fun WatchRepository.restore(")) {
            assertTrue(signature, add.substringAfter(signature).substringBefore("\n}\n").contains("userWriteRefused("))
        }
        // Ohne eigene Datenbank-Arbeit: legt über tryAddWatch an (dort gesperrt)
        assertTrue(add.substringAfter("fun WatchRepository.addWatch(").substringBefore("\n\n").contains("tryAddWatch("))
    }

    /** Seit Build 103 (Review FUN-03): «Rückgängig» prüft die Obergrenze der Merkliste wie das Anlegen. */
    @Test
    fun restoreRespectsPairLimit() {
        val body = source("WatchRepositoryAdd.kt").substringAfter("fun WatchRepository.restore(")
        assertTrue(body.contains("WatchlistLimits.canAddPair(watchDao.countWatches())"))
        assertTrue(body.contains("RestoreOutcome.FULL"))
        // Vorhandenes Paar geht vor «voll»
        assertTrue(body.indexOf("blocked -> RestoreOutcome.BLOCKED") < body.indexOf("RestoreOutcome.FULL"))
    }

    /** Kurse, Fehler und Alarm-Zustand schreiben weiter (leere Datenbank im Arbeitsspeicher) — kein Absturz, kein Hinweis. */
    @Test
    fun backgroundWritesDoNotAskTheLock() {
        assertFalse(source("WatchRepositoryRefresh.kt").contains("userWriteRefused("))
        val repo = source("WatchRepository.kt")
        for (signature in listOf("suspend fun normalizeContractTypes()", "suspend fun clearChanges(")) {
            assertFalse(signature, member(repo, signature).contains("userWriteRefused("))
        }
    }

    /** Folge-Schreibwege (Meldekurs, Prozent-Bezug) sperren still: Der Hinweis kam schon vom eigentlichen Schreibweg. */
    @Test
    fun followUpWritesRefuseSilently() {
        val repo = source("WatchRepository.kt")
        for (signature in listOf("suspend fun setNotifiedPrice(", "suspend fun setMissingPercentReferences(")) {
            assertTrue(signature, member(repo, signature).contains("userWriteRefused(notify = false)"))
        }
    }

    @Test
    fun lockChecksStoredKeptInsideATransaction() {
        val lock = source("WatchRepositoryLock.kt").substringAfter("fun WatchRepository.userWriteRefused(")
        assertTrue(lock.contains("database.withTransaction"))
        assertTrue(lock.contains("WatchlistStorageLock.blocksWrites(DatabaseEncryptionNotice.storedKept.value)"))
        assertTrue(lock.contains("WatchlistWriteRefusal.report()"))
    }

    @Test
    fun refusalHintIsThrottled() {
        val gap = WatchlistWriteRefusal.MIN_GAP_MILLIS
        assertTrue(WatchlistWriteRefusal.shouldNotify(lastAt = 0L, now = 5L))
        assertFalse(WatchlistWriteRefusal.shouldNotify(lastAt = 1_000L, now = 1_000L + gap - 1))
        assertTrue(WatchlistWriteRefusal.shouldNotify(lastAt = 1_000L, now = 1_000L + gap))
        // Uhr rückwärts (anderer Bezug): nicht als «zu früh» schlucken
        assertTrue(WatchlistWriteRefusal.shouldNotify(lastAt = 10_000L, now = 5_000L))
    }

    @Test
    fun reportFiresOncePerBurst() {
        // Weit weg von anderen Tests (gemeinsamer Zustand des Objekts)
        val start = 9_000_000_000L
        assertTrue(WatchlistWriteRefusal.report(start))
        assertFalse(WatchlistWriteRefusal.report(start + 10))
        assertFalse(WatchlistWriteRefusal.report(start + WatchlistWriteRefusal.MIN_GAP_MILLIS - 1))
        assertTrue(WatchlistWriteRefusal.report(start + WatchlistWriteRefusal.MIN_GAP_MILLIS))
    }
}
