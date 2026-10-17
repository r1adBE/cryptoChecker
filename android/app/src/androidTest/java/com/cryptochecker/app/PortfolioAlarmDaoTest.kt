package com.cryptochecker.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.data.portfolio.PortfolioAlarmDao
import com.cryptochecker.app.data.portfolio.PortfolioAlarmEntity
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import com.cryptochecker.app.domain.alarm.PortfolioAlarmKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bedingtes Speichern eines ausgelösten Portfolio-Alarms (PortfolioAlarmDao.markTriggered):
 * Hat der Nutzer den Alarm seit dem Lesen ausgeschaltet oder geändert, bleibt seine Änderung
 * und es wird nicht gemeldet (0 Zeilen) — wie die Paar-Alarme.
 */
@RunWith(AndroidJUnit4::class)
class PortfolioAlarmDaoTest {

    private lateinit var db: PortfolioDatabase
    private lateinit var dao: PortfolioAlarmDao

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, PortfolioDatabase::class.java).allowMainThreadQueries().build()
        dao = db.getPortfolioAlarmDao()
    }

    @After
    fun close() = db.close()

    private suspend fun mark(read: PortfolioAlarmEntity, time: Long = 1_000L): Int =
        dao.markTriggered(
            id = read.id, time = time, value = 51_000.0, enabled = read.repeating,
            expectedReferenceAt = read.referenceAt, kind = read.kind.name, threshold = read.threshold,
            repeating = read.repeating, currency = read.currency,
        )

    @Test
    fun unchangedAlarmIsSavedOnce() = runBlocking {
        val id = dao.insert(PortfolioAlarmEntity(kind = PortfolioAlarmKind.VALUE_ABOVE, threshold = 50_000.0, currency = "CHF"))
        val read = dao.getAll().single { it.id == id }
        assertEquals(1, mark(read))
        val saved = dao.getAll().single { it.id == id }
        assertFalse(saved.enabled) // einmalig: aus
        assertEquals(1_000L, saved.referenceAt)
        // Zweites Speichern mit demselben gelesenen Stand: schon ausgelöst → 0
        assertEquals(0, mark(read, time = 2_000L))
    }

    @Test
    fun switchedOffAfterReadStaysOff() = runBlocking {
        val id = dao.insert(
            PortfolioAlarmEntity(kind = PortfolioAlarmKind.CHANGE_UP, threshold = 5.0, repeating = true)
        )
        val read = dao.getAll().single { it.id == id }
        dao.setEnabled(id, false)
        assertEquals(0, mark(read))
        val after = dao.getAll().single { it.id == id }
        assertFalse(after.enabled)
        assertEquals(0L, after.lastTriggeredAt)
    }

    @Test
    fun editedThresholdIsNotOverwritten() = runBlocking {
        val id = dao.insert(PortfolioAlarmEntity(kind = PortfolioAlarmKind.VALUE_BELOW, threshold = 10_000.0, currency = null))
        val read = dao.getAll().single { it.id == id }
        dao.insert(read.copy(threshold = 9_000.0))
        assertEquals(0, mark(read))
        assertTrue(dao.getAll().single { it.id == id }.enabled)
    }
}
