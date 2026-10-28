package com.cryptochecker.app

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.data.BackupManager
import com.cryptochecker.app.data.RestoreJournal
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.local.StorageRecoveryNotice
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.addWatch
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.refresh.PriceRefresher
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.marketdata.model.ExchangePair
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.inject.Inject

/**
 * Wiederherstellen einer Sicherung von Anfang bis Ende mit den echten Datenbanken (Hilt, Gerät/Emulator): Merkliste
 * samt Alarmen wird ersetzt; das Journal steht, bevor das Erste geschrieben wird, und ist danach weg; ein Abbruch
 * mittendrin lässt die Daten stehen; ein liegengebliebenes Journal (Prozess beendet) rollt beim nächsten Start zurück;
 * eine Sicherung ohne Passwort schaltet die Portfolio-Sperre nicht aus ([com.cryptochecker.app.data.RestoreResult.appLockKept]).
 *
 * Die Refresh-Sperre ([PriceRefresher.withRefreshLock]) hält das Wiederherstellen kontrolliert vor dem ersten
 * Schreibvorgang an (es ersetzt die Merkliste nur unter ihr).
 */
@HiltAndroidTest
class BackupRestoreTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @Inject
    lateinit var backupManager: BackupManager

    @Inject
    lateinit var watchRepository: WatchRepository

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var priceRefresher: PriceRefresher

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val journalFile get() = File(context.noBackupFilesDir, RestoreJournal.FILE_NAME)

    private lateinit var backupFile: File

    @Before
    fun setUp() {
        hiltRule.inject()
        backupFile = File(context.cacheDir, "restore_test_backup.json").apply { writeBytes(ByteArray(0)) }
        runBlocking {
            settingsRepository.setAboutSeen(true)
            settingsRepository.setAppLock(false)
            watchRepository.deleteAllWatches()
        }
        journalFile.delete()
    }

    @After
    fun tearDown() {
        runBlocking {
            settingsRepository.setAppLock(false)
            watchRepository.deleteAllWatches()
        }
        journalFile.delete()
        backupFile.delete()
        // Kein Hinweis «Wiederherstellen abgebrochen» für die folgenden Oberflächen-Tests stehen lassen
        StorageRecoveryNotice.acknowledge(context, StorageRecoveryNotice.Kind.RESTORE_ROLLED_BACK)
    }

    private val uri: Uri get() = Uri.fromFile(backupFile)

    private suspend fun add(base: String): Long =
        checkNotNull(watchRepository.addWatch(ExchangeInfo("Binance", "Binance"), ExchangePair(base, "USDT", "${base}USDT")))

    private suspend fun bases(): List<String> = watchRepository.getWatches().map { it.baseAsset }.sorted()

    /** Gesichert: BTC mit einem Alarm. Danach steht ETH in der Merkliste (der Stand, den die Sicherung ersetzt). */
    private suspend fun backupBtcThenSwitchToEth() {
        val btc = add("BTC")
        watchRepository.saveAlarm(AlarmEntity(watchId = btc, condition = AlarmCondition.PRICE_ABOVE, threshold = 1_000_000.0))
        backupManager.export(uri, password = null)
        assertTrue(backupFile.length() > 0)
        watchRepository.deleteAllWatches()
        add("ETH")
    }

    /** Hält die Refresh-Sperre, bis [release] erfüllt ist; [held] meldet, dass sie gehalten wird. */
    private fun holdRefreshLock(held: CompletableDeferred<Unit>, release: CompletableDeferred<Unit>) =
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            priceRefresher.withRefreshLock {
                held.complete(Unit)
                release.await()
            }
        }

    private suspend fun awaitJournal() {
        withTimeout(TIMEOUT) { while (!journalFile.exists()) delay(20) }
    }

    @Test
    fun restoreReplacesWatchlistWithAlarmsAndRemovesTheJournal() = runBlocking {
        backupBtcThenSwitchToEth()
        val ethId = watchRepository.getWatches().single().id

        val result = backupManager.restore(uri, password = null)

        assertEquals(1, result.watches)
        assertEquals(1, result.alarms)
        assertEquals(listOf("BTC"), bases())
        assertEquals(1, watchRepository.getAllEnabledAlarms().size)
        assertEquals(listOf(ethId), result.replacedWatchIds)
        assertFalse("Journal nach dem Wiederherstellen weg", journalFile.exists())
    }

    @Test
    fun journalIsWrittenBeforeTheFirstWrite() = runBlocking {
        backupBtcThenSwitchToEth()
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = holdRefreshLock(held, release)
        withTimeout(TIMEOUT) { held.await() }

        val restore = async(Dispatchers.IO) { backupManager.restore(uri, password = null) }
        // Das Wiederherstellen wartet vor dem Ersetzen der Merkliste auf die Sperre: das Journal steht schon …
        awaitJournal()
        // … geschrieben ist noch nichts
        assertEquals(listOf("ETH"), bases())

        release.complete(Unit)
        withTimeout(TIMEOUT) { restore.await() }
        holder.join()
        assertEquals(listOf("BTC"), bases())
        assertFalse(journalFile.exists())
    }

    @Test
    fun cancelledRestoreKeepsTheDataAndRemovesTheJournal() = runBlocking {
        backupBtcThenSwitchToEth()
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = holdRefreshLock(held, release)
        withTimeout(TIMEOUT) { held.await() }

        val restore = async(Dispatchers.IO) { backupManager.restore(uri, password = null) }
        awaitJournal()
        restore.cancel()
        try {
            restore.await()
            fail("abgebrochen erwartet")
        } catch (expected: CancellationException) {
            // erwartet
        }
        release.complete(Unit)
        holder.join()

        // Zurückgerollt (hier: nichts geschrieben) — Daten unverändert, Journal entfernt
        assertEquals(listOf("ETH"), bases())
        assertFalse(journalFile.exists())
    }

    @Test
    fun leftoverJournalRollsBackOnTheNextStart() = runBlocking {
        backupBtcThenSwitchToEth()
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = holdRefreshLock(held, release)
        withTimeout(TIMEOUT) { held.await() }

        val restore = async(Dispatchers.IO) { backupManager.restore(uri, password = null) }
        awaitJournal()
        // Inhalt des Journals in dem Moment, in dem der Prozess hätte enden können
        val sealed = journalFile.readBytes()
        release.complete(Unit)
        withTimeout(TIMEOUT) { restore.await() }
        holder.join()
        assertEquals(listOf("BTC"), bases())

        // Wie nach einem Absturz mitten im Schreiben: Journal liegt noch da → Start schreibt den Stand davor zurück
        journalFile.writeBytes(sealed)
        backupManager.recoverInterruptedRestore()

        assertEquals(listOf("ETH"), bases())
        assertFalse(journalFile.exists())
        assertEquals(StorageRecoveryNotice.Kind.RESTORE_ROLLED_BACK, StorageRecoveryNotice.pending(context))
    }

    @Test
    fun backupWithoutPasswordKeepsTheAppLockOn() = runBlocking {
        // Gesichert mit Sperre aus …
        add("BTC")
        backupManager.export(uri, password = null)
        // … inzwischen eingeschaltet: eine Sicherung ohne Passwort darf sie nicht ausschalten
        settingsRepository.setAppLock(true)

        val result = backupManager.restore(uri, password = null)

        assertTrue(result.appLockKept)
        assertTrue(settingsRepository.current().appLock)
        assertEquals(listOf("BTC"), bases())
    }

    private companion object {
        const val TIMEOUT = 20_000L
    }
}
