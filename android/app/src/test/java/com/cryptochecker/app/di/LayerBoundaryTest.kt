package com.cryptochecker.app.di

import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Schichtgrenzen der Module am Quelltext (Audit C-01): Paket = Modul.
 * - `:domain`: jede Datei im Paket `com.cryptochecker.app.domain.…` (wie ihr Ordner), kein Import von Room, OkHttp
 *   oder einem anderen Paket der App (Daten, Widgets, Oberfläche …).
 * - `:data`: jede Datei im Paket `com.cryptochecker.app.data.…`, aus der App nur Importe aus `:domain` und `:data`.
 * - `:app` hat kein Paket `com.cryptochecker.app.domain` mehr (es gehört `:domain`) und keines unter
 *   `com.cryptochecker.app.data` (es gehört `:data`; Sicherung, Logo-Abgleich, Blatt-Chart und Portfolio-Alarme der App
 *   liegen in `backup`, `logos`, `chart`, `alarm`).
 * - Kein Paket liegt in zwei Modulen (`src/main` aller Module) — auch nicht in `:marketdata`, `:wear` oder einem neuen.
 * - Unit-Tests (Audit C-03): was unter `domain/src/test` bzw. `data/src/test` liegt, hält dieselben Regeln ein wie
 *   die Quellen des Moduls (Paket = Modul, aus der App nur die eigenen Schichten); kein Modul verliert still seine
 *   Tests ([TEST_FLOOR], [TOTAL_TEST_FLOOR]). Einzige Ausnahme: der gemeinsame Test-Helfer `ParityJson`
 *   ([TEST_SHARED], Ordner `test-shared/java`), den :app, :domain und :data als Testquelle mitkompilieren.
 * Ausnahmen nur über die Listen unten, je mit Grund; ein Eintrag ohne Verstoss muss wieder hinaus.
 */
class LayerBoundaryTest {

    @Test
    fun domainStaysInItsPackageWithoutRoomOkHttpOrAppLayers() {
        assertLayer("domain", DOMAIN, forbidden = listOf("androidx.room", "okhttp3"), appPackages = listOf(DOMAIN), exceptions = DOMAIN_EXCEPTIONS)
    }

    @Test
    fun dataStaysInItsPackageAndUsesOnlyDomainFromTheApp() {
        assertLayer("data", DATA, forbidden = emptyList(), appPackages = listOf(DATA, DOMAIN), exceptions = DATA_EXCEPTIONS)
    }

    @Test
    fun appHasNoDomainPackage() {
        val inDomain = sources("app").filter { inPackage(it.packageName, DOMAIN) }.map { it.name }
        assertTrue("Paket $DOMAIN gehört :domain, in :app liegen: $inDomain", inDomain.isEmpty())
    }

    @Test
    fun appHasNoDataPackage() {
        val inData = sources("app").filter { inPackage(it.packageName, DATA) }.map { it.name }
        assertTrue("Paket $DATA gehört :data, in :app liegen: $inData", inData.isEmpty())
    }

    /** Paket = Modul für alle Module: dasselbe Paket (genauer Name) kommt nie in `src/main` zweier Module vor. */
    @Test
    fun noPackageInTwoModules() {
        val root = SourceGuard.android(androidRoot(), "android/app/src/main")
        val modules = root.listFiles { dir -> File(dir, "src/main").isDirectory }.orEmpty().map { it.name }.sorted()
        assertTrue("Module: $modules", modules.containsAll(listOf("app", "data", "domain", "marketdata", "wear")))
        val byPackage = sortedMapOf<String, MutableSet<String>>()
        for (module in modules) {
            for (file in sourceFiles(module, "main")) {
                val name = PACKAGE.find(file.readText(Charsets.UTF_8))?.groupValues?.get(1) ?: continue
                byPackage.getOrPut(name) { sortedSetOf() }.add(module)
            }
        }
        assertTrue("nur ${byPackage.size} Pakete gefunden", byPackage.size > 50)
        val shared = byPackage.filterValues { it.size > 1 }.map { (name, owners) -> "$name: $owners" }
        assertTrue("Paket in zwei Modulen (Paket = Modul):\n" + shared.joinToString("\n"), shared.isEmpty())
    }

    @Test
    fun domainTestsStayInDomain() {
        assertLayer("domain", DOMAIN, forbidden = listOf("androidx.room", "okhttp3"), appPackages = listOf(DOMAIN, TEST_SHARED), exceptions = emptyMap(), sourceSet = "test")
    }

    @Test
    fun dataTestsUseOnlyDataAndDomainFromTheApp() {
        assertLayer("data", DATA, forbidden = emptyList(), appPackages = listOf(DATA, DOMAIN, TEST_SHARED), exceptions = emptyMap(), sourceSet = "test")
    }

