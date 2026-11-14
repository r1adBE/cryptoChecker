package com.cryptochecker.app.ui

import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Regeln, die an jeder Stelle gelten müssen (Blätter, Dialoge, Meldungen) — Quelltext-Wächter. */
class PortfolioSheetRulesTest {

    private val portfolio = "src/main/java/com/cryptochecker/app/ui/features/portfolio"

    @Test
    fun `address not written is not called invalid`() {
        val sheet = file("$portfolio/PortfolioAddressSheet.kt")
        val branch = sheet.substringAfter("null -> saveError =").substringBefore("\n            }")
        assertTrue(branch.contains("R.string.portfolio_write_failed"))
        assertFalse(branch.contains("R.string.address_invalid"))
    }

    /** Sichern: Dialog übersteht Drehen; Prozess-Neustart während der Dateiauswahl fragt erneut; keine leere Datei bleibt. */
    @Test
    fun `backup dialog survives recreation and failed write leaves no empty file`() {
        val settings = "src/main/java/com/cryptochecker/app/ui/features/settings"
        val page = file("$settings/SettingsDataPages.kt")
        assertTrue(page.contains("var exportDialog by rememberSaveable { mutableStateOf<Boolean?>(null) }"))
        assertTrue(page.contains("var pickerWithPassword by rememberSaveable { mutableStateOf<Boolean?>(null) }"))
        assertTrue(page.contains("viewModel.exportBackup(uri, withPassword) { exportDialog = true }"))
        val actions = file("$settings/SettingsBackupActions.kt")
        assertTrue(actions.contains("if (withPassword == true) onPasswordMissing() else _backupMessage.value = BackupMessage.SaveFailed"))
        assertTrue(actions.contains("if (message != BackupMessage.Exported && created) UriExportTarget(context).delete(uri)"))
        // Nur eine eben angelegte (leere) Datei, nie eine gewählte bestehende
        assertTrue(actions.contains("c.getLong(0) == 0L"))
    }

    /** «Beträge verbergen» an der Wurzel (U3-01): jedes Blatt und jeder Dialog sieht den Schalter, auch ausserhalb einer Liste. */
    @Test
    fun `hide amounts is provided at the root above every sheet`() {
        val host = file("src/main/java/com/cryptochecker/app/ui/navigation/AppNavHost.kt")
        val provide = host.indexOf("LocalHidePortfolioAmounts provides navViewModel.hidePortfolioAmounts")
        assertTrue(provide > 0)
        assertTrue(provide < host.indexOf("navController = navigation,"))
        assertTrue(file("src/main/java/com/cryptochecker/app/ui/navigation/AppNavViewModel.kt").contains(".map { it.hidePortfolioAmounts }"))
    }

    /** Portfolio-Beträge ausserhalb des Tabs (S3-07): Überlagerungen aus, mit Sperre FLAG_SECURE; der Schutz wird gezählt. */
    @Test
    fun `portfolio amounts outside the tab are shielded`() {
        val overview = file("src/main/java/com/cryptochecker/app/ui/features/alarms/AlarmsOverviewScreen.kt")
        assertTrue(overview.contains("HideOverlaysEffect(showsPortfolioAmounts)"))
        assertTrue(overview.contains("lockSetting = lockEnabled, portfolioVisible = showsPortfolioAmounts"))
        // Erfassen-Blatt (auch aus Paarseite und Markt): Überlagerungen aus, FLAG_SECURE am eigenen Fenster mit Sperre
        val tx = file("$portfolio/PortfolioTxSheet.kt")
        assertTrue(tx.contains("com.cryptochecker.app.ui.lock.HideOverlaysEffect()"))
        assertTrue(tx.contains("securePolicy = if (lockEnabled) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit"))
        // Mehrere Stellen: erst die letzte nimmt FLAG_SECURE weg
        val lock = file("src/main/java/com/cryptochecker/app/ui/lock/PortfolioLock.kt")
        assertTrue(lock.contains("if (active && window != null) SecureShield.release(window)"))
        assertFalse(lock.contains("if (active) window?.clearFlags"))
    }

    /** Wischen zum Löschen wie iOS (U3-09): Positionen, Transaktionen, Alarme des Paars, Adressen — immer mit Rückfrage. */
    @Test
    fun `swipe to delete with confirmation on every list that iOS swipes`() {
        val lists = mapOf(
            "$portfolio/PortfolioScreenBody.kt" to "onDelete = { onDeleteCoin(position.coin) },",
            "$portfolio/PortfolioDetailScreen.kt" to "onDelete = { confirmDelete = tx },",
            "src/main/java/com/cryptochecker/app/ui/features/alarms/AlarmsScreen.kt" to "onDelete = { editorHost.askDelete = alarm },",
            "$portfolio/PortfolioAddressSheet.kt" to "onDelete = { askRemove = a },",
        )
        for ((path, delete) in lists) {
            val code = file(path)
            val swipe = code.substringAfter("SwipeActionsRow(", missingDelimiterValue = "").substringBefore(") {")
            assertTrue(path, swipe.contains(delete))
            assertTrue(path, swipe.contains("confirmsDelete = true"))
        }
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
