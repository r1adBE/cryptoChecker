package com.cryptochecker.app.ui.theme

import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Wache über das Abstandsraster 4/8/12/16/24/32 (dp in Android, pt in iOS, siehe [Spacing] und
 * `ios/Shared/Util/Spacing.swift`): Innenabstände, `spacedBy`/`spacing:`, Spacer als Lücke und
 * `contentPadding` nehmen eine Rasterstufe. Gezählt werden Zwischenwerte (5, 6, 7, 9, 10 … 30) in
 * Abstands-Aufrufen; Grössen (`size`, `height`, `width` ausser in `Spacer`), Radien, Strichstärken,
 * Feinabstimmungen von 1–3 und negative Werte (Ausgleich einer grösseren Tippfläche) zählen nicht.
 *
 * Seit Build 80 sind alle Abstände auf dem Raster ausser den Stellen in [ANDROID_CEILINGS] bzw.
 * [IOS_CEILINGS], die bewusst bleiben (Begründung je Eintrag). Die Obergrenzen dürfen nur sinken —
 * wer eine Stelle bereinigt, senkt die Zahl hier mit. Liest die Quellen von der Platte (wie
 * [TypographyUsageTest]); das iOS-Verzeichnis ist optional (reiner Android-Checkout).
 *
 * Seit Build 82 auch die Widget-Layouts (`res/layout/widget_*.xml`, RemoteViews): `android:padding*`,
 * `android:layout_margin*` und Breite/Höhe von `<Space>` als Lücke. Widgets haben wenig Platz, darum
 * wurde abgerundet (6 → 4, 10 → 8); was bleibt, steht in [WIDGET_XML_CEILINGS]. Die iOS-Widgets
 * sind seit Build 82 ganz auf dem Raster (keine Obergrenze mehr).
 */
class SpacingUsageTest {

    @Test
    fun androidSpacingStaysOnGrid() {
        val root = androidSources()
        assertNotNull("android/app/src/main/java nicht gefunden (Arbeitsverzeichnis?)", root)
        val problems = offenders(root!!, "kt", ANDROID_OFF_GRID, ANDROID_CEILINGS)
        assertTrue(
            "Abstände ausserhalb des Rasters 4/8/12/16/24/32 dp (Spacing.xs … Spacing.xxl nehmen):\n" +
                problems.joinToString("\n"),
            problems.isEmpty()
        )
    }

    @Test
    fun iosSpacingStaysOnGrid() {
        val root = SourceGuard.ios(iosRoot(), "ios/App")
        val problems = IOS_AREAS.flatMap { area ->
            val dir = File(root, area)
            if (!dir.isDirectory) emptyList()
            else offenders(dir, "swift", IOS_OFF_GRID, IOS_CEILINGS, prefix = "$area/")
        }
        assertTrue(
            "Abstände ausserhalb des Rasters 4/8/12/16/24/32 pt (Spacing.xs … Spacing.xxl nehmen):\n" +
                problems.joinToString("\n"),
            problems.isEmpty()
        )
    }

    @Test
    fun androidWidgetLayoutsStayOnGrid() {
        val dir = androidLayouts()
        assertNotNull("android/app/src/main/res/layout nicht gefunden (Arbeitsverzeichnis?)", dir)
        val problems = dir!!.listFiles { f -> f.isFile && f.name.startsWith("widget_") && f.extension == "xml" }
            .orEmpty()
            .sortedBy { it.name }
            .mapNotNull { file ->
                val text = file.readText(Charsets.UTF_8)
                fun lineOf(offset: Int) = text.substring(0, offset).count { it == '\n' } + 1
                val margins = XML_OFF_GRID.findAll(text).map { lineOf(it.range.first) to it.value }.toList()
                val spaces = XML_SPACE.findAll(text).flatMap { space ->
                    XML_SPACE_OFF_GRID.findAll(space.value).map { lineOf(space.range.first + it.range.first) to it.value }
                }.toList()
                val hits = (margins + spaces).sortedBy { it.first }
                val allowed = WIDGET_XML_CEILINGS[file.name] ?: 0
                if (hits.size > allowed) {
                    "${file.name}: ${hits.size} Stellen, erlaubt $allowed\n" +
                        hits.joinToString("\n") { (line, value) -> "  $line: $value" }
                } else null
            }
        assertTrue(
            "Widget-Abstände ausserhalb des Rasters 4/8/12/16/24/32 dp (abrunden, Widgets haben wenig Platz):\n" +
                problems.joinToString("\n"),
            problems.isEmpty()
        )
    }

