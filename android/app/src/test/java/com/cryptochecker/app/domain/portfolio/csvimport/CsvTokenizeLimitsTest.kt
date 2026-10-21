package com.cryptochecker.app.domain.portfolio.csvimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grenzen beim Zerlegen einer CSV-Datei (seit Build 99): höchstens [CsvImportParser.MAX_CELLS_PER_ROW] Zellen je Zeile
 * und [CsvImportParser.MAX_ROWS] Datensätze; mehr = Datei zu gross, nicht lesbar wie eine Datei über 16 MB.
 * Gleiche Fälle: iOS `CsvTokenizeLimitsTests`.
 */
class CsvTokenizeLimitsTest {

    private val header = "Date,Type,Coin,Amount,Price,Currency\n"
    private val row = "2024-02-02T10:00:00Z,Staking Reward,ETH,0.01,2000,USD\n"

    @Test
    fun grenzenWieIos() {
        assertEquals(200, CsvImportParser.MAX_CELLS_PER_ROW)
        assertEquals(200_000, CsvImportParser.MAX_ROWS)
    }

    @Test
    fun zellenJeZeileHoechstensGrenze() {
        val atLimit = List(CsvImportParser.MAX_CELLS_PER_ROW) { "c$it" }.joinToString(",")
        assertEquals(CsvImportParser.MAX_CELLS_PER_ROW, CsvImportParser.tokenizeOrNull(atLimit, ',')!!.single().cells.size)
        assertNull(CsvImportParser.tokenizeOrNull("$atLimit,zuviel", ','))
        // Lauter Trenner: ebenfalls zu viele (leere) Zellen
        assertNull(CsvImportParser.tokenizeOrNull("a" + ",".repeat(100_000), ','))
        // Im Feld in Anführungszeichen zählt der Trenner nicht
        assertEquals(2, CsvImportParser.tokenizeOrNull("\"" + ",".repeat(500) + "\",b", ',')!!.single().cells.size)
    }

    @Test
    fun datensaetzeHoechstensGrenze() {
        val text = (1..5).joinToString("\n") { "a$it;b$it" }
        assertEquals(5, CsvImportParser.tokenizeOrNull(text, ';', maxRows = 5)!!.size)
        assertNull(CsvImportParser.tokenizeOrNull(text, ';', maxRows = 4))
        // Leere Zeilen zählen nicht
        assertEquals(2, CsvImportParser.tokenizeOrNull("a\n\n\n\nb\n", ';', maxRows = 2)!!.size)
        // Erkennung der Kopfzeile (maxRecords) hört vorher auf und ist nicht «zu gross»
        assertEquals(3, CsvImportParser.tokenizeOrNull(text, ';', maxRecords = 3, maxRows = 4)!!.size)
    }

    @Test
    fun zuGrosseDateiIstNichtLesbar() {
        val ok = CsvImportParser.parse(header + row)
        assertEquals(CsvImportFormat.GENERIC, ok.format)
        assertFalse(ok.tooLarge)
        val wide = CsvImportParser.parse(header + row + List(CsvImportParser.MAX_CELLS_PER_ROW + 1) { "x" }.joinToString(",") + "\n")
        assertEquals(CsvImportFormat.UNKNOWN, wide.format)
        assertTrue(wide.tooLarge)
        assertTrue(wide.rows.isEmpty())
        // Kopfzeile selbst zu breit
        assertTrue(CsvImportParser.parse(List(CsvImportParser.MAX_CELLS_PER_ROW + 1) { "Date" }.joinToString(",")).tooLarge)
        val long = CsvImportParser.parse(header + "x\n".repeat(CsvImportParser.MAX_ROWS))
        assertEquals(CsvImportFormat.UNKNOWN, long.format)
        assertTrue(long.tooLarge)
    }

    /**
     * Zu gross wird als «zu gross» gemeldet, nicht als fremdes Format (seit Build 99): die Vorschau trägt
     * [CsvImportResult.tooLarge] weiter, [CsvImportRefusal] wählt die Meldung. Gleiche Fälle: iOS `CsvTokenizeLimitsTests`.
     */
    @Test
    fun zuGrosseDateiMeldetZuGross() {
        fun preview(text: String) = CsvImportMapping.preview(CsvImportParser.parse(text), emptySet()) { _, _ -> null }
        val wide = preview(header + row + List(CsvImportParser.MAX_CELLS_PER_ROW + 1) { "x" }.joinToString(",") + "\n")
        assertTrue(wide.tooLarge)
        assertEquals(CsvImportRefusal.TOO_LARGE, CsvImportRefusal.of(fileTooLarge = false, preview = wide))
        val long = preview(header + "x\n".repeat(CsvImportParser.MAX_ROWS))
        assertEquals(CsvImportRefusal.TOO_LARGE, CsvImportRefusal.of(fileTooLarge = false, preview = long))
        // Über 16 MB: das Lesen bricht ab, keine Vorschau
        assertEquals(CsvImportRefusal.TOO_LARGE, CsvImportRefusal.of(fileTooLarge = true, preview = null))
        // Fremdes Format oder nicht lesbar: wie bisher
        assertEquals(CsvImportRefusal.UNREADABLE, CsvImportRefusal.of(fileTooLarge = false, preview = preview("a,b,c\n1,2,3\n")))
        assertEquals(CsvImportRefusal.UNREADABLE, CsvImportRefusal.of(fileTooLarge = false, preview = null))
        // Lesbare Datei: Vorschau
        val ok = preview(header + row)
        assertFalse(ok.tooLarge)
        assertNull(CsvImportRefusal.of(fileTooLarge = false, preview = ok))
    }

    /** Bestehender Aufruf ohne Grenzen-Ergebnis: über den Grenzen leer. */
    @Test
    fun tokenizeUeberGrenzenLeer() {
        assertTrue(CsvImportParser.tokenize("a" + ",".repeat(300), ',').isEmpty())
    }
}
