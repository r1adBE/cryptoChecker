package com.cryptochecker.app.ui.features.alarms

import com.cryptochecker.app.data.GuardedWrites
import com.cryptochecker.app.domain.alarm.PortfolioAlarmEntity
import com.cryptochecker.app.domain.alarm.PortfolioAlarmKind
import com.cryptochecker.app.domain.alarm.PortfolioAlarmPause
import com.cryptochecker.app.domain.alarm.PortfolioAlarmStore
import com.cryptochecker.app.domain.model.AlarmCondition
import com.cryptochecker.app.domain.model.AlarmEntity
import com.cryptochecker.app.domain.model.AlarmWithWatch
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot
import com.cryptochecker.app.domain.settings.AppSettings
import com.cryptochecker.app.effects.AlarmNotifications
import com.cryptochecker.app.ui.fakes.FakeSettingsRepository
import com.cryptochecker.app.ui.fakes.FakeUiWatchStore
import com.cryptochecker.app.ui.fakes.ViewModelTestRule
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Alarm-Übersicht ([AlarmsOverviewViewModel]): Gruppen, Portfolio-Alarme, Pause-Hinweis und Schreibwege mit Mitteilungen. */
class AlarmsOverviewViewModelTest {

    @get:Rule
    val rule = ViewModelTestRule()

    private val settings = FakeSettingsRepository(AppSettings(portfolioEnabled = true))
    private val watchStore = FakeUiWatchStore()
    private val portfolioAlarms = FakePortfolioAlarms()
    private val notifications = FakeNotifications()
    private var snapshot: PortfolioSnapshot? = null
    private var snapshotReads = 0

    /** Gemeinsames Protokoll der Schreibwege und Mitteilungen in Reihenfolge. */
    private val log = mutableListOf<String>()

    private inner class FakePortfolioAlarms : PortfolioAlarmStore {
        val alarms = MutableStateFlow<List<PortfolioAlarmEntity>>(emptyList())
        var fail: Exception? = null

        override fun observeAll() = alarms

        override suspend fun setEnabled(id: Long, enabled: Boolean) {
            fail?.let { throw it }
            log += "portfolio.setEnabled($id, $enabled)"
        }

        override suspend fun delete(id: Long) {
            fail?.let { throw it }
            log += "portfolio.delete($id)"
        }
    }

    private inner class FakeNotifications : AlarmNotifications {
        override fun cancelAlarms(alarmIds: Collection<Long>, portfolioAlarmIds: Collection<Long>) {
            log += "cancelAlarms($alarmIds, $portfolioAlarmIds)"
        }

        override suspend fun retractAlarm(alarmId: Long) {
            log += "retractAlarm($alarmId)"
        }
    }

    private fun viewModel() = rule.hold {
        AlarmsOverviewViewModel(
            watchStore,
            portfolioAlarms,
            settings,
            notifications,
            {
                snapshotReads++
                snapshot
            },
            rule.dispatcher,
            rule.dispatcher,
            GuardedWrites(),
        )
    }

    /** Hält die WhileSubscribed-Flows wach wie eine sichtbare Ansicht. */
    private fun TestScope.observe(vm: AlarmsOverviewViewModel) {
        backgroundScope.launch { vm.groups.collect() }
        backgroundScope.launch { vm.portfolioPause.collect() }
    }

    private fun watch(id: Long, base: String) = WatchEntity(
        id = id,
        exchangeKey = "Binance",
        exchangeName = "Binance",
        baseAsset = base,
        quoteAsset = "USDT",
    )

    private fun alarm(id: Long, watch: WatchEntity, enabled: Boolean) =
        AlarmWithWatch(
            AlarmEntity(id = id, watchId = watch.id, condition = AlarmCondition.PRICE_ABOVE, threshold = 1.0, enabled = enabled),
            watch
        )

    @Test
    fun `groups armed pairs first, then by name, armed alarms first within a pair`() = runTest(rule.dispatcher) {
        val eth = watch(1, "ETH")
        val btc = watch(2, "BTC")
        val ada = watch(3, "ADA")
        watchStore.alarms.value = listOf(
            alarm(10, eth, enabled = false),
            alarm(11, eth, enabled = true),
            alarm(12, btc, enabled = true),
            alarm(13, ada, enabled = false),
        )
        val vm = viewModel()
        observe(vm)
        advanceUntilIdle()
        assertEquals(
            listOf(listOf(12L), listOf(11L, 10L), listOf(13L)),
            vm.groups.value.map { group -> group.map { it.alarm.id } },
        )
    }

