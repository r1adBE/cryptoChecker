package com.cryptochecker.app

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.cryptochecker.app.domain.lock.PortfolioLockPolicy
import com.cryptochecker.app.domain.portfolio.CostMethod
import com.cryptochecker.app.domain.settings.SettingsRepository
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.ui.MainActivity
import com.cryptochecker.app.ui.features.settings.PortfolioPage
import com.cryptochecker.resources.R
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Einstellungen › Portfolio: eine andere Einstandsmethode und «Portfolio in
 * Systemsicherung» einschalten fragen erst — «Abbrechen» lässt den Wert, erst «Ändern» schreibt. Echte Seite mit dem
 * echten ViewModel. iOS: Regel portfolio-settings-confirm.
 */
@HiltAndroidTest
class PortfolioSettingsConfirmDeviceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var settingsRepository: SettingsRepository

    @Before
    fun setUp() {
        hiltRule.inject()
        runBlocking {
            settingsRepository.setPortfolioEnabled(true)
            settingsRepository.setCostMethod(CostMethod.FIFO)
            settingsRepository.setPortfolioSystemBackup(false)
        }
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme { PortfolioPage(onBack = {}) } } }
        compose.waitForIdle()
    }

    private fun text(id: Int) = compose.activity.getString(id)

    private fun method(): CostMethod = runBlocking { settingsRepository.settings.first().costMethod }

    private fun dialogButton(label: String, dialogText: String) =
        compose.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(hasAnyDescendant(hasText(dialogText))))

    @Test
    fun otherCostMethodAsksFirst() {
        val question = text(R.string.settings_cost_method_confirm)
        val average = compose.onAllNodes(hasText(text(R.string.settings_cost_method_average)), useUnmergedTree = true).onFirst()
        average.performScrollTo()
        average.performClick()
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(hasText(question)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("vor der Bestätigung geschrieben", CostMethod.FIFO, method())
        dialogButton(text(R.string.action_cancel), question).performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasText(question)).assertCountEquals(0)
        assertEquals(CostMethod.FIFO, method())
        average.performClick()
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(hasText(question)).fetchSemanticsNodes().isNotEmpty() }
        dialogButton(text(R.string.delivery_action_change), question).performClick()
        compose.waitUntil(TIMEOUT) { method() == CostMethod.AVERAGE }
    }

    @Test
    fun systemBackupAsksFirst() {
        assumeTrue(PortfolioLockPolicy.systemBackupSupported(Build.VERSION.SDK_INT))
        val question = text(R.string.settings_portfolio_system_backup_confirm)
        val switch = compose.onNode(isToggleable() and hasText(text(R.string.settings_portfolio_system_backup), substring = true))
        switch.performScrollTo()
        switch.performClick()
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(hasText(question)).fetchSemanticsNodes().isNotEmpty() }
        dialogButton(text(R.string.action_cancel), question).performClick()
        compose.waitForIdle()
        assertFalse(runBlocking { settingsRepository.settings.first().portfolioSystemBackup })
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
