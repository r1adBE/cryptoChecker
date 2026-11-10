package com.cryptochecker.app.notification

import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Portfolio-Alarme auf dem Sperrbildschirm immer privat: VISIBILITY_PRIVATE mit neutraler öffentlicher
 * Fassung, unabhängig von der Portfolio-Sperre; Kursalarme folgen weiter der Sperre (`lockScreenPrivacy`).
 * Gegenstück iOS: `PortfolioAlarmLockScreenTests` (Kategorie mit verborgener Vorschau immer).
 */
class PortfolioAlarmLockScreenTest {

    @Test
    fun portfolioAlarmImmerPrivat() {
        val rel = "notification/AppNotifierAlarms.kt"
        val src = SourceGuard.android(findUp(rel), rel).readText(Charsets.UTF_8)
        val portfolio = src.substringAfter("suspend fun AppNotifier.showPortfolioAlarm(")
        assertTrue(portfolio.contains("    builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)"))
        assertTrue(portfolio.contains(".setContentText(locked)"))
        assertFalse(portfolio.contains("if (settings.appLock) {\n        builder.setVisibility"))
        // Kursalarme unverändert: nur mit Sperre privat
        assertTrue(src.substringBefore("suspend fun AppNotifier.showPortfolioAlarm(").contains("builder.lockScreenPrivacy("))
    }

    private fun findUp(rel: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (prefix in listOf("", "app/", "android/app/")) {
                val f = File(dir, "${prefix}src/main/java/com/cryptochecker/app/$rel")
                if (f.isFile) return f
            }
            dir = dir.parentFile
        }
        return null
    }
}
