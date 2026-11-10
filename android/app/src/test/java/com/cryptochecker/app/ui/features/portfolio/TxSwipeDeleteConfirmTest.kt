package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Wischen (bzw. TalkBack/VoiceOver «Löschen») auf einer Buchungszeile fragt mit derselben Rückfrage wie der Editor und
 * löscht erst danach — ohne «Rückgängig», wie der Editor. Wächter auf den Quelltext beider Plattformen.
 */
class TxSwipeDeleteConfirmTest {

    @Test
    fun androidWischenFragtWieDerEditor() {
        val detail = android("PortfolioDetailScreen.kt")
        // Wischen und TalkBack öffnen nur die Rückfrage; gelöscht wird in onConfirm
        assertTrue(detail.contains("onDelete = { confirmDelete = tx }"))
        assertEquals(2, Regex("""confirmDelete = tx\b""").findAll(detail).count())
        assertTrue(detail.contains("confirmsDelete = true"))
        assertTrue(detail.contains("TxDeleteConfirmDialog("))
        assertFalse("kein sofortiges Löschen aus der Geste", detail.contains("onDelete = { deleteTx(tx) }"))
        // Wie der Editor: kein «Rückgängig» nach dem Löschen
        assertFalse(detail.contains("viewModel.restore("))
        assertFalse(detail.contains("banner.show(removedText) {"))
        // Dieselbe Rückfrage im Editor
        assertTrue(android("PortfolioTxSheet.kt").contains("TxDeleteConfirmDialog("))
        val row = android("PortfolioTxRow.kt")
        assertTrue(row.contains("R.string.portfolio_tx_delete_title") && row.contains("R.string.portfolio_tx_delete_confirm"))
    }

    @Test
    fun iosWischenFragtWieDerEditor() {
        val detail = ios("App/Features/Portfolio/PortfolioCoinDetail.swift")
        assertTrue(detail.contains("Button { confirmDelete(tx) } label:"))
        assertTrue(detail.contains("Button(L(\"action_delete\")) { confirmDelete(tx) }"))
        assertTrue(detail.contains(".portfolioTxDeleteConfirmation(isPresented: \$askDelete)"))
        assertFalse(detail.contains("Button(role: .destructive) { deleteTx(tx) }"))
        assertFalse(detail.contains("restorePortfolioTxs"))
        assertTrue(ios("App/Features/Portfolio/PortfolioTxSheet.swift").contains(".portfolioTxDeleteConfirmation(isPresented: \$askDelete)"))
    }

    private fun android(name: String): String {
        val rel = "ui/features/portfolio/$name"
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