    /** Beide Plattformen haben dieselben Stufen mit denselben Werten. */
    @Test
    fun spacingTokensMatchIos() {
        val androidRoot = SourceGuard.android(androidSources(), "android/app/src/main/java")
        val iosRoot = SourceGuard.ios(iosRoot(), "ios/App")
        val android = Regex("""val (\w+) = (\d+)\.dp""")
            .findAll(File(androidRoot, "ui/theme/Spacing.kt").readText(Charsets.UTF_8))
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        val swift = File(iosRoot, "Shared/Util/Spacing.swift").readText(Charsets.UTF_8)
        val block = swift.substringAfter("enum Spacing {").substringBefore("}")
        val ios = Regex("""static let (\w+): CGFloat = (\d+)""")
            .findAll(block)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        assertEquals(mapOf("xs" to 4, "sm" to 8, "md" to 12, "lg" to 16, "xl" to 24, "xxl" to 32), android)
        assertEquals(android, ios)
    }

    /**
     * Freiraum unter Listen mit schwebendem «+»-Knopf: auf beiden Plattformen 3 × xxl (96) und
     * nur über `Spacing.fabClearance` — keine nackte 96 mehr als Listenende (Portfolio, Coin, Alarme).
     */
    @Test
    fun fabClearanceIsDerivedFromGrid() {
        val androidRoot = SourceGuard.android(androidSources(), "android/app/src/main/java")
        assertTrue(File(androidRoot, "ui/theme/Spacing.kt").readText(Charsets.UTF_8).contains("val fabClearance = xxl * 3"))
        val literal = Regex("""bottom = 96\.dp""")
        val androidHits = androidRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { literal.containsMatchIn(it.readText(Charsets.UTF_8)) }.map { it.name }.toList()
        assertEquals(emptyList<String>(), androidHits)
        val iosRoot = SourceGuard.ios(iosRoot(), "ios/App")
        val swift = File(iosRoot, "Shared/Util/Spacing.swift").readText(Charsets.UTF_8)
        assertTrue(swift.contains("static let fabClearance: CGFloat = Spacing.xxl * 3"))
        val iosHits = File(iosRoot, "App").walkTopDown().filter { it.isFile && it.extension == "swift" }
            .filter { it.readText(Charsets.UTF_8).contains("Color.clear.frame(height: 96)") }.map { it.name }.toList()
        assertEquals(emptyList<String>(), iosHits)
    }

    /**
     * Dateien unter [dir] mit mehr Treffern als erlaubt (nicht gelistete Dateien: 0), mit den
     * betroffenen Zeilen. Kommentarzeilen zählen nicht.
     */
    private fun offenders(
        dir: File,
        extension: String,
        pattern: Regex,
        ceilings: Map<String, Int>,
        prefix: String = "",
    ): List<String> =
        dir.walkTopDown()
            .filter { it.isFile && it.extension == extension }
            .mapNotNull { file ->
                val rel = prefix + file.relativeTo(dir).invariantSeparatorsPath
                val hits = file.readLines(Charsets.UTF_8)
                    .withIndex()
                    .filterNot { (_, line) -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }
                    .flatMap { (index, line) -> pattern.findAll(line).map { "  ${index + 1}: ${line.trim()}" }.toList() }
                val allowed = ceilings[rel] ?: 0
                if (hits.size > allowed) "$rel: ${hits.size} Stellen, erlaubt $allowed\n" + hits.joinToString("\n") else null
            }
            .toList()

