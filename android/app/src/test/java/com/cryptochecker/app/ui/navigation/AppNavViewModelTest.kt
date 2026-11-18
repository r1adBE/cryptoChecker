package com.cryptochecker.app.ui.navigation

import app.cash.turbine.test
import com.cryptochecker.app.domain.settings.AppSettings
import com.cryptochecker.app.ui.fakes.FakeSettingsRepository
import com.cryptochecker.app.ui.fakes.ViewModelTestRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Navigation ([AppNavViewModel]): Portfolio-Tab und «Beträge verbergen» aus den Einstellungen. */
class AppNavViewModelTest {

    @get:Rule
    val rule = ViewModelTestRule()

    private val settings = FakeSettingsRepository()

    private fun viewModel() = rule.hold { AppNavViewModel(settings) }

    @Test
    fun `starts with the cached settings before the file is read`() {
        settings.cachedValue = AppSettings(portfolioEnabled = true, hidePortfolioAmounts = true)
        val vm = viewModel()
        assertTrue(vm.portfolioEnabled.value)
        assertTrue(vm.hidePortfolioAmounts.value)
    }

    @Test
    fun `follows the stored settings`() = runTest(rule.dispatcher) {
        val vm = viewModel()
        vm.portfolioEnabled.test {
            assertFalse(awaitItem())
            settings.state.value = AppSettings(portfolioEnabled = true)
            assertTrue(awaitItem())
            settings.state.value = AppSettings(portfolioEnabled = false)
            assertFalse(awaitItem())
        }
    }

    @Test
    fun `other settings do not emit the tab switch again`() = runTest(rule.dispatcher) {
        val vm = viewModel()
        vm.portfolioEnabled.test {
            assertFalse(awaitItem())
            settings.state.value = AppSettings(hidePortfolioAmounts = true)
            testScheduler.advanceUntilIdle()
            settings.state.value = AppSettings(hidePortfolioAmounts = true, marketMoreExpanded = true)
            testScheduler.advanceUntilIdle()
            expectNoEvents()
        }
        assertTrue(vm.hidePortfolioAmounts.value)
    }
}
