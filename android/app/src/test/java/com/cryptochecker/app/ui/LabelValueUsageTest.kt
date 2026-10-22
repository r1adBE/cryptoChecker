package com.cryptochecker.app.ui

import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Wache über «Bezeichnung: Wert» (seit Build 98): sichtbare und gesprochene Texte setzen Bezeichnung
 * und Wert über `R.string.label_value` bzw. iOS `L("label_value", …)` zusammen, nie über ein festes
 * «: ». Sonst fehlt die Typografie der Sprache — Französisch « : » mit geschütztem Leerzeichen vor dem
 * Doppelpunkt, Chinesisch und Japanisch der breite Doppelpunkt «：».
 *
 * Durchsucht die Kotlin-Quellen und `ios/App`, `ios/Shared`, `ios/Widgets` ([com.cryptochecker.app.util.SourceGuard], reiner
 * Android-Checkout) wie [com.cryptochecker.app.ui.theme.TypographyUsageTest]. Protokollzeilen und
 * Programmierfehler-Texte zählen nicht; technische Texte (Diagnose, CSV, HTTP-Fehler) stehen mit
 * Begründung in [ALLOWED].
 */
class LabelValueUsageTest {

    @Test
    fun kotlinUsesLabelValueResource() {
        val root = findUp(listOf("", "app/", "android/app/").map { it + "src/main/java/com/cryptochecker/app" })
        assertNotNull("android/app/src/main/java nicht gefunden (Arbeitsverzeichnis?)", root)
        val problems = offenders(root!!, "kt", KOTLIN_PATTERNS)
        assertTrue(
            "«Bezeichnung: Wert» per Verkettung — stringResource(R.string.label_value, a, b) nehmen:\n" +
                problems.joinToString("\n"),
            problems.isEmpty()
        )
    }

    @Test
    fun swiftUsesLabelValueResource() {
        val roots = listOf("App", "Shared", "Widgets").mapNotNull { dir ->
            findUp(listOf("ios/$dir", "../ios/$dir", "../../ios/$dir"))
        }
        // Seit Build 106 (SEC4-R1/TST4-R1): fehlt ios/, nicht still grün — übersprungen bzw. mit REQUIRE_IOS_SOURCES Fehler
        val found = SourceGuard.ios(roots.takeIf { it.isNotEmpty() }, "ios/App|Shared|Widgets")
        val problems = found.flatMap { offenders(it, "swift", SWIFT_PATTERNS) }
        assertTrue(
            "«Bezeichnung: Wert» per Verkettung — L(\"label_value\", a, b) nehmen:\n" + problems.joinToString("\n"),
            problems.isEmpty()
        )
    }

    /** Alle 31 Sprachen haben `label_value` mit beiden Platzhaltern und der Typografie der Sprache. */
    @Test
    fun labelValueHasLanguageTypography() {
        val res = findUp(listOf("src/main/res", "app/src/main/res", "android/app/src/main/res"))
        assertNotNull("res-Verzeichnis nicht gefunden", res)
        val files = res!!.listFiles { f -> f.isDirectory && f.name.startsWith("values") }!!
            .map { File(it, "strings_common.xml") }
            .filter { it.isFile }
        assertEquals("strings_common.xml in allen 31 Sprachen", 31, files.size)
        for (file in files) {
            val value = Regex("""<string name="label_value">([^<]*)</string>""")
                .find(file.readText(Charsets.UTF_8))?.groupValues?.get(1)
            assertNotNull("label_value fehlt in ${file.parentFile.name}", value)
            val expected = when (file.parentFile.name) {
                "values-fr" -> """%1${'$'}s\u00A0: %2${'$'}s"""
                "values-zh", "values-ja" -> "%1\$s：%2\$s"
                else -> "%1\$s: %2\$s"
            }
            assertEquals("label_value in ${file.parentFile.name}", expected, value)
        }
    }

    /** Treffer in Codezeilen (ohne Kommentare, Protokoll und [ALLOWED]) als «Pfad:Zeile: Text». */
    private fun offenders(dir: File, extension: String, patterns: List<Regex>): List<String> =
        dir.walkTopDown()
            .filter { it.isFile && it.extension == extension }
            .flatMap { file ->
                val path = file.invariantSeparatorsPath
                val allowed = ALLOWED.entries.filter { path.endsWith(it.key) }.flatMap { it.value }
                file.readLines(Charsets.UTF_8).withIndex()
                    .filterNot { (_, line) -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }
                    .filterNot { (_, line) -> IGNORED_CALLS.any { line.contains(it) } }
                    .filter { (_, line) -> patterns.any { it.containsMatchIn(line) } }
                    .filterNot { (_, line) -> allowed.any { line.contains(it) } }
                    .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
                    .toList()
            }
            .toList()

    /** Sucht [candidates] vom Arbeitsverzeichnis aus aufwärts. */
    private fun findUp(candidates: List<String>): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (rel in candidates) {
                val f = File(dir, rel)
                if (f.isDirectory) return f
            }
            dir = dir.parentFile
        }
        return null
    }

    private companion object {
        /** `a + ": " + b`, `.append(": ")`, `"$a: $b"` und `"$a: " + b`. */
        val KOTLIN_PATTERNS = listOf(
            Regex("""\+ ": " \+"""),
            Regex("""\.append\(": "\)"""),
            Regex("""\$\{?[A-Za-z_][\w.]*\}?: (\$|" \+)"""),
        )

        /** `a + ": " + b`, `"\(a): \(b)"` und `"\(a): " + b`. */
        val SWIFT_PATTERNS = listOf(
            Regex("""\+ ": " \+"""),
            Regex("""\): \\\("""),
            Regex("""\): " \+"""),
        )

        /** Protokoll und Programmierfehler: nie in der Sprache der Oberfläche. */
        val IGNORED_CALLS = listOf("HttpLog.", "Log.", "Timber.", "fatalError(", "error(\"", "require(", "check(")

        /** Pfadende → Zeilenstücke, die bewusst ein festes «: » behalten. */
        val ALLOWED: Map<String, List<String>> = mapOf(
            // Diagnosebericht zum Kopieren: feste Codes, gleich in jeder Sprache
            "domain/diagnostics/DiagnosticReport.kt" to listOf("\${it.row.code}: "),
            "Services/DiagnosticReport.swift" to listOf("\\(item.row.rawValue): "),
            // Prüfprotokoll der Datenbank-Umstellung (Diagnose, deutsch)
            "data/local/DatabaseFingerprintDiff.kt" to listOf("\$table: "),
            // Orientierungsposten auch in der CSV-Ausgabe: Format der Vorlage, nicht der Oberfläche
            "domain/portfolio/TaxSummaryTexts.kt" to listOf("\$fallback: \$label"),
            "Portfolio/Tax/TaxSummaryTexts.swift" to listOf("\\(fallback): \\(label)"),
            // Technischer Fehlertext: HTTP-Status und Auszug der Antwort
            "Core/MarketHTTP.swift" to listOf("status + \": \" + excerpt"),
            "Markets/BulkTickerRows.swift" to listOf("\\(formatError): "),
        )
    }
}
