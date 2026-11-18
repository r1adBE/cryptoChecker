package com.cryptochecker.app.data.local

import com.cryptochecker.app.domain.local.StorageRecoveryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reihenfolge der Hinweise über der App ([StorageRecoveryNotice.next]) samt «Einstellungen zurückgesetzt» —
 * gleiche Fälle wie iOS `SettingsResetNoticeTests`.
 */
class SettingsResetNoticeTest {

    private fun next(
        restoreInterrupted: String? = null,
        keyRestored: Boolean = false,
        systemBackupNotImported: Boolean = false,
        settingsReset: Boolean = false,
        setAside: Boolean = false,
        setAsideHidden: Boolean = false,
    ) = StorageRecoveryNotice.next(restoreInterrupted, keyRestored, systemBackupNotImported, settingsReset, setAside, setAsideHidden)

    @Test
    fun nothingPendingNoNotice() {
        assertNull(next())
    }

    @Test
    fun settingsResetAloneIsShown() {
        assertEquals(StorageRecoveryKind.SETTINGS_RESET, next(settingsReset = true))
    }

    @Test
    fun settingsResetBeforeNotUnlocked() {
        assertEquals(StorageRecoveryKind.SETTINGS_RESET, next(settingsReset = true, setAside = true))
        // Weggetipptes «nicht entsperrt» verdeckt den Einstellungs-Hinweis nicht
        assertEquals(StorageRecoveryKind.SETTINGS_RESET, next(settingsReset = true, setAside = true, setAsideHidden = true))
    }

    @Test
    fun cancelledRestoreAndRecoveredDataFirst() {
        assertEquals(StorageRecoveryKind.RESTORE_ROLLED_BACK, next(restoreInterrupted = "rolled_back", settingsReset = true))
        assertEquals(StorageRecoveryKind.RESTORE_INCOMPLETE, next(restoreInterrupted = "incomplete", settingsReset = true))
        assertEquals(StorageRecoveryKind.KEY_RESTORED, next(keyRestored = true, settingsReset = true))
        assertEquals(StorageRecoveryKind.SYSTEM_BACKUP_NOT_IMPORTED, next(systemBackupNotImported = true, settingsReset = true))
    }

    @Test
    fun notUnlockedOnlyWithoutDismissing() {
        assertEquals(StorageRecoveryKind.SET_ASIDE, next(setAside = true))
        assertNull(next(setAside = true, setAsideHidden = true))
    }
}