    private fun androidSources(): File? = findUp(listOf("", "app/", "android/app/").map { it + "src/main/java/com/cryptochecker/app" })

    private fun androidLayouts(): File? = findUp(listOf("", "app/", "android/app/").map { it + "src/main/res/layout" })

    /** `ios/` (mit `App`, `Widgets`, `Shared`). */
    private fun iosRoot(): File? = findUp(listOf("ios/App", "../ios/App", "../../ios/App"))?.parentFile

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
        const val OFF_GRID = "5|6|7|9|10|11|13|14|15|17|18|19|20|22|26|28|30"

        /**
         * `padding(… 6.dp …)`, `spacedBy(6.dp)`, `PaddingValues(6.dp)`, `Spacer(Modifier.width(6.dp))` und
         * benannte Abstände über mehrere Zeilen (`top = 6.dp`); nicht 1.5.dp oder 16.dp.
         */
        val ANDROID_OFF_GRID = Regex(
            """(?:(?:padding|spacedBy|PaddingValues|Spacer\(Modifier\.(?:width|height))\([^)]*?|\b(?:start|end|top|bottom|horizontal|vertical|all) = )(?<![\d.])(?:$OFF_GRID)\.dp\b"""
        )

        /** `spacing: 6`, `Spacer(minLength: 6)`, `.padding(6)`, `.padding(.top, 6)`, `EdgeInsets(top: 6 …)`; nicht -6. */
        val IOS_OFF_GRID = Regex(
            """(?:\b(?:spacing|minLength|top|bottom|leading|trailing):\s*|\.padding\((?:\.\w+,\s*|\[[^\]]*\],\s*)?)(?:$OFF_GRID)(?:\.0)?\b(?![.\d])"""
        )

        /** Datei (relativ zu `com/cryptochecker/app`) → höchstens so viele Stellen, die bewusst bleiben. */
        val ANDROID_CEILINGS = mapOf(
            // Chart-Inneres: Platz für die Beschriftung über der Kurve
            "ui/features/info/MarketCycleChart.kt" to 1,
            // Suchfeld 48 dp hoch, Pille 44 dp: je 2 dp weniger Abstand (12 − 2 = 10), damit nichts wächst
            "ui/features/watchlist/WatchGroupEditSheet.kt" to 1,
        )

        /** `android:paddingStart="6dp"`, `android:layout_marginTop="10dp"`; nicht 1–3 dp (Feinabstimmung) oder 0dp. */
        val XML_OFF_GRID = Regex("""android:(?:padding|layout_margin)\w*="(?:$OFF_GRID)dp"""")

        /** Ein `<Space …>`-Element (über mehrere Zeilen) und seine Breite/Höhe als Lücke. */
        val XML_SPACE = Regex("""<Space\b[^>]*>""")
        val XML_SPACE_OFF_GRID = Regex("""android:layout_(?:width|height)="(?:$OFF_GRID)dp"""")

        /** Widget-Layout (Dateiname in `res/layout`) → höchstens so viele Stellen, die bewusst bleiben. */
        val WIDGET_XML_CEILINGS = mapOf(
            // Vorschau: 6 dp stehen für die zwei Leerzeichen vor dem Prozent (WidgetTextFit.PERCENT_GAP)
            "widget_preview_portfolio.xml" to 1,
        )

        val IOS_AREAS = listOf("App", "Widgets")

        /** Datei (relativ zu `ios/`) → höchstens so viele Stellen, die bewusst bleiben. */
        val IOS_CEILINGS = mapOf(
            // Zeilen der Marktdaten: wie Android 2 × 3 dp Zeilen-Innenabstand (Feinabstimmung) = 6
            // (seit Build 83 in `PulseFacts`, CryptoPulseParts.swift)
            "App/Features/Cycle/CryptoPulseParts.swift" to 1,
            // Widgets/: seit Build 82 alle auf dem Raster (abgerundet wie die Android-Widgets) — keine Ausnahme
        )
    }
}
