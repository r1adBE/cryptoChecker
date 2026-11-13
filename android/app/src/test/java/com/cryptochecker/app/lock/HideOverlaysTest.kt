package com.cryptochecker.app.lock

import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Fremde Überlagerungen ausblenden (Tapjacking-Schutz, ab Android 12): Regel, Berechtigung und Einbaustellen. */
class HideOverlaysTest {

    @Test
    fun `overlays are hidden from Android 12 while someone asks for it`() {
        assertTrue(PortfolioLockPolicy.hideOverlayWindows(holders = 1, sdkInt = 31))
        assertTrue(PortfolioLockPolicy.hideOverlayWindows(holders = 2, sdkInt = 36))
        assertFalse(PortfolioLockPolicy.hideOverlayWindows(holders = 0, sdkInt = 36))
        // Vor Android 12 gibt es den Aufruf nicht
        assertFalse(PortfolioLockPolicy.hideOverlayWindows(holders = 1, sdkInt = 30))
    }

    @Test
    fun `manifest declares the normal permission`() {
        assertTrue(file("src/main/AndroidManifest.xml").contains("<uses-permission android:name=\"android.permission.HIDE_OVERLAY_WINDOWS\" />"))
    }

    @Test
    fun `portfolio and sensitive confirmations hide overlays`() {
        val ui = "src/main/java/com/cryptochecker/app/ui"
        assertTrue(file("$ui/navigation/AppNavHost.kt").contains("HideOverlaysEffect(onPortfolio)"))
        assertTrue(file("$ui/features/settings/SettingsScreen.kt").contains("HideOverlaysEffect(askReset)"))
        assertTrue(file("$ui/features/settings/SettingsDataPages.kt").contains("HideOverlaysEffect(restoreStep != null)"))
        assertTrue(file("$ui/features/settings/SettingsDataPages.kt").contains("HideOverlaysEffect(exportDialog != null)"))
        assertTrue(file("$ui/features/settings/SettingsPortfolioPages.kt").contains("HideOverlaysEffect()"))
        // Der Aufruf selbst nur hinter der Versionsprüfung
        val effect = file("$ui/lock/HideOverlays.kt")
        assertTrue(effect.contains("if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {"))
        assertTrue(effect.contains("window.setHideOverlayWindows("))
    }

    private fun file(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var found: File? = null
        while (dir != null && found == null) {
            found = listOf("", "app/", "android/app/").map { File(dir, it + rel) }.firstOrNull { it.isFile }
            dir = dir.parentFile
        }
        return SourceGuard.android(found, rel).readText(Charsets.UTF_8)
    }
}