    /**
     * Der gemeinsame Test-Helfer liegt genau einmal im Baum (`test-shared/java`, Paket [TEST_SHARED]) und ist Testquelle
     * von :app, :domain und :data; kein Modul hält eine eigene Kopie, und ausser ihm liegt dort nichts.
     */
    @Test
    fun sharedTestHelperIsOneFileCompiledIntoEachModule() {
        val root = SourceGuard.android(androidRoot(), "android/app/src/main")
        val shared = File(root, "test-shared/java").walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toList()
        assertEquals(listOf("test-shared/java/com/cryptochecker/app/parity/ParityJson.kt"), shared)
        val text = File(root, shared.single()).readText(Charsets.UTF_8)
        assertEquals(TEST_SHARED, PACKAGE.find(text)?.groupValues?.get(1))
        for (module in listOf("app", "domain", "data")) {
            val gradle = File(root, "$module/build.gradle.kts").readText(Charsets.UTF_8)
            assertTrue(
                "$module/build.gradle.kts bindet test-shared/java nicht als Testquelle ein",
                gradle.contains("getByName(\"test\") {\n            kotlin.directories.add(\"\$rootDir/test-shared/java\")"),
            )
            val copies = sourceFiles(module, "test").filter { it.name == "ParityJson.kt" }
            assertTrue("eigene Kopie in :$module: $copies", copies.isEmpty())
            val inSharedPackage = sourceFiles(module, "test").filter { PACKAGE.find(it.readText(Charsets.UTF_8))?.groupValues?.get(1) == TEST_SHARED }
            // :app behält Tests im Paket parity (Quelltext-Wächter und Tests mit App-Klassen); :domain und :data nicht
            if (module != "app") assertTrue("Tests im Paket $TEST_SHARED in :$module: ${inSharedPackage.map { it.name }}", inSharedPackage.isEmpty())
        }
    }

    /**
     * Kein Test geht beim Verschieben oder Aufräumen verloren: jedes Modul (`src/test` von app, domain, data,
     * marketdata, wear) behält mindestens seine Zahl aus [TEST_FLOOR], die Summe fällt nicht darunter.
     * Wer Tests bewusst streicht, senkt die Zahl hier mit.
     */
    @Test
    fun noModuleLosesItsTests() {
        val counts = TEST_MODULES.associateWith { module -> sourceFiles(module, "test").sumOf { testMethods(it.readText(Charsets.UTF_8)) } }
        val lost = TEST_FLOOR.filter { (module, floor) -> counts.getValue(module) < floor }.map { (module, floor) -> ":$module ${counts[module]} < $floor" }
        assertTrue("@Test-Methoden verloren (Ordner src/test des Moduls?): $lost – gezählt: $counts", lost.isEmpty())
        assertTrue("@Test-Methoden aller Module: ${counts.values.sum()} < $TOTAL_TEST_FLOOR ($counts)", counts.values.sum() >= TOTAL_TEST_FLOOR)
    }

    /** Gegenprobe: gezählt wird die Annotation am Zeilenanfang, nicht ihr Name in Kommentar oder Text. */
    @Test
    fun testMethodsAreCountedWithoutComments() {
        val at = "@" + "Test"
        assertEquals(2, testMethods("class A {\n    $at\n    fun a() = Unit\n    // $at\n    /*\n$at */\n    $at fun b() = Unit\n    val text = \"$at\"\n}\n"))
        assertEquals(0, testMethods("${at}Only fun c() = Unit\n"))
    }

    /** Gegenprobe: Paket und Importe werden gelesen (keine Kommentare), jede Regel schlägt an. */
    @Test
    fun rulesFire() {
        val text = "package $DOMAIN.util\n\nimport okhttp3.OkHttpClient\n// import x.y\nimport $APP.data.Foo\nimport $DOMAIN.model.Bar\n"
        val source = parse("java/com/cryptochecker/app/domain/util/A.kt", text)
        assertEquals("$DOMAIN.util", source.packageName)
        assertEquals(listOf("okhttp3.OkHttpClient", "$APP.data.Foo", "$DOMAIN.model.Bar"), source.imports)
        assertEquals(
            listOf("import okhttp3.OkHttpClient", "import $APP.data.Foo"),
            violations(source, DOMAIN, listOf("androidx.room", "okhttp3"), listOf(DOMAIN)),
        )
        assertEquals(emptyList<String>(), violations(source, DOMAIN, emptyList(), listOf(DATA, DOMAIN)))
        val misplaced = parse("java/com/cryptochecker/app/util/A.kt", "package $APP.util\n")
        assertEquals(listOf("Paket $APP.util statt $DOMAIN"), violations(misplaced, DOMAIN, emptyList(), listOf(DOMAIN)))
        val otherFolder = parse("java/com/cryptochecker/app/domain/A.kt", "package $DOMAIN.util\n")
        assertEquals(listOf("Paket $DOMAIN.util passt nicht zum Ordner"), violations(otherFolder, DOMAIN, emptyList(), listOf(DOMAIN)))
        assertTrue(inPackage(DOMAIN, DOMAIN))
        assertTrue(!inPackage("${DOMAIN}x.A", DOMAIN))
    }

