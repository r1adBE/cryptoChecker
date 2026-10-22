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
            "suspend fun deleteAlarm(", "suspend fun setAlarmEnabled(",
        )
        for (signature in userWrites) {
            assertTrue(signature, member(repo, signature).contains("userWriteRefused("))
        }
        // Seit Build 105: Alarm speichern in WatchRepositoryAlarmSave.kt (eindeutiges Ergebnis); die Kurzformen leiten weiter
        val save = source("WatchRepositoryAlarmSave.kt")
        for (signature in listOf("fun WatchRepository.trySaveAlarm(", "fun WatchRepository.trySaveAlarmMerged(")) {
            val body = save.substringAfter(signature).substringBefore("\n\n")
            assertTrue(signature, body.contains("userWriteRefused()) AlarmSaveResult.Refused"))
        }
        assertTrue(member(repo, "suspend fun saveAlarm(").contains("trySaveAlarm(alarm)"))
        assertTrue(member(repo, "suspend fun saveAlarmMerged(").contains("trySaveAlarmMerged(id, build)"))
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

    /**
     * Schalter melden «abgelehnt» (false): Folgeaktionen (Mitteilung zeigen bzw. entfernen) laufen nur nach dem Schreiben —
     * sonst verschwände z. B. die Mitteilung eines noch scharfen Alarms samt «1 h später».
     */
    @Test
    fun refusedTogglesSkipFollowUps() {
        val repo = source("WatchRepository.kt")
        assertTrue(member(repo, "suspend fun setNotificationEnabled(").contains("): Boolean {"))
        assertTrue(member(repo, "suspend fun setAlarmEnabled(").contains("): Boolean {"))
        assertTrue(member(repo, "suspend fun setTtsEnabled(").contains("): Boolean {"))
        // Liste statt Map: eine Datei kann mehrere Aufrufer haben
        val callers = listOf(
            "alarms/AlarmsOverviewViewModel.kt" to "if (!watchRepository.setAlarmEnabled(alarmId, enabled)) return@launch",
            "alarms/AlarmsViewModel.kt" to "if (!watchRepository.setAlarmEnabled(alarmId, enabled)) return@launch",
            "settings/SettingsAlarmActions.kt" to "if (!watchRepository.setNotificationEnabled(watchId, enabled)) return",
            "watchlist/WatchlistEditController.kt" to "if (!watchRepository.setNotificationEnabled(watch.id, enabled)) return@launchWrite",
            "watchlist/WatchlistEditController.kt" to "if (watchRepository.setTtsEnabled(watch.id, enabled) && turnOnSpeech) {",
        )
        for ((file, guard) in callers) {
            val path = "src/main/java/com/cryptochecker/app/ui/features/$file"
            val text = listOf(File(path), File("app/$path")).first { it.exists() }.readText()
            assertTrue(file, text.contains(guard))
        }
    }

    /**
     * Seit Build 105 (Review ARC3-R01, UX3-01): Lösch- und Deaktivier-Wege melden «abgelehnt» eindeutig (false); Rückgängig,
     * Mitteilungen und Protokoll laufen erst danach und nur nach dem Schreiben.
     */
    @Test
    fun deletesReportRefusalAndFollowUpsComeAfter() {
        val repo = source("WatchRepository.kt")
        for (signature in listOf(
            "suspend fun deleteWatch(id: Long)", "suspend fun deleteWatches(", "suspend fun deleteAllWatches()",
            "suspend fun setDisabled(", "suspend fun deleteAlarm(",
        )) {
            assertTrue(signature, member(repo, signature).contains("): Boolean {"))
        }
        val controller = ui("watchlist/WatchlistEditController.kt")
        val single = controller.substringAfter("fun deleteWithUndo(").substringBefore("\n    fun ")
        assertTrue(single.indexOf("deleteWatch(watch.id)) return@withLock") in 0 until single.indexOf("undo.merge("))
        assertTrue(single.indexOf("undo.merge(") < single.indexOf("notifier.cancelAlarms("))
        val many = controller.substringAfter("private suspend fun deleteSnapshots(").substringBefore("\n    fun ")
        assertTrue(many.indexOf("deleteWatches(") in 0 until many.indexOf("undo.put("))
        assertTrue(many.indexOf("undo.put(") < many.indexOf("notifier.cancelAlarms("))
        val all = controller.substringAfter("fun deleteAllWithUndo(").substringBefore("\n    fun ")
        assertTrue(all.indexOf("deleteAllWatches()) return@withLock 0") in 0 until all.indexOf("notifier.cancelAllPrices("))
        val alarms = ui("alarms/AlarmsViewModel.kt").substringAfter("fun delete(alarmId: Long)").substringBefore("\n    fun ")
        assertTrue(alarms.indexOf("!watchRepository.deleteAlarm(alarmId)) return@launch") in 0 until alarms.indexOf("retractAlarm("))
    }

    /**
     * Seit Build 105 (Review ARC3-R02, UX3-01/03/04): Die Rückfrage F-08 kommt EINMAL je Aktion VOR Sperren und Folgen
     * ([confirmUserWrite]); der Schreibweg wartet nie auf sie (sonst hielte ein offener Dialog z. B. die Refresh-Sperre),
     * sondern lehnt ohne Bestätigung ab. Jede Datei, die Schreibwege des Nutzers aufruft, fragt vorher.
     */
    @Test
    fun actionsConfirmBeforeLocksAndWritePathsNeverWait() {
        val lock = source("WatchRepositoryLock.kt").substringAfter("fun WatchRepository.userWriteRefused(").substringBefore("\n}\n")
        assertFalse(lock.contains(".confirm("))
        assertTrue(lock.contains("WatchlistNotSavedGate.needsConfirmation()"))
        val edit = ui("watchlist/WatchEditViewModel.kt")
        assertTrue(edit.indexOf("confirmUserWrite()") in 0 until edit.indexOf("withRefreshLock {"))
        val controller = ui("watchlist/WatchlistEditController.kt")
        // Jede Sperre der Löschwege liegt hinter der Rückfrage (launchWrite bzw. confirmUserWrite davor)
        Regex("""fun (\w+)\([^\n]*\{\n((?:(?!\n    fun ).)*)""", RegexOption.DOT_MATCHES_ALL).findAll(controller).forEach { m ->
            val body = m.groupValues[2]
            val lockAt = body.indexOf("undoMutex.withLock")
            if (lockAt >= 0) {
                val asked = body.indexOf("launchWrite {").takeIf { it >= 0 } ?: body.indexOf("confirmUserWrite()")
                assertTrue(m.groupValues[1], asked in 0 until lockAt)
            }
        }
        val callers = listOf(
            "watchlist/WatchlistEditController.kt", "watchlist/WatchEditViewModel.kt", "watchlist/WatchlistStarterController.kt",
            "explorer/ExplorerBulkActions.kt", "explorer/ExplorerSelectionActions.kt", "alarms/AlarmsViewModel.kt",
            "alarms/AlarmsOverviewViewModel.kt", "settings/SettingsAlarmActions.kt",
        )
        for (file in callers) assertTrue(file, ui(file).contains("watchRepository.confirmUserWrite()"))
        // Startpaare: eine Frage vor dem Erst-Moment und vor der Schleife
        val starter = ui("watchlist/WatchlistStarterController.kt").substringAfter("private suspend fun addStarterPairs(")
        assertTrue(starter.indexOf("confirmUserWrite()") in 0 until starter.indexOf("addMoments.postStarter("))
        // «Abbrechen» beim Alarm-Speichern meldet keine Grenze (nur AlarmSaveResult.Full)
        val alarms = ui("alarms/AlarmsViewModel.kt")
        assertFalse(alarms.contains("!alarmSaveBlocked()) onLimit()"))
        assertTrue(alarms.contains("AlarmSaveResult.Full -> { onLimit(); null }"))
    }

    private fun ui(file: String): String {
        val path = "src/main/java/com/cryptochecker/app/ui/features/$file"
        return listOf(File(path), File("app/$path")).first { it.exists() }.readText()
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
