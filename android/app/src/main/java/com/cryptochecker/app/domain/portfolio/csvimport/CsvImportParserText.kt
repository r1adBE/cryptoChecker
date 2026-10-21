// Text der CSV-Dateien (Teil von [CsvImportParser], gespiegelt in `CsvImportParser+Text.swift`): Zeichensatz,
// Zerlegen in Zellen, Normalisieren. Erweiterungsfunktionen des Objekts.
package com.cryptochecker.app.domain.portfolio.csvimport

import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportParser.Record

// ---------- Zeichensatz ----------

/** Windows-1252 0x80–0x9F (nicht belegte Bytes bleiben ihr Codepunkt, wie WHATWG); Rest = Latin-1. */
private val CP1252_HIGH = intArrayOf(
    0x20AC, 0x0081, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021, 0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0x008D, 0x017D, 0x008F,
    0x0090, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014, 0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x009D, 0x017E, 0x0178,
)

internal fun CsvImportParser.decodeCp1252(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size)
    for (b in bytes) {
        val v = b.toInt() and 0xff
        sb.append((if (v in 0x80..0x9F) CP1252_HIGH[v - 0x80] else v).toChar())
    }
    return sb.toString()
}

// ---------- Zerlegen ----------

/** Wie [tokenizeOrNull]; über den Grenzen leer. */
internal fun CsvImportParser.tokenize(text: String, delimiter: Char, maxRecords: Int = Int.MAX_VALUE): List<Record> =
    tokenizeOrNull(text, delimiter, maxRecords).orEmpty()

/**
 * RFC-4180-Zerlegung («""» = «"», Umbruch in Anführungszeichen erlaubt); Zellen getrimmt, leere Zeilen weg.
 * null = eine Zeile mit mehr als [maxCells] Zellen oder mehr als [maxRows] Datensätze (Datei zu gross) — wie iOS.
 */
internal fun CsvImportParser.tokenizeOrNull(
    text: String,
    delimiter: Char,
    maxRecords: Int = Int.MAX_VALUE,
    maxCells: Int = CsvImportParser.MAX_CELLS_PER_ROW,
    maxRows: Int = CsvImportParser.MAX_ROWS,
): List<Record>? {
    val out = ArrayList<Record>()
    var cells = ArrayList<String>()
    val cell = StringBuilder()
    var inQuotes = false
    var line = 1
    var startLine = 1
    var tooLarge = false
    fun endCell() {
        cells.add(cell.toString().trim())
        cell.setLength(0)
        if (cells.size > maxCells) tooLarge = true
    }
    fun endRecord() {
        endCell()
        if (cells.any { it.isNotEmpty() }) out.add(Record(startLine, cells))
        if (out.size > maxRows) tooLarge = true
        cells = ArrayList()
    }
    var i = 0
    val n = text.length
    // Höchstens [maxRecords] Zeilen (Erkennung der Kopfzeile): danach nicht weiterlesen; über den Grenzen sofort aufhören
    while (i < n && out.size < maxRecords && !tooLarge) {
        val c = text[i]
        when {
            inQuotes && c == '"' && i + 1 < n && text[i + 1] == '"' -> { cell.append('"'); i++ }
            inQuotes && c == '"' -> inQuotes = false
            inQuotes -> { if (c == '\n') line++; cell.append(c) }
            c == '"' && cell.isBlank() -> { cell.setLength(0); inQuotes = true }
            c == delimiter -> endCell()
            c == '\n' -> { endRecord(); line++; startLine = line }
            else -> cell.append(c)
        }
        i++
    }
    if (!tooLarge && out.size < maxRecords && (cell.isNotEmpty() || cells.isNotEmpty())) endRecord()
    return if (tooLarge) null else out
}

/** Spaltenname/Art vergleichbar machen: klein, Umlaute ausgeschrieben, nur a–z und 0–9. */
internal fun CsvImportParser.normalize(text: String): String {
    val sb = StringBuilder()
    for (ch in text.lowercase()) {
        when (ch) {
            'ä' -> sb.append("ae")
            'ö' -> sb.append("oe")
            'ü' -> sb.append("ue")
            'ß' -> sb.append("ss")
            'é', 'è', 'ê', 'ë' -> sb.append('e')
            'à', 'â' -> sb.append('a')
            'ô' -> sb.append('o')
            'î', 'ï' -> sb.append('i')
            'ç' -> sb.append('c')
            else -> if (ch in 'a'..'z' || ch in '0'..'9') sb.append(ch)
        }
    }
    return sb.toString()
}