    @Test
    fun `portfolio alarms only with the portfolio switched on`() = runTest(rule.dispatcher) {
        portfolioAlarms.alarms.value = listOf(PortfolioAlarmEntity(id = 5, kind = PortfolioAlarmKind.VALUE_ABOVE, threshold = 100.0))
        val vm = viewModel()
        backgroundScope.launch { vm.portfolioAlarms.collect() }
        advanceUntilIdle()
        assertEquals(listOf(5L), vm.portfolioAlarms.value.map { it.id })

        settings.state.value = AppSettings(portfolioEnabled = false)
        advanceUntilIdle()
        assertTrue(vm.portfolioAlarms.value.isEmpty())
    }

    @Test
    fun `pause hint reads the snapshot only for armed portfolio alarms`() = runTest(rule.dispatcher) {
        snapshot =
            PortfolioSnapshot(
                total = 1.0,
                changeAmount = null,
                changePercent = null,
                currency = "USD",
                time = 1,
                empty = false,
                addressesPending = 2
            )
        portfolioAlarms.alarms.value =
            listOf(PortfolioAlarmEntity(id = 5, kind = PortfolioAlarmKind.VALUE_ABOVE, threshold = 1.0, enabled = false))
        val vm = viewModel()
        observe(vm)
        advanceUntilIdle()
        assertNull(vm.portfolioPause.value)
        assertEquals(0, snapshotReads)

        portfolioAlarms.alarms.value = listOf(PortfolioAlarmEntity(id = 5, kind = PortfolioAlarmKind.VALUE_ABOVE, threshold = 1.0))
        advanceUntilIdle()
        assertEquals(PortfolioAlarmPause.AddressesPending(2), vm.portfolioPause.value)
        assertEquals(1, snapshotReads)
    }

    @Test
    fun `no snapshot means no pause hint`() = runTest(rule.dispatcher) {
        portfolioAlarms.alarms.value = listOf(PortfolioAlarmEntity(id = 5, kind = PortfolioAlarmKind.CHANGE_UP, threshold = 5.0))
        val vm = viewModel()
        observe(vm)
        advanceUntilIdle()
        assertNull(vm.portfolioPause.value)
        assertEquals(1, snapshotReads)
    }

    @Test
    fun `switching a pair alarm off retracts its notification`() = runTest(rule.dispatcher) {
        val vm = viewModel()
        vm.setEnabled(7, enabled = false)
        advanceUntilIdle()
        assertEquals(listOf("confirmUserWrite()", "setAlarmEnabled(7, false)"), watchStore.calls)
        assertEquals(listOf("retractAlarm(7)"), log)
    }

    @Test
    fun `switching a pair alarm on keeps notifications`() = runTest(rule.dispatcher) {
        val vm = viewModel()
        vm.setEnabled(7, enabled = true)
        advanceUntilIdle()
        assertEquals(listOf("confirmUserWrite()", "setAlarmEnabled(7, true)"), watchStore.calls)
        assertTrue(log.isEmpty())
    }

    @Test
    fun `cancelled confirmation writes nothing`() = runTest(rule.dispatcher) {
        watchStore.confirm = false
        val vm = viewModel()
        vm.setEnabled(7, enabled = false)
        advanceUntilIdle()
        assertEquals(listOf("confirmUserWrite()"), watchStore.calls)
        assertTrue(log.isEmpty())
    }

    @Test
    fun `refused write on a locked watchlist keeps the notification`() = runTest(rule.dispatcher) {
        watchStore.writesAllowed = false
        val vm = viewModel()
        vm.setEnabled(7, enabled = false)
        advanceUntilIdle()
        assertEquals(listOf("confirmUserWrite()", "setAlarmEnabled(7, false)"), watchStore.calls)
        assertTrue(log.isEmpty())
    }

    @Test
    fun `deleting a portfolio alarm removes its notification afterwards`() = runTest(rule.dispatcher) {
        val vm = viewModel()
        vm.deletePortfolioAlarm(5)
        advanceUntilIdle()
        assertEquals(listOf("portfolio.delete(5)", "cancelAlarms([], [5])"), log)
    }

    @Test
    fun `failed delete keeps the notification`() = runTest(rule.dispatcher) {
        portfolioAlarms.fail = IOException("disk full")
        val vm = viewModel()
        vm.deletePortfolioAlarm(5)
        advanceUntilIdle()
        assertTrue(log.isEmpty())
    }

    @Test
    fun `portfolio alarm switch survives a failed write`() = runTest(rule.dispatcher) {
        val vm = viewModel()
        vm.setPortfolioAlarmEnabled(5, enabled = true)
        advanceUntilIdle()
        assertEquals(listOf("portfolio.setEnabled(5, true)"), log)

        portfolioAlarms.fail = IOException("disk full")
        vm.setPortfolioAlarmEnabled(5, enabled = false)
        advanceUntilIdle()
        assertEquals(listOf("portfolio.setEnabled(5, true)"), log)
    }
}
