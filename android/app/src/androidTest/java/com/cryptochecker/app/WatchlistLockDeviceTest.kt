package com.cryptochecker.app

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.data.AddWatchOutcome
import com.cryptochecker.app.data.AlarmSaveResult
import com.cryptochecker.app.data.PairEditResult
import com.cryptochecker.app.data.RestoreOutcome
import com.cryptochecker.app.data.WatchMove
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.WatchSnapshot
import com.cryptochecker.app.data.addWatch
import com.cryptochecker.app.data.addWatches
import com.cryptochecker.app.data.deleteWatchesWithoutAlarms
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.local.WatchlistNotSavedGate
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.restore
import com.cryptochecker.app.data.tryAddWatch
import com.cryptochecker.app.data.trySaveAlarm
import com.cryptochecker.app.data.trySaveAlarmMerged
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.refresh.AlarmLogStore
import com.cryptochecker.app.notification.AlarmDeliveryMarkers
import com.cryptochecker.app.widget.PortfolioSnapshotSeal
import com.cryptochecker.marketdata.model.ExchangePair
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Notfall-Sperre der Merkliste auf dem Gerät (Review TST-02): Liegt die gespeicherte Merkliste nur leer im
 * Arbeitsspeicher ([DatabaseEncryptionNotice.storedKept]), ruft der Test JEDEN Schreibweg des Nutzers von
 * [WatchRepository] mit echtem Room auf und prüft, dass sich keine Zeile ändert — ergänzt den Quelltext-Test
 * `WatchRepositoryLockTest` (JVM). Per Reflection muss jede öffentliche suspend-Funktion des Repositorys (Klasse und
 * Erweiterungen in `WatchRepositoryAdd.kt`/`WatchRepositoryRefresh.kt`) in genau einer Liste stehen: Schreibweg des
 * Nutzers (hier aufgerufen), nur lesen oder Hintergrund — ein neuer Schreibweg fällt so auf, bis er eingeordnet ist.
 * Review TST2-03: Gegenprobe pro Eintrag — ungesperrt ändert jeder Schreibweg die Tabellen (frische
 * Datenbank je Eintrag), sonst bewiese der Sperr-Vergleich nichts; dafür liegt im Ausgangsstand ein %-Alarm ohne Bezug.
 */
