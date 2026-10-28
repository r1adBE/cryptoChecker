package com.cryptochecker.app

import android.app.NotificationManager
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.cryptochecker.app.data.RefreshStats
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.addWatch
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.refresh.PriceRefresher
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.testing.FakeExchange
import com.cryptochecker.app.work.PriceUpdateWorker
import com.cryptochecker.marketdata.model.ExchangePair
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Hintergrund-Prüfung von Anfang bis Ende: Der echte [PriceUpdateWorker] (über die
 * [HiltWorkerFactory] wie in der App) fragt die Fake-Börse ab ([FakeExchange], kein Netz), wertet den Alarm aus, vermerkt
 * ihn als ausgelöst und postet die Alarm-Mitteilung — ohne Activity, wie ein geplanter WorkManager-Lauf bei
 * geschlossener App. Ergänzt die reinen Regeln (BackgroundWorkPolicyTest, JVM) und den Vordergrund-Weg (CoreFlowTest).
 * Ein geplanter Lauf liefert fast immer `Result.success()` (auch bei Fehlern, siehe
 * `BackgroundWorkPolicy.outcome`) — das Ergebnis prüft darum der Fall «Merkliste nur leer im Arbeitsspeicher» (retry).
 */
@HiltAndroidTest
class PriceUpdateWorkerTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var watchRepository: WatchRepository
    @Inject lateinit var priceRefresher: PriceRefresher
    @Inject lateinit var refreshStats: RefreshStats

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val notifications: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        hiltRule.inject()
        notifications.cancelAll()
        runBlocking { watchRepository.deleteAllWatches() }
    }

    @After
    fun tearDown() {
        notifications.cancelAll()
        runBlocking { watchRepository.deleteAllWatches() }
    }

    private fun alarmNotificationShown(): Boolean =
        notifications.activeNotifications.any { it.notification.channelId?.startsWith("alarms") == true }

    @Test
    fun scheduledRunTriggersAlarmAndPostsNotification() = runBlocking {
        // Paar mit erstem Kurs 95’000 (unter der Marke), dann Alarm «über 100’000»
        val watchId = checkNotNull(watchRepository.addWatch(ExchangeInfo("Binance", "Binance"), ExchangePair("BTC", "USDT", "BTCUSDT")))
        priceRefresher.refreshOne(watchId)
        val alarmId = checkNotNull(
            watchRepository.saveAlarm(
                AlarmEntity(watchId = watchId, condition = AlarmCondition.PRICE_ABOVE, threshold = 100_000.0, repeating = true),
            ),
        )
        assertTrue("vor dem Lauf keine Alarm-Mitteilung", !alarmNotificationShown())
        // Kurs steigt; der geplante Lauf darf nicht als «eben erst geprüft» übersprungen werden
        FakeExchange.btcPrice = 105_000.0
        refreshStats.setLastFullCheck(0)

        val worker = TestListenableWorkerBuilder<PriceUpdateWorker>(context)
            .setWorkerFactory(workerFactory)
            .build()
        worker.doWork()

        // Kein Vergleich mit Result.success(): das liefert ein geplanter Lauf immer
        val alarm = watchRepository.observeAlarms(watchId).first().single { it.id == alarmId }
        assertTrue("Alarm als ausgelöst vermerkt", alarm.lastTriggeredAt > 0)
        assertEquals(105_000.0, alarm.lastTriggeredPrice ?: 0.0, 0.0)
        // Mitteilung: auf den Zustand warten statt fester Zeit (das System übernimmt sie asynchron)
        val shown = withTimeoutOrNull(TIMEOUT_MILLIS) {
            while (!alarmNotificationShown()) delay(50)
            true
        }
        assertEquals("Alarm-Mitteilung gepostet", true, shown)
        // Vermerkt als volle Prüfung: ein gleich folgender geplanter Lauf würde übersprungen
        assertTrue(refreshStats.lastFullCheck() > 0)
    }

    /**
     * Merkliste in diesem Prozess nur leer im Arbeitsspeicher ([DatabaseEncryptionNotice.storedKept],
     * Schlüssel gerade nicht lesbar) — der Lauf prüft nichts, meldet `Result.retry()` (kurzer Backoff), löst keinen Alarm
     * aus und postet keine Mitteilung, obwohl der Kurs über der Marke liegt.
     */
    @Test
    fun storedKeptRunRetriesWithoutAlarm() = runBlocking {
        val watchId = checkNotNull(watchRepository.addWatch(ExchangeInfo("Binance", "Binance"), ExchangePair("BTC", "USDT", "BTCUSDT")))
        priceRefresher.refreshOne(watchId)
        val alarmId = checkNotNull(
            watchRepository.saveAlarm(
                AlarmEntity(watchId = watchId, condition = AlarmCondition.PRICE_ABOVE, threshold = 100_000.0, repeating = true),
            ),
        )
        FakeExchange.btcPrice = 105_000.0
        refreshStats.setLastFullCheck(0)
        val result = try {
            DatabaseEncryptionNotice.markInMemory(AppDatabase.DB_NAME, storedKept = true)
            TestListenableWorkerBuilder<PriceUpdateWorker>(context)
                .setWorkerFactory(workerFactory)
                .build()
                .doWork()
        } finally {
            DatabaseEncryptionNotice.clearInMemoryForTest(AppDatabase.DB_NAME)
        }

        assertEquals(ListenableWorker.Result.retry(), result)
        val alarm = watchRepository.observeAlarms(watchId).first().single { it.id == alarmId }
        assertEquals("nicht als ausgelöst vermerkt", 0L, alarm.lastTriggeredAt)
        // Kurz warten: eine (fälschlich) gepostete Mitteilung käme asynchron
        delay(500)
        assertTrue("keine Alarm-Mitteilung", !alarmNotificationShown())
        assertEquals("nicht als volle Prüfung vermerkt", 0L, refreshStats.lastFullCheck())
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000L
    }
}
