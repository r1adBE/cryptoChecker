package com.cryptochecker.app.domain.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityAnalysisGateTest {

    @Test
    fun runsOnlyWithAConsumer() {
        assertFalse(ActivityAnalysisGate.shouldRun(alertsEnabled = false, appVisible = false))
        assertTrue(ActivityAnalysisGate.shouldRun(alertsEnabled = true, appVisible = false))
        assertTrue(ActivityAnalysisGate.shouldRun(alertsEnabled = false, appVisible = true))
        assertTrue(ActivityAnalysisGate.shouldRun(alertsEnabled = true, appVisible = true))
    }
}
