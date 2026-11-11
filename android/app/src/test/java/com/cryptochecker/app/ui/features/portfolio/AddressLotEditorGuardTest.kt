package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Einstand-Posten einer Adresse: «Löschen» fragt erst nach (beide Plattformen), und auf Android überstehen das
 * Erfassen-Blatt der Coin-Seite und der Posten-Editor das Neu-Erzeugen der Activity (Drehen, Falten, Dunkelmodus).
 * Wächter auf den Quelltext; das Verhalten am Gerät steht in der Geräteliste (ROADMAP «Build 222 zuerst»).
 */
class AddressLotEditorGuardTest {

    @Test
    fun androidLoeschenFragtErst() {
        val editor = android("PortfolioAddressLotEditor.kt")
        assertTrue(editor.contains("TextButton(onClick = { askDelete = true })"))
        assertTrue(editor.contains("R.string.address_lots_delete_title"))
        assertFalse("kein sofortiges Löschen aus dem Knopf", editor.contains("TextButton(onClick = onDelete)"))
    }

    @Test
    fun iosLoeschenFragtErst() {
        val sheet = ios("App/Features/Portfolio/PortfolioAddressLotsSheet.swift")
        assertTrue(sheet.contains("Button(L(\"action_delete\"), role: .destructive) { askDelete = true }"))
        assertTrue(sheet.contains(".alert(L(\"address_lots_delete_title\"), isPresented: \$askDelete)"))
    }

    @Test
    fun androidBlaetterUeberstehenDasNeuErzeugen() {
        val detail = android("PortfolioDetailScreen.kt")
        assertTrue(detail.contains("var sheet by rememberSaveable(stateSaver = TxDraftSaver) { mutableStateOf<TxDraft?>(null) }"))
        assertTrue(detail.contains("var lotsFor by rememberSaveable { mutableStateOf<Long?>(null) }"))
        assertTrue(android("PortfolioAddressSheet.kt").contains("var lotsFor by rememberSaveable { mutableStateOf<Long?>(null) }"))
        assertTrue(android("PortfolioAddressLotsSheet.kt").contains("var editingId by rememberSaveable { mutableStateOf<Long?>(null) }"))
        // Alle Eingaben des Editors sind saveable (nur der geladene Vorschlag `close` nicht: er wird neu geladen)
        val editor = android("PortfolioAddressLotEditor.kt")
        assertFalse(editor.contains("by remember {"))
        for (field in listOf("coin", "time", "amountText", "perCoin", "valueText", "pickDate", "tried", "askDelete")) {
            assertTrue(field, editor.contains("var $field by rememberSaveable {"))
        }
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
