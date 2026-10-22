package com.cryptochecker.app

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.data.AddWatchOutcome
import com.cryptochecker.app.data.PairEditResult
import com.cryptochecker.app.data.RestoreOutcome
import com.cryptochecker.app.data.WatchMove
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.WatchSnapshot
import com.cryptochecker.app.data.addWatch
import com.cryptochecker.app.data.addWatches
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.restore
import com.cryptochecker.app.data.tryAddWatch
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.refresh.AlarmLogStore
import com.cryptochecker.app.widget.PortfolioSnapshotSeal
import com.cryptochecker.marketdata.model.MarketPair
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Notfall-Sperre der Merkliste auf dem Gerät (seit Build 103, Review TST-02): Liegt die gespeicherte Merkliste nur leer im
 * Arbeitsspeicher ([DatabaseEncryptionNotice.storedKept]), ruft der Test JEDEN Schreibweg des Nutzers von
 * [WatchRepository] mit echtem Room auf und prüft, dass sich keine Zeile ändert — ergänzt den Quelltext-Test
 * `WatchRepositoryLockTest` (JVM). Per Reflection muss jede öffentliche suspend-Funktion des Repositorys (Klasse und
 * Erweiterungen in `WatchRepositoryAdd.kt`/`WatchRepositoryRefresh.kt`) in genau einer Liste stehen: Schreibweg des
 * Nutzers (hier aufgerufen), nur lesen oder Hintergrund — ein neuer Schreibweg fällt so auf, bis er eingeordnet ist.
 */