    private fun assertLayer(
        module: String, base: String, forbidden: List<String>, appPackages: List<String>, exceptions: Map<String, String>,
        sourceSet: String = "main",
    ) {
        val found = sources(module, sourceSet).associate { it.name to violations(it, base, forbidden, appPackages) }.filterValues { it.isNotEmpty() }
        val problems = found.filterKeys { it !in exceptions }.toSortedMap().map { (name, list) -> "$name: ${list.joinToString("; ")}" }
        assertTrue("Schichtgrenze von :$module ($sourceSet) verletzt:\n" + problems.joinToString("\n"), problems.isEmpty())
        val stale = exceptions.keys.filter { it !in found }.sorted()
        assertTrue("Ausnahme ohne Verstoss entfernen: $stale", stale.isEmpty())
    }

    private class Source(val name: String, val packageName: String, val imports: List<String>)

    /** Kotlin-Dateien unter `<module>/src/<sourceSet>`, Name relativ dazu (`java/com/…`). */
    private fun sources(module: String, sourceSet: String = "main"): List<Source> {
        val files = sourceFiles(module, sourceSet)
        val least = if (sourceSet == "main") 100 else 50
        assertTrue("$module/src/$sourceSet: nur ${files.size} Kotlin-Dateien gefunden", files.size > least)
        val dir = sourceDir(module, sourceSet)
        return files.map { parse(it.relativeTo(dir).invariantSeparatorsPath, it.readText(Charsets.UTF_8)) }
    }

    private fun sourceDir(module: String, sourceSet: String): File {
        val root = SourceGuard.android(androidRoot(), "android/app/src/main")
        return SourceGuard.android(File(root, "$module/src/$sourceSet").takeIf { it.isDirectory }, "$module/src/$sourceSet")
    }

    private fun sourceFiles(module: String, sourceSet: String): List<File> =
        sourceDir(module, sourceSet).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** `@Test` am Zeilenanfang (nach Einrückung), ohne Kommentare. */
    private fun testMethods(text: String): Int =
        TEST_ANNOTATION.findAll(text.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")).count()

    private fun parse(name: String, text: String) = Source(
        name = name,
        packageName = PACKAGE.find(text)?.groupValues?.get(1).orEmpty(),
        imports = IMPORT.findAll(text).map { it.groupValues[1] }.toList(),
    )

    private fun violations(source: Source, base: String, forbidden: List<String>, appPackages: List<String>): List<String> {
        val folder = source.name.substringBeforeLast('/').removePrefix("java/").replace('/', '.')
        val placement = when {
            !inPackage(source.packageName, base) -> listOf("Paket ${source.packageName} statt $base")
            source.packageName != folder -> listOf("Paket ${source.packageName} passt nicht zum Ordner")
            else -> emptyList()
        }
        val imports = source.imports.filter { imported ->
            forbidden.any { inPackage(imported, it) } || (inPackage(imported, APP) && appPackages.none { inPackage(imported, it) })
        }.map { "import $it" }
        return placement + imports
    }

    private fun inPackage(name: String, base: String): Boolean = name == base || name.startsWith("$base.")

    /** Ordner `android/` (mit `app/src/main`). */
    private fun androidRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (rel in listOf("app/src/main", "android/app/src/main")) {
                val found = File(dir, rel)
                if (found.isDirectory) return found.parentFile.parentFile.parentFile
            }
            dir = dir.parentFile
        }
        return null
    }

    private companion object {
        const val APP = "com.cryptochecker.app"
        const val DOMAIN = "$APP.domain"
        const val DATA = "$APP.data"

        /** Paket des gemeinsamen Test-Helfers (`test-shared/java`): Tests von :domain und :data dürfen es importieren. */
        const val TEST_SHARED = "$APP.parity"
        val PACKAGE = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)
        val IMPORT = Regex("""^import\s+([\w.*]+)""", RegexOption.MULTILINE)
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("""//[^\n]*""")
        val TEST_ANNOTATION = Regex("""^\s*@Test\b""", RegexOption.MULTILINE)

        /** Module mit Unit-Tests unter `src/test`. */
        val TEST_MODULES = listOf("app", "domain", "data", "marketdata", "wear")

        /**
         * Untergrenze der `@Test`-Methoden je Modul = heutiger Stand (gezählt wie [testMethods]): weniger heisst
         * verloren. Neue Tests heben die Zahl nicht automatisch – wer Tests hinzufügt, hebt die Untergrenze im selben
         * Commit auf den neuen Stand (die Meldung von [noModuleLosesItsTests] nennt die gezählten Werte); wer Tests
         * bewusst streicht, senkt sie mit Begründung.
         */
        val TEST_FLOOR = mapOf("app" to 1552, "domain" to 1571, "data" to 567, "marketdata" to 32, "wear" to 47)

        /** Alle Module zusammen = Summe von [TEST_FLOOR]. */
        val TOTAL_TEST_FLOOR = TEST_FLOOR.values.sum()

        /** Datei (relativ zu `domain/src/main`) → Grund, warum sie die Regel verletzen darf. Leer: keine Ausnahme. */
        val DOMAIN_EXCEPTIONS: Map<String, String> = emptyMap()

        /** Datei (relativ zu `data/src/main`) → Grund. Leer: keine Ausnahme. */
        val DATA_EXCEPTIONS: Map<String, String> = emptyMap()
    }
}
