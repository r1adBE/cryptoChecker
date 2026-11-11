package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Notiz unter einem Paar der Merkliste: eine Zeile mit «…» über die ganze Breite der Zeile (ausserhalb der Paar-Spalte),
 * auf beiden Plattformen. Quelltext-Wächter; die Regel «eine Zeile» prüft `TextLimitParityTest`.
 */
class WatchRowNoteTest {

    @Test
    fun `android note is one ellipsized line below the whole row`() {
        val row = android("WatchlistRow.kt")
        // Nach der Zeile (Logo, Paar, Mini-Chart, Kurs), nicht in der Paar-Spalte
        assertTrue(row.contains("                }\n                watch.note?.let { note -> WatchRowNote(note, accent, off) }\n"))
        val note = android("WatchlistRowInfo.kt").substringAfter("internal fun WatchRowNote(")
        assertTrue(note.contains("modifier = modifier.fillMaxWidth().padding(top = 2.dp).clearAndSetSemantics { }"))
        assertTrue(note.contains("text = TextLimit.singleLine(note),"))
        assertTrue(note.contains("maxLines = 1,\n            overflow = TextOverflow.Ellipsis,"))
        assertFalse(android("WatchlistRowInfo.kt").substringBefore("internal fun WatchRowNote(").contains("R.drawable.ic_note"))
    }

    @Test
    fun `ios note is one ellipsized line below the whole row`() {
        val row = ios("App/Features/Watchlist/WatchlistRow.swift")
        assertTrue(row.contains("            mainLine\n            noteLine\n"))
        val parts = ios("App/Features/Watchlist/WatchlistRowParts.swift")
        val note = parts.substringAfter("var noteLine: some View {").substringBefore("var priceColumn: some View {")
        assertTrue(note.contains("Text(TextLimit.singleLine(note))"))
        assertTrue(note.contains(".lineLimit(1)\n                    .truncationMode(.tail)"))
        assertTrue(note.contains(".frame(maxWidth: .infinity, alignment: .leading)"))
        assertFalse(parts.substringBefore("var noteLine: some View {").contains("\"note.text\""))
    }

    private fun android(name: String): String {
        val rel = "src/main/java/com/cryptochecker/app/ui/features/watchlist/$name"
        return SourceGuard.android(findUp(listOf("", "app/", "android/app/").map { it + rel }), rel).readText(Charsets.UTF_8)
    }

    private fun ios(rel: String): String =
        SourceGuard.ios(findUp(listOf("ios/$rel", "../ios/$rel", "../../ios/$rel")), "ios/$rel").readText(Charsets.UTF_8)

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