@RunWith(AndroidJUnit4::class)
class WatchlistLockDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var repo: WatchRepository
    private var watchId = 0L
    private var alarmId = 0L

    /** Alarm-Protokoll ohne Schlüssel: gesperrt wird nichts gelöscht, es wird also nie versiegelt. */
    private val noKeys = object : PortfolioSnapshotSeal.Keys {
        override fun lookup(): Nothing = throw UnsupportedOperationException("kein Schlüssel im Test")
        override fun discardIfBroken(error: Throwable) = Unit
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = WatchRepository(db, db.getWatchDao(), AlarmLogStore(File(context.cacheDir, "alarm-log-lock-test"), noKeys))
        runBlocking {
            watchId = checkNotNull(repo.addWatch(BINANCE, MarketPair("BTC", "USDT", "BTCUSDT"), group = "Layer 1"))
            checkNotNull(repo.addWatch(BINANCE, MarketPair("ETH", "USDT", "ETHUSDT")))
            repo.setNote(watchId, "Ø 77 500")
            alarmId = checkNotNull(
                repo.saveAlarm(AlarmEntity(watchId = watchId, condition = AlarmCondition.PRICE_ABOVE, threshold = 100_000.0)),
            )
        }
    }

    @After
    fun tearDown() {
        DatabaseEncryptionNotice.clearInMemoryForTest(AppDatabase.DB_NAME)
        db.close()
    }

    /** Alle Zeilen der Merkliste als Text (Paare und Alarme), zum Vergleich vorher/nachher. */
    private fun dump(): List<List<String?>> = listOf("watches", "alarms").flatMap { table ->
        db.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY id").use { c ->
            buildList {
                while (c.moveToNext()) add(listOf(table) + (0 until c.columnCount).map { c.getString(it) })
            }
        }
    }

    /**
     * Jeder Schreibweg des Nutzers einmal, mit Argumenten, die ungesperrt etwas ändern würden. Rückgabe-Prüfungen gleich
     * mit (gesperrt: «gesperrt», null bzw. nichts angelegt).
     */
    private fun userWrites(): Map<String, suspend () -> Unit> {
        // Ausdrücklich als suspend-Lambda (mit «to» allein wäre es ein gewöhnliches Lambda)
        fun write(block: suspend () -> Unit): suspend () -> Unit = block
        val btc = MarketPair("BTC", "USDT", "BTCUSDT")
        val sol = MarketPair("SOL", "USDT", "SOLUSDT")
        return mapOf(
            "changePair" to write { assertEquals(PairEditResult.LOCKED, repo.changePair(watchId, BINANCE, sol)) },
            "setMissingPercentReferences" to write { repo.setMissingPercentReferences(watchId, 99_000.0) },
            "deleteWatch" to write { repo.deleteWatch(watchId) },
            "deleteWatches" to write { repo.deleteWatches(listOf(watchId)) },
            "deleteAllWatches" to write { repo.deleteAllWatches() },
            "setNotificationEnabled" to write { assertFalse(repo.setNotificationEnabled(watchId, false)) },
            "setFavorite" to write { repo.setFavorite(watchId, true) },
            "move" to write { repo.move(watchId, WatchMove.BOTTOM) },
            "reorder" to write { repo.reorder(repo.getWatches().map { it.id }.reversed()) },
            "setNote" to write { repo.setNote(watchId, "neu") },
            "setGroup" to write { repo.setGroup(watchId, "Neu") },
            "applyGroupEdit" to write { repo.applyGroupEdit("Layer 1", "Neu", setOf(watchId)) },
            "clearGroup" to write { repo.clearGroup("Layer 1") },
            "setTtsEnabled" to write { assertFalse(repo.setTtsEnabled(watchId, true)) },
            "setDisabled" to write { repo.setDisabled(listOf(watchId), true) },
            "setNotifiedPrice" to write { repo.setNotifiedPrice(watchId, 98_000.0) },
            "saveAlarm" to write {
                assertNull(repo.saveAlarm(AlarmEntity(watchId = watchId, condition = AlarmCondition.PRICE_BELOW, threshold = 1.0)))
            },
            "saveAlarmMerged" to write {
                assertNull(repo.saveAlarmMerged(alarmId) { it!!.copy(threshold = 120_000.0) })
            },
            "deleteAlarm" to write { repo.deleteAlarm(alarmId) },
            "setAlarmEnabled" to write { assertFalse(repo.setAlarmEnabled(alarmId, false)) },
            "addWatch" to write { assertNull(repo.addWatch(BINANCE, sol)) },
            "tryAddWatch" to write { assertEquals(AddWatchOutcome.Locked, repo.tryAddWatch(BINANCE, sol)) },
            "addWatches" to write { assertTrue(repo.addWatches(BINANCE, listOf(sol, btc)).locked) },
            "restore" to write {
                val gone = checkNotNull(repo.getWatch(watchId)).copy(id = 99, baseAsset = "SOL", pairId = "SOLUSDT")
                assertEquals(RestoreOutcome.BLOCKED, repo.restore(WatchSnapshot(gone, emptyList())))
            },
        )
    }

    @Test
    fun lockedWatchlistIgnoresEveryUserWrite() = runBlocking {
        val before = dump()
        DatabaseEncryptionNotice.markInMemory(AppDatabase.DB_NAME, storedKept = true)
        for ((name, write) in userWrites()) {
            write()
            assertEquals("$name hat trotz Sperre geschrieben", before, dump())
        }
        // Gegenprobe: ohne Sperre ändert derselbe Schreibweg die Tabelle (der Vergleich oben sieht also Änderungen)
        DatabaseEncryptionNotice.clearInMemoryForTest(AppDatabase.DB_NAME)
        repo.setNote(watchId, "neu")
        assertNotEquals(before, dump())
        // Ungesperrt melden die Schalter «geschrieben» (Aufrufer führen erst dann die Folgeaktionen aus)
        assertTrue(repo.setNotificationEnabled(watchId, false))
        assertTrue(repo.setAlarmEnabled(alarmId, false))
        assertTrue(repo.setTtsEnabled(watchId, true))
    }

    @Test
    fun everyPublicSuspendFunctionIsClassified() {
        val declared = suspendFunctions(WatchRepository::class.java, extension = false) +
            EXTENSION_FILES.flatMap { suspendFunctions(Class.forName("com.cryptochecker.app.data.$it"), extension = true) }
        val userWrites = userWrites().keys
        val known = userWrites + READS + BACKGROUND
        assertEquals("nicht eingeordnet (Schreibweg des Nutzers → userWrites, sonst READS/BACKGROUND)", emptySet<String>(), declared - known)
        assertEquals("veraltete Einträge", emptySet<String>(), known - declared)
        assertTrue("doppelt eingeordnet", userWrites.intersect(READS + BACKGROUND).isEmpty() && READS.intersect(BACKGROUND).isEmpty())
    }

    /**
     * Namen der öffentlichen suspend-Funktionen (letzter Parameter Continuation). Ohne erzeugte Hilfsmethoden (`…$default`,
     * umbenannte `internal`-Mitglieder); bei Erweiterungen nur solche auf [WatchRepository].
     */
    private fun suspendFunctions(type: Class<*>, extension: Boolean): Set<String> = type.declaredMethods
        .filter { m: Method ->
            Modifier.isPublic(m.modifiers) && !m.isSynthetic && '$' !in m.name &&
                m.parameterTypes.lastOrNull() == Continuation::class.java &&
                (!extension || (Modifier.isStatic(m.modifiers) && m.parameterTypes.firstOrNull() == WatchRepository::class.java))
        }
        .map { it.name }
        .toSet()

    private companion object {
        val BINANCE = MarketInfo("Binance", "Binance")

        /** Dateien mit Erweiterungen von [WatchRepository] (Klassen der Kotlin-Dateien). */
        val EXTENSION_FILES = listOf("WatchRepositoryAddKt", "WatchRepositoryRefreshKt")

        /** Nur lesen. */
        val READS = setOf("getWatches", "getWatch", "snapshot", "getEnabledAlarms", "getAllEnabledAlarms", "getAllAlarmIds")

        /**
         * Hintergrund (Kurse, Fehler, Alarm-Zustand, Vertragsarten): schreibt bewusst weiter in die leere Datenbank im
         * Arbeitsspeicher, siehe `WatchRepositoryLock.kt`.
         */
        val BACKGROUND = setOf(
            "normalizeContractTypes", "clearChanges", "updatePrice", "writePrice", "writeError", "updateError", "fillChange",
            "applyRefresh", "setNotifiedPrices", "markAlarmTriggered", "setAlarmReferenceIfUnchanged", "rearmAlarm",
            "rearmAlarmIfUnchanged",
        )
    }
}
