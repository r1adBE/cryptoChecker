package com.cryptochecker.app.ui.features.settings

import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Einstellungen › Portfolio: Einstandsmethode und «Portfolio in Systemsicherung» gehen über die Rückfrage
 * ([com.cryptochecker.app.settings.SettingsConfirm]); die Arbeitsunterlage nennt vor dem Export, was in den Dateien steht.
 * Wächter auf den Quelltext beider Plattformen.
 */
class PortfolioSettingsConfirmWiringTest {

    @Test
    fun androidFragtVorDemSchreiben() {
        val page = android("ui/features/settings/SettingsPortfolioPages.kt")
        assertFalse("Methode nicht mehr direkt per Radioklick", page.contains("onClick = { viewModel.setCostMethod(method) }"))
        assertTrue(page.contains("SettingsConfirm.costMethod(settings.costMethod, method)"))
        assertTrue(page.contains("SettingsConfirm.systemBackup(enabling = on)"))
        assertTrue(page.contains("R.string.settings_cost_method_confirm") && page.contains("R.string.settings_portfolio_system_backup_confirm"))
        assertTrue(android("ui/features/portfolio/TaxWorksheetContent.kt").contains("R.string.tax_export_file_contents"))
    }

    @Test
    fun iosFragtVorDemSchreiben() {
        val page = ios("App/Features/Settings/SettingsPortfolioPages.swift")
        assertFalse(page.contains(") { data.settings.costMethod = method }"))
        assertTrue(page.contains("SettingsConfirm.costMethod(current: data.settings.costMethod, chosen: method)"))
        assertTrue(page.contains("SettingsConfirm.systemBackup(enabling: on)"))
        assertTrue(page.contains("L(\"settings_portfolio_system_backup_confirm_ios\")"))
        assertTrue(ios("App/Features/Portfolio/TaxWorksheetPage.swift").contains("L(\"tax_export_file_contents\")"))
    }

    private fun android(rel: String): String {
        val file = findUp(listOf("", "app/", "android/app/").map { "${it}src/main/java/com/cryptochecker/app/$rel" })
        return SourceGuard.android(file, rel).readText(Charsets.UTF_8)
    }

    private fun ios(rel: String): String {
        val file = findUp(listOf("ios/$rel", "../ios/$rel", "../../ios/$rel"))
        return SourceGuard.ios(file, "ios/$rel").readText(Charsets.UTF_8)
    }

    private fun findUp(candidates: List<String>): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (rel in candidates) {
                val f = File(dir, rel)
                if (f.isFile) return f
            }
            dir = dir.parentFile
        }
        return null
    }
}
