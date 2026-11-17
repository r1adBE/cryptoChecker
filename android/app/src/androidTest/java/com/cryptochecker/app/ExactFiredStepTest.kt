package com.cryptochecker.app

import com.cryptochecker.app.domain.refresh.CheckResult
import com.cryptochecker.app.domain.refresh.CheckRunStore
import com.cryptochecker.app.domain.refresh.CheckTrigger
import com.cryptochecker.app.domain.work.BackgroundWorkPolicy.FiredAction
import com.cryptochecker.app.testing.AppTestEnvironmentRule
import com.cryptochecker.app.work.ExactFiredStep
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Wecker «Pünktlich prüfen» ohne Netz: kein Versuch, aber ein ehrlicher Eintrag
 * «offline» (Auslöser exakt) in «Letzte Prüfungen»; Absagen vermerkt nichts; mit Netz wird geprüft.
 */
@HiltAndroidTest
class ExactFiredStepTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val environment = AppTestEnvironmentRule()

    @Inject lateinit var step: ExactFiredStep

    @Inject lateinit var checkRuns: CheckRunStore

    @Before
    fun inject() = hiltRule.inject()

    private fun latestAfter(startedAt: Long) = checkRuns.runs.value.filter { it.at >= startedAt }.maxByOrNull { it.at }

    @Test
    fun offlineIsLoggedAsOfflineAndNotChecked() {
        val startedAt = System.currentTimeMillis()
        assertFalse(step.apply(FiredAction.REARM_SKIP_OFFLINE, enabled = true, hasWork = true, intervalMinutes = 15, startedAt = startedAt))
        val run = checkNotNull(latestAfter(startedAt)) { "kein Eintrag vermerkt" }
        assertEquals(CheckTrigger.EXACT, run.trigger)
        assertEquals(CheckResult.OFFLINE, run.result)
    }

    @Test
    fun cancelLogsNothingAndOnlineChecks() {
        val startedAt = System.currentTimeMillis()
        assertFalse(step.apply(FiredAction.CANCEL, enabled = false, hasWork = true, intervalMinutes = 15, startedAt = startedAt))
        assertTrue(step.apply(FiredAction.REARM_AND_CHECK, enabled = true, hasWork = true, intervalMinutes = 15, startedAt = startedAt))
        assertEquals("Absagen und «prüfen» vermerken selbst nichts", null, latestAfter(startedAt))
    }
}
