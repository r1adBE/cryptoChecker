package com.cryptochecker.app.lock

import com.cryptochecker.app.data.local.SystemBackupScope
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
        assertTrue(PortfolioLockPolicy.systemBackupNeedsUnlock(locked = true, enabling = true))
        assertFalse(PortfolioLockPolicy.systemBackupNeedsUnlock(locked = true, enabling = false))
        assertFalse(PortfolioLockPolicy.systemBackupNeedsUnlock(locked = false, enabling = true))
        assertTrue(PortfolioLockPolicy.quickAddNeedsUnlock(locked = true))
        assertFalse(PortfolioLockPolicy.quickAddNeedsUnlock(locked = false))
        assertTrue(PortfolioLockPolicy.showSetting(portfolioEnabled = true))
        assertFalse(PortfolioLockPolicy.showSetting(portfolioEnabled = false))
    }

    /** Android 8/8.1 (API 26/27) kennt requireFlags nicht: Systemsicherung des Portfolios dort immer aus. */
    @Test
    fun systemBackupOnlyFromAndroid9() {
        assertFalse(PortfolioLockPolicy.systemBackupSupported(26))
        assertFalse(PortfolioLockPolicy.systemBackupSupported(27))
        assertTrue(PortfolioLockPolicy.systemBackupSupported(28))
        assertTrue(PortfolioLockPolicy.systemBackupSupported(35))
        assertFalse(PortfolioLockPolicy.systemBackupEffective(setting = true, sdkInt = 27))
        assertTrue(PortfolioLockPolicy.systemBackupEffective(setting = true, sdkInt = 28))
        assertFalse(PortfolioLockPolicy.systemBackupEffective(setting = false, sdkInt = 30))
    }

    /**
     * Sicherungsregeln: Android 8/8.1 (xml/, requireFlags wirkungslos) ohne Portfolio und ohne die unverschlüsselte
     * Kopie der Hauptdatenbank (Build 60), ab Android 9 (xml-v28/) mit beiden.
     */
    @Test
    fun backupRulesKeepPortfolioAndDatabaseCopyOffAndroid8() {
        val base = resourceText("xml/backup_rules.xml") ?: return // Quelle nicht auffindbar
        val v28 = resourceText("xml-v28/backup_rules.xml") ?: return
        val include = Regex("""<include[^>]*path="([^"]+)"""")
        val basePaths = include.findAll(base).map { it.groupValues[1] }.toSet()
        assertFalse(basePaths.any { it.contains("portfolio_system_backup") || it.startsWith("cryptochecker_portfolio") })
        assertFalse(basePaths.any { it.startsWith("cryptochecker_system_backup") || it.startsWith("cryptochecker.db") })
        assertTrue(v28.contains("path=\"portfolio_system_backup.json\" requireFlags=\"clientSideEncryption\""))
        assertTrue(v28.contains("path=\"cryptochecker_system_backup.db\" requireFlags=\"clientSideEncryption\""))
        // Sonst gleicher Umfang
        val onlyWithEncryption = setOf("portfolio_system_backup.json", "cryptochecker_system_backup.db", "cryptochecker_system_backup.db-wal")
        assertEquals(include.findAll(v28).map { it.groupValues[1] }.toSet() - onlyWithEncryption, basePaths)
    }

    /** Der Sicherungs-Agent legt die Kopie der Hauptdatenbank nur dort an, wo die Regeln sie mitnehmen. */
    @Test
    fun databaseCopyOnlyFromAndroid9() {
        assertFalse(SystemBackupScope.writesDatabaseCopy(26))
        assertFalse(SystemBackupScope.writesDatabaseCopy(27))
        assertTrue(SystemBackupScope.writesDatabaseCopy(28))
        assertTrue(SystemBackupScope.writesDatabaseCopy(35))
        // Gleiche Grenze wie «Portfolio in Systemsicherung»
        assertEquals(PortfolioLockPolicy.SYSTEM_BACKUP_MIN_SDK, SystemBackupScope.ENCRYPTED_RULES_MIN_SDK)
    }

    /**
     * Verschlüsselte Datenbanken (Build 59) nie in einer Sicherungsregel — ihr Schlüssel bleibt im Keystore; die
     * Hauptdatenbank geht ab Android 9 als Kopie des Sicherungs-Agenten mit (SystemBackupAgent.EXPORT_NAME).
     */
    @Test
    fun backupRulesCarryDatabaseCopyNotEncryptedFiles() {
        val include = Regex("""<include[^>]*path="([^"]+)"""")
        for (rel in listOf("xml/backup_rules.xml", "xml-v28/backup_rules.xml", "xml/data_extraction_rules.xml")) {
            val text = resourceText(rel) ?: return // Quelle nicht auffindbar
            val paths = include.findAll(text).map { it.groupValues[1] }.toSet()
            // Kopie nur mit wirksamer Verschlüsselungs-Bedingung (nicht Android 8/8.1)
            assertEquals(rel, rel != "xml/backup_rules.xml", "cryptochecker_system_backup.db" in paths)
            // Widget-Aufnahme (versiegelt, Schlüssel im Keystore) und Widget-Zuordnung nie
            assertFalse(rel, paths.any { it.startsWith("portfolio_widget") || it.startsWith("widgets") })
            assertFalse(rel, paths.any { it.startsWith("cryptochecker.db") || it.startsWith("cryptochecker_portfolio") })
            assertFalse(rel, paths.any { "db_keys" in it || it.endsWith(".unreadable") })
        }
    }

    private fun resourceText(rel: String): String? {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (prefix in listOf("", "app/", "android/app/")) {
                val f = java.io.File(dir, prefix + "src/main/res/" + rel)
                if (f.isFile) return f.readText(Charsets.UTF_8)
            }
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun widgetIgnoresTheSession() {
        assertTrue(PortfolioLockPolicy.widgetLocked(lockSetting = true))
        assertFalse(PortfolioLockPolicy.widgetLocked(lockSetting = false))
    }

    @Test
    fun secureWindowOnlyWithLockAndVisiblePortfolio() {
        assertTrue(PortfolioLockPolicy.secureWindow(lockSetting = true, portfolioVisible = true))
        assertFalse(PortfolioLockPolicy.secureWindow(lockSetting = true, portfolioVisible = false))
        assertFalse(PortfolioLockPolicy.secureWindow(lockSetting = false, portfolioVisible = true))
        assertFalse(PortfolioLockPolicy.secureWindow(lockSetting = false, portfolioVisible = false))
    }
}