@RunWith(AndroidJUnit4::class)
class WatchlistLockDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var repo: WatchRepository
    private var watchId = 0L
    private var plainWatchId = 0L
    private var alarmId = 0L
    private var percentAlarmId = 0L

    /** Alarm-Protokoll ohne Schlüssel: gesperrt wird nichts gelöscht, es wird also nie versiegelt. */
    private val noKeys = object : PortfolioSnapshotSeal.Keys {
        override fun lookup(): Nothing = throw UnsupportedOperationException("kein Schlüssel im Test")
        override fun discardIfBroken(error: Throwable) = Unit
    }

    @Before
    fun setUp() {
        openSeeded()
    }

    /** Frische In-Memory-Datenbank mit zwei Paaren, Notiz, Gruppe, Kursalarm und %-Alarm ohne Bezug. */
    private fun openSeeded() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = WatchRepository(
            db, db.getWatchDao(), AlarmLogStore(File(context.cacheDir, "alarm-log-lock-test"), noKeys),
            AlarmDeliveryMarkers(context),
        )
        runBlocking {
            watchId = checkNotNull(repo.addWatch(BINANCE, ExchangePair("BTC", "USDT", "BTCUSDT"), group = "Layer 1"))
            // Ohne Alarm: deleteWatchesWithoutAlarms hat ungesperrt etwas zu löschen
            plainWatchId = checkNotNull(repo.addWatch(BINANCE, ExchangePair("ETH", "USDT", "ETHUSDT")))
            repo.setNote(watchId, "Ø 77 500")
            alarmId = checkNotNull(
                repo.saveAlarm(AlarmEntity(watchId = watchId, condition = AlarmCondition.PRICE_ABOVE, threshold = 100_000.0)),
            )
            // Ohne Bezug (referencePrice null): setMissingPercentReferences hat ungesperrt etwas zu schreiben
            percentAlarmId = checkNotNull(
                repo.saveAlarm(AlarmEntity(watchId = watchId, condition = AlarmCondition.CHANGE_PERCENT_UP, threshold = 5.0)),
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
     * Jeder Schreibweg des Nutzers einmal, mit Argumenten, die ungesperrt etwas ändern. Der Parameter sagt, ob die
     * Merkliste gerade gesperrt ist; Rückgabe-Prüfungen gleich mit (gesperrt: «gesperrt», null bzw. nichts angelegt;
     * ungesperrt: geschrieben bzw. angelegt).
     */
    private fun userWrites(): Map<String, suspend (Boolean) -> Unit> {
        // Ausdrücklich als suspend-Lambda (mit «to» allein wäre es ein gewöhnliches Lambda)
        fun write(block: suspend (locked: Boolean) -> Unit): suspend (Boolean) -> Unit = block
        val btc = ExchangePair("BTC", "USDT", "BTCUSDT")
        val sol = ExchangePair("SOL", "USDT", "SOLUSDT")
        return mapOf(
            "changePair" to write { locked ->
                val expected = if (locked) PairEditResult.LOCKED else PairEditResult.SAVED
                assertEquals(expected, repo.changePair(watchId, BINANCE, sol))
            },
            "setMissingPercentReferences" to write { repo.setMissingPercentReferences(watchId, 99_000.0) },
            "deleteWatch" to write { locked -> assertEquals(!locked, repo.deleteWatch(watchId)) },
            "deleteWatches" to write { locked -> assertEquals(!locked, repo.deleteWatches(listOf(watchId))) },
            // Undo der Sammelaufnahme: nur das Paar ohne Alarm geht, das mit Alarm bleibt
            "deleteWatchesWithoutAlarms" to write { locked ->
                assertEquals(!locked, repo.deleteWatchesWithoutAlarms(listOf(watchId, plainWatchId)))
                if (!locked) assertEquals(listOf(watchId), repo.getWatches().map { it.id })
            },
            "deleteAllWatches" to write { locked -> assertEquals(!locked, repo.deleteAllWatches()) },
            "setNotificationEnabled" to write { locked -> assertEquals(!locked, repo.setNotificationEnabled(watchId, false)) },
            "setFavorite" to write { repo.setFavorite(watchId, true) },
            "move" to write { repo.move(watchId, WatchMove.BOTTOM) },
            "reorder" to write { repo.reorder(repo.getWatches().map { it.id }.reversed()) },
            "setNote" to write { repo.setNote(watchId, "neu") },
            "setGroup" to write { repo.setGroup(watchId, "Neu") },
            "applyGroupEdit" to write { repo.applyGroupEdit("Layer 1", "Neu", setOf(watchId)) },
            "clearGroup" to write { repo.clearGroup("Layer 1") },
            "setTtsEnabled" to write { locked -> assertEquals(!locked, repo.setTtsEnabled(watchId, true)) },
            "setDisabled" to write { locked -> assertEquals(!locked, repo.setDisabled(listOf(watchId), true)) },
            "setNotifiedPrice" to write { repo.setNotifiedPrice(watchId, 98_000.0) },
            "saveAlarm" to write { locked ->
                val id = repo.saveAlarm(AlarmEntity(watchId = watchId, condition = AlarmCondition.PRICE_BELOW, threshold = 1.0))
                assertEquals(locked, id == null)
            },
            "saveAlarmMerged" to write { locked ->
                assertEquals(locked, repo.saveAlarmMerged(alarmId) { it!!.copy(threshold = 120_000.0) } == null)
            },
            // Eindeutiges Ergebnis (gespeichert / voll / abgelehnt)
            "trySaveAlarm" to write { locked ->
                val r = repo.trySaveAlarm(AlarmEntity(watchId = watchId, condition = AlarmCondition.PRICE_BELOW, threshold = 2.0))
                if (locked) assertEquals(AlarmSaveResult.Refused, r) else assertTrue(r is AlarmSaveResult.Saved)
            },
            "trySaveAlarmMerged" to write { locked ->
                val r = repo.trySaveAlarmMerged(alarmId) { it!!.copy(threshold = 130_000.0) }
                if (locked) assertEquals(AlarmSaveResult.Refused, r) else assertTrue(r is AlarmSaveResult.Saved)
            },
            "deleteAlarm" to write { locked -> assertEquals(!locked, repo.deleteAlarm(alarmId)) },
            "setAlarmEnabled" to write { locked -> assertEquals(!locked, repo.setAlarmEnabled(alarmId, false)) },
            "addWatch" to write { locked -> assertEquals(locked, repo.addWatch(BINANCE, sol) == null) },
            "tryAddWatch" to write { locked ->
                val outcome = repo.tryAddWatch(BINANCE, sol)
                if (locked) assertEquals(AddWatchOutcome.Locked, outcome) else assertTrue(outcome is AddWatchOutcome.Added)
            },
            "addWatches" to write { locked -> assertEquals(locked, repo.addWatches(BINANCE, listOf(sol, btc)).locked) },
            "restore" to write { locked ->
                val gone = checkNotNull(repo.getWatch(watchId)).copy(id = 99, baseAsset = "SOL", pairId = "SOLUSDT")
                val expected = if (locked) RestoreOutcome.BLOCKED else RestoreOutcome.RESTORED
                assertEquals(expected, repo.restore(WatchSnapshot(gone, emptyList())))
            },
        )
    }

    @Test
    fun lockedWatchlistIgnoresEveryUserWrite() = runBlocking {
        val before = dump()
        DatabaseEncryptionNotice.markInMemory(AppDatabase.DB_NAME, storedKept = true)
        for ((name, write) in userWrites()) {
            write(true)
            assertEquals("$name hat trotz Sperre geschrieben", before, dump())
        }
    }

    /**
     * Gegenprobe pro Eintrag: ungesperrt ändert jeder Schreibweg die Tabellen — auf einer frischen
     * Datenbank je Eintrag, damit kein früherer Schreibweg den Ausgangsstand verschiebt. Sonst bewiese der Sperr-Test
     * für diesen Eintrag nichts.
     */
    @Test
    fun everyUserWriteChangesTablesWhenUnlocked() = runBlocking {
        for (name in userWrites().keys) {
            db.close()
            openSeeded()
            val before = dump()
            checkNotNull(userWrites()[name]).invoke(false)
            assertNotEquals("$name ändert ungesperrt nichts (Gegenprobe zum Sperr-Test)", before, dump())
        }
        // Der %-Alarm hatte keinen Bezug, setMissingPercentReferences hat ihn gesetzt (frische Datenbank, nur dieser Weg)
        db.close()
        openSeeded()
        checkNotNull(userWrites()["setMissingPercentReferences"]).invoke(false)
        assertEquals(99_000.0, checkNotNull(db.getWatchDao().getAlarm(percentAlarmId)).referencePrice)
    }

    /**
     * F-08, Review ARC3-R01/R02: nur im Arbeitsspeicher ohne gespeicherten Stand und mit sichtbarer
     * Oberfläche lehnt jeder Schreibweg des Nutzers OHNE vorherige Bestätigung (`confirmUserWrite`) ab — eindeutig und ohne
     * selbst zu fragen oder zu warten. Folge-Schreibwege laufen nur nach einem bestätigten und lehnen nie ab.
     */
    @Test
    fun unconfirmedUserWritesAreRefusedWhenNotSaved() = runBlocking {
        WatchlistNotSavedGate.resetForTest()
        DatabaseEncryptionNotice.markInMemory(AppDatabase.DB_NAME)
        // Sichtbare Oberfläche: jemand sammelt die Frage (wie WatchlistNotSavedGateHost)
        val visible = launch(Dispatchers.Default) { WatchlistNotSavedGate.request.collect {} }
        try {
            withTimeout(5_000) { while (!WatchlistNotSavedGate.needsConfirmation()) delay(5) }
            val before = dump()
            for ((name, write) in userWrites()) {
                if (name in FOLLOW_UPS) continue
                write(true)
                assertEquals("$name hat ohne Bestätigung geschrieben", before, dump())
                assertEquals("$name hat selbst gefragt", null, WatchlistNotSavedGate.request.value)
            }
        } finally {
            visible.cancelAndJoin()
            WatchlistNotSavedGate.resetForTest()
        }
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
        val BINANCE = ExchangeInfo("Binance", "Binance")

        /** Dateien mit Erweiterungen von [WatchRepository] (Klassen der Kotlin-Dateien). */
        val EXTENSION_FILES = listOf("WatchRepositoryAddKt", "WatchRepositoryRefreshKt", "WatchRepositoryAlarmSaveKt")

        /** Folge-Schreibwege (nur nach einem bestätigten Schreibweg, `userWriteRefused(notify = false)`): lehnen nie ab. */
        val FOLLOW_UPS = setOf("setMissingPercentReferences", "setNotifiedPrice")

        /** Nur lesen. */
        val READS = setOf("getWatches", "getWatch", "snapshot", "getEnabledAlarms", "getAllEnabledAlarms", "getAllAlarmIds")

        /**
         * Hintergrund (Kurse, Fehler, Alarm-Zustand): schreibt bewusst weiter in die leere Datenbank im
         * Arbeitsspeicher, siehe `WatchRepositoryLock.kt`.
         */
        val BACKGROUND = setOf(
            "clearChanges", "updatePrice", "writePrice", "writeError", "updateError", "fillChange",
            "applyRefresh", "setNotifiedPrices", "markAlarmTriggered", "setAlarmReferenceIfUnchanged", "rearmAlarm",
            "rearmAlarmIfUnchanged",
        )
    }
}
