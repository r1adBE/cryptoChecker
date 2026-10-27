package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Speichern aus dem Editor mit dem GESPEICHERTEN Stand: ein eben ausgelöster Alarm bleibt ausgelöst,
 * auch wenn die Oberfläche noch den scharfen Stand zeigte — sonst käme eine zweite Meldung.
 */
class AlarmSaveBuilderTest {

    private fun input(
        condition: AlarmCondition = AlarmCondition.PRICE_ABOVE,
        threshold: Double = 70_000.0,
        speak: Boolean = false,
        freshReference: Double? = null,
        id: Long = 7,
    ) = AlarmSaveBuilder.Input(
        id = id, watchId = 1, condition = condition, threshold = threshold, repeating = false,
        sound = true, vibrate = true, speak = speak, windowHours = 4, currency = null,
        freshReference = freshReference, now = 5_000,
    )

    private val armed = AlarmEntity(id = 7, watchId = 1, condition = AlarmCondition.PRICE_ABOVE, threshold = 70_000.0)

    @Test
    fun ausgeloesterZustandAusDerDatenbankBleibt() {
        // Die Aktualisierung hat den einmaligen Alarm inzwischen ausgelöst (aus, mit Meldung)
        val stored = armed.copy(enabled = false, lastTriggeredAt = 4_000, lastTriggeredPrice = 70_100.0, referenceAt = 4_000)
        val saved = AlarmSaveBuilder.build(input(speak = true), stored)
        assertFalse(saved.enabled)
        assertEquals(4_000L, saved.lastTriggeredAt)
        assertEquals(4_000L, saved.referenceAt)
        assertTrue(saved.speak)
    }

    @Test
    fun geaenderteBedingungStelltNeuScharf() {
        val stored = armed.copy(enabled = false, lastTriggeredAt = 4_000, referenceAt = 4_000)
        val saved = AlarmSaveBuilder.build(input(threshold = 80_000.0), stored)
        assertTrue(saved.enabled)
        assertEquals(0L, saved.lastTriggeredAt)
        assertEquals(0L, saved.referenceAt)
    }

    @Test
    fun prozentAlarmBehaeltBezugSonstFrischerKurs() {
        val percent = armed.copy(condition = AlarmCondition.CHANGE_PERCENT_UP, threshold = 5.0, referencePrice = 60_000.0)
        val kept = AlarmSaveBuilder.build(input(AlarmCondition.CHANGE_PERCENT_UP, 5.0, freshReference = 65_000.0), percent)
        assertEquals(60_000.0, kept.referencePrice!!, 0.0)
        val fresh = AlarmSaveBuilder.build(input(AlarmCondition.CHANGE_PERCENT_UP, 5.0, freshReference = 65_000.0, id = 0), null)
        assertEquals(65_000.0, fresh.referencePrice!!, 0.0)
        assertNull(AlarmSaveBuilder.build(input(), null).referencePrice)
    }

    @Test
    fun bewegungsFensterBeginntJetzt() {
        val saved = AlarmSaveBuilder.build(input(AlarmCondition.MOVE_PERCENT_WINDOW, 3.0, freshReference = 1.0, id = 0), null)
        assertEquals(5_000L, saved.referenceAt)
    }

    @Test
    fun speichernLiestUndSchreibtInEinerTransaktion() {
        fun source(path: String) = listOf(File(path), File("app/$path"), File("../data/$path"), File("data/$path"), File("../domain/$path"), File("domain/$path")).first { it.exists() }.readText()
        val vm = source("src/main/java/com/cryptochecker/app/ui/features/alarms/AlarmsViewModel.kt")
        val save = vm.substringAfter("fun save(draft: AlarmDraft").substringBefore("\n    fun delete(")
        assertTrue(save.contains("saveMergedIfWatchExists("))
        assertFalse(save.contains("AlarmEdit.merged("))
        // In WatchRepositoryAlarmSave.kt (eindeutiges Ergebnis: gespeichert, voll, abgelehnt)
        val repo = source("src/main/java/com/cryptochecker/app/data/WatchRepositoryAlarmSave.kt")
        val merged = repo.substringAfter("suspend fun WatchRepository.trySaveAlarmMerged(")
        assertTrue(merged.contains("database.withTransaction"))
        assertTrue(merged.contains("watchDao.getAlarm(id)"))
    }
}
