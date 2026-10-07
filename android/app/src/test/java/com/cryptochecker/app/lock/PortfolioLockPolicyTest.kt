package com.cryptochecker.app.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioLockPolicyTest {

    @Test
    fun accessFollowsSettingAndSession() {
        assertEquals(PortfolioAccess.LOCKED, PortfolioLockPolicy.access(lockSetting = true, lockRequested = true))
        assertEquals(PortfolioAccess.OPEN, PortfolioLockPolicy.access(lockSetting = true, lockRequested = false))
        assertEquals(PortfolioAccess.OPEN, PortfolioLockPolicy.access(lockSetting = false, lockRequested = true))
        assertEquals(PortfolioAccess.OPEN, PortfolioLockPolicy.access(lockSetting = false, lockRequested = false))
    }

    @Test
    fun unknownSettingWaitsOnlyWhileALockWouldBeDue() {
        assertEquals(PortfolioAccess.PENDING, PortfolioLockPolicy.access(lockSetting = null, lockRequested = true))
        assertEquals(PortfolioAccess.OPEN, PortfolioLockPolicy.access(lockSetting = null, lockRequested = false))
    }

    @Test
    fun isLockedNeedsBoth() {
        assertTrue(PortfolioLockPolicy.isLocked(lockSetting = true, lockRequested = true))
        assertFalse(PortfolioLockPolicy.isLocked(lockSetting = true, lockRequested = false))
        assertFalse(PortfolioLockPolicy.isLocked(lockSetting = false, lockRequested = true))
    }

    @Test
    fun relocksOnlyAfterMoreThanTheLimit() {
        val limit = PortfolioLockPolicy.BACKGROUND_LIMIT_MILLIS
        assertEquals(60_000L, limit)
        assertFalse(PortfolioLockPolicy.relockAfterBackground(backgroundSince = 0L, now = 10 * limit))
        assertFalse(PortfolioLockPolicy.relockAfterBackground(backgroundSince = 1_000L, now = 1_000L + limit))
        assertTrue(PortfolioLockPolicy.relockAfterBackground(backgroundSince = 1_000L, now = 1_001L + limit))
        assertFalse(PortfolioLockPolicy.relockAfterBackground(backgroundSince = 1_000L, now = 5_000L))
    }

    @Test
    fun backupAndRestoreGates() {
        assertTrue(PortfolioLockPolicy.backupExportNeedsUnlock(locked = true, hasPortfolioData = true))
        assertFalse(PortfolioLockPolicy.backupExportNeedsUnlock(locked = true, hasPortfolioData = false))
        assertFalse(PortfolioLockPolicy.backupExportNeedsUnlock(locked = false, hasPortfolioData = true))
        assertTrue(PortfolioLockPolicy.restoreNeedsUnlock(locked = true))
        assertFalse(PortfolioLockPolicy.restoreNeedsUnlock(locked = false))
    }

    @Test
    fun settingsAndQuickAddGates() {
        assertTrue(PortfolioLockPolicy.disableNeedsUnlock(locked = true))
        assertFalse(PortfolioLockPolicy.disableNeedsUnlock(locked = false))
        assertTrue(PortfolioLockPolicy.quickAddNeedsUnlock(locked = true))
        assertFalse(PortfolioLockPolicy.quickAddNeedsUnlock(locked = false))
        assertTrue(PortfolioLockPolicy.showSetting(portfolioEnabled = true))
        assertFalse(PortfolioLockPolicy.showSetting(portfolioEnabled = false))
    }

    @Test
    fun widgetIgnoresTheSession() {
        assertTrue(PortfolioLockPolicy.widgetLocked(lockSetting = true))
        assertFalse(PortfolioLockPolicy.widgetLocked(lockSetting = false))
    }
}
