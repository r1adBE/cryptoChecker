package com.cryptochecker.app.util

import com.cryptochecker.app.parity.ParityJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Die Test-Aufgaben aller Module kennen die Dateien, die die Tests ausserhalb ihres Klassenpfads lesen
 * (`gradle/test-inputs.gradle.kts`, angewendet im Wurzel-Buildskript) — sonst meldet Gradle «UP-TO-DATE» bzw. nimmt
 * das Ergebnis aus dem Build-Cache, obwohl sich eine Fixture, eine Swift-Datei oder ein Nachbarmodul geändert hat.
 *
 * Dieser Wächter liest alle Zeichenketten der Unit-Tests aller Module: Bezeichnet eine davon (ab `android/`, einem
 * Modulordner, der Wurzel oder `public/`) einen vorhandenen Ort im Baum, muss er unter einem angemeldeten Ordner
 * liegen (oder dessen Vorfahre sein, z. B. `"ios"` als Anfang eines zusammengesetzten Pfads). Ein neuer Leseort kann
 * so nicht vergessen gehen: in `testInputRoots` eintragen. Nennt ein Test einen Pfad nur als Text (liest ihn nicht),
 * gehört er in [NOT_READ].
 */
class TestInputsGuardTest {

    private val android: File = ParityJson.dir().parentFile!!.parentFile!!.normalize()
    private val repo: File = android.parentFile!!
    private val script = File(android, "gradle/test-inputs.gradle.kts")

    private val declared: List<File> by lazy {
        val block = script.readText(Charsets.UTF_8).substringAfter("// testInputRoots:begin").substringBefore("// testInputRoots:end")
        Regex("""(?m)^\s*"([^"]+)",\s*$""").findAll(block).map { File(android, it.groupValues[1]).normalize() }.toList()
    }

    @Test
    fun wurzelSkriptWendetDieEingabenAufAlleModuleAn() {
        val root = File(android, "build.gradle.kts").readText(Charsets.UTF_8)
        assertTrue(root.contains("subprojects {\n    apply(from = rootProject.file(\"gradle/test-inputs.gradle.kts\"))\n}"))
        val text = script.readText(Charsets.UTF_8)
        assertTrue(text.contains("tasks.withType<Test>().configureEach {"))
        assertTrue(text.contains(".withPathSensitivity(PathSensitivity.RELATIVE)"))
        assertTrue(text.contains("providers.environmentVariable(\"${SourceGuard.REQUIRE_IOS_ENV}\")"))
        assertTrue(text.contains("inputs.property(\"requireIosSources\", requireIosSources)"))
        assertTrue("${declared.size} Einträge", declared.size >= 20)
        assertEquals("doppelte Einträge", declared.size, declared.toSet().size)
    }

    /** Die Module, deren Tests gelesen werden, sind die mit Unit-Tests — ein neues Modul fällt auf. */
    @Test
    fun jedesModulMitTestsIstAngemeldet() {
        val withTests = android.listFiles { f -> File(f, "src/test").isDirectory }.orEmpty().map { it.name }.sorted()
        assertEquals(MODULES.sorted(), withTests)
        for (module in MODULES) {
            for (sourceSet in listOf("main", "test")) {
                assertTrue("$module/src/$sourceSet", File(android, "$module/src/$sourceSet").normalize() in declared)
            }
        }
    }

    @Test
    fun jederLeseortDerTestsIstAlsEingabeAngemeldet() {
        val bases = listOf(android) + MODULES.map { File(android, it) } + listOf(repo, File(repo, "public"))
        // Einzelne Namen ohne «/» zählen nur, wenn sie oben im Baum liegen und kein Modul-/Paketname sind
        val single = (android.list().orEmpty().toSet() + repo.list().orEmpty().toSet()) - MODULES.toSet()
        val uncovered = sortedMapOf<String, String>()
        var paths = 0
        for (module in MODULES) {
            File(android, "$module/src/test").walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val text = file.readText(Charsets.UTF_8).replace(RAW_STRING, "\"\"")
                for (match in LITERAL.findAll(text)) {
                    val path = pathPart(match.groupValues[1]) ?: continue
                    if ('/' !in path && path !in single) continue
                    val found = bases.map { File(it, path).normalize() }.filter { it.exists() && it.startsWith(repo) }
                    if (found.isEmpty() || path in NOT_READ) continue
                    paths++
                    val covered = found.any { f -> declared.any { d -> f.startsWith(d) || d.startsWith(f) } }
                    if (!covered) uncovered.putIfAbsent(path, file.relativeTo(android).path)
                }
            }
        }
        // Der Wächter sieht die Pfade wirklich (sonst wäre er still grün)
        assertTrue("nur $paths Pfade erkannt", paths >= 100)
        assertTrue(
            "Leseorte ohne Eingabe in gradle/test-inputs.gradle.kts (testInputRoots): $uncovered",
            uncovered.isEmpty(),
        )
    }

    /** Die Erkennung selbst: Vorlagen, Präfixe und Nicht-Pfade. */
    @Test
    fun pfadTeilEinerZeichenkette() {
        assertEquals("ios", pathPart("ios/\$rel"))
        assertEquals("src/main", pathPart("\${prefix}src/main/\$rel"))
        assertEquals("../ios/App", pathPart("../ios/App/"))
        assertEquals("testdata/parity", pathPart("./testdata/parity"))
        assertEquals("LICENSE", pathPart("LICENSE"))
        assertEquals(null, pathPart("\$dir/strings_common.xml"))
        assertEquals(null, pathPart("https://example.org/a"))
        assertEquals(null, pathPart("zwei Wörter/x"))
        assertEquals(null, pathPart("/tmp/x"))
        assertEquals(null, pathPart("../"))
        assertEquals(null, pathPart(""))
    }

    /**
     * Fester Anfang einer Zeichenkette als Pfad: eine Vorlage am Anfang fällt weg, ab der nächsten Vorlage wird am
     * letzten «/» davor abgeschnitten; null für alles, was kein relativer Pfad sein kann.
     */
    private fun pathPart(literal: String): String? {
        var v = literal.replace(LEADING_TEMPLATE, "")
        val template = v.indexOf('$')
        if (template >= 0) v = v.substring(0, template).substringBeforeLast('/', "")
        v = v.trim()
        if (v.isEmpty() || v.startsWith("/") || v.contains("://") || v.contains('\\') || v.any { it.isWhitespace() }) return null
        while (v.startsWith("./")) v = v.removePrefix("./")
        v = v.trimEnd('/')
        if (v.isEmpty() || v.all { it == '.' || it == '/' }) return null
        return v
    }

    private companion object {
        val MODULES = listOf("app", "domain", "data", "marketdata", "wear")

        /** Pfade, die ein Test nur nennt (Meldung, Vergleichstext), aber nicht liest. */
        val NOT_READ = emptySet<String>()

        val RAW_STRING = Regex("\"\"\"[\\s\\S]*?\"\"\"")
        val LITERAL = Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"")
        val LEADING_TEMPLATE = Regex("^(\\$\\{[^}]*\\}|\\$[A-Za-z_]+)")
    }
}
