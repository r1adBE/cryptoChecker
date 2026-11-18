package com.cryptochecker.app.ui.lock

import com.cryptochecker.app.domain.lock.PortfolioAccess
import com.cryptochecker.app.domain.settings.AppSettings
import com.cryptochecker.app.lock.AppLockState
import com.cryptochecker.app.ui.fakes.FakeSettingsRepository
import com.cryptochecker.app.ui.fakes.ViewModelTestRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Portfolio-Sperre der Oberfläche ([PortfolioLockViewModel]): Zugang, Ausschalten ohne Displaysperre, Entsperren. */
class PortfolioLockViewModelTest {

    @get:Rule
    val rule = ViewModelTestRule()

    private val settings = FakeSettingsRepository()

    /** Neuer Prozess: Sperre verlangt (Startwert von [AppLockState]). */
    private val lockState = AppLockState()

    private fun viewModel() = rule.hold { PortfolioLockViewModel(lockState, settings) }

    @Test
    fun `pending until the settings are read, then locked with the lock on`() = runTest(rule.dispatcher) {
        settings.state.value = AppSettings(appLock = true)
        val vm = viewModel()
        assertEquals(PortfolioAccess.PENDING, vm.access.value)
        advanceUntilIdle()
        assertEquals(PortfolioAccess.LOCKED, vm.access.value)
    }

    @Test
    fun `open without the lock setting and after unlocking`() = runTest(rule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(PortfolioAccess.OPEN, vm.access.value)

        settings.state.value = AppSettings(appLock = true)
        advanceUntilIdle()
        assertEquals(PortfolioAccess.LOCKED, vm.access.value)
        lockState.unlock()
        advanceUntilIdle()
        assertEquals(PortfolioAccess.OPEN, vm.access.value)
    }

    @Test
    fun `lock enabled follows the setting`() = runTest(rule.dispatcher) {
        settings.cachedValue = AppSettings(appLock = true)
        val vm = viewModel()
        assertTrue(vm.lockEnabled.value)
        advanceUntilIdle()
        assertFalse(vm.lockEnabled.value)
    }

    @Test
    fun `turning the lock off writes the setting and unlocks`() = runTest(rule.dispatcher) {
        settings.state.value = AppSettings(appLock = true)
        val vm = viewModel()
        vm.turnOffLockWithoutScreenLock()
        advanceUntilIdle()
        assertEquals(listOf("setAppLock(false)"), settings.calls)
        assertFalse(lockState.lockRequested.value)
    }

    @Test
    fun `a failed write keeps the portfolio locked`() = runTest(rule.dispatcher) {
        settings.state.value = AppSettings(appLock = true)
        settings.writeFails = true
        val vm = viewModel()
        vm.turnOffLockWithoutScreenLock()
        advanceUntilIdle()
        assertTrue(settings.calls.isEmpty())
        assertTrue(lockState.lockRequested.value)
        assertEquals(PortfolioAccess.LOCKED, vm.access.value)
    }

    @Test
    fun `require unlock runs at once when the portfolio is open`() = runTest(rule.dispatcher) {
        val vm = viewModel()
        var unlocked = 0
        vm.requireUnlock(activity = null, reason = "test") { unlocked++ }
        advanceUntilIdle()
        assertEquals(1, unlocked)
    }

    @Test
    fun `require unlock without an activity does nothing while locked`() = runTest(rule.dispatcher) {
        settings.state.value = AppSettings(appLock = true)
        val vm = viewModel()
        var unlocked = 0
        vm.requireUnlock(activity = null, reason = "test") { unlocked++ }
        advanceUntilIdle()
        assertEquals(0, unlocked)
        assertTrue(lockState.lockRequested.value)
    }
}
