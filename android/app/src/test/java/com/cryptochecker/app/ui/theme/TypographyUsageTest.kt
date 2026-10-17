package com.cryptochecker.app.ui.theme

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Wache über die kleinste Schriftstufe: `typography.labelSmall` (11 sp) bzw. iOS `.caption2` (11 pt)
 * gehören nur zu Quellenangaben, rechtlichen Fusszeilen und Chart-Beschriftungen; Metadaten nehmen
 * `label` (12 sp) bzw. `.caption`. Die Obergrenzen sind der Stand nach Build 56 und dürfen nur
 * sinken — wer eine Stelle entfernt, senkt die Zahl hier mit. Liest die Quellen von der Platte
 * (wie [AccentContrastTest]); das iOS-Verzeichnis ist optional (reiner Android-Checkout).
 */
class TypographyUsageTest {

    @Test
    fun labelSmallUsesOnlyGoDown() {
        val root = androidSources()
        assertNotNull("android/app/src/main/java nicht gefunden (Arbeitsverzeichnis?)", root)
        for ((area, ceiling) in ANDROID_CEILINGS) {
            val dir = if (area.isEmpty()) root!! else File(root, area)
            val uses = countUses(dir, "kt", LABEL_SMALL)
            assertTrue(
                "typography.labelSmall in «${area.ifEmpty { "alle" }}»: $uses Stellen, erlaubt höchstens $ceiling — " +
                    "für Metadaten MaterialTheme.typography.label nehmen",
                uses <= ceiling
            )
        }
    }

    @Test
    fun caption2UsesOnlyGoDownOnIos() {
        val root = iosSources() ?: return // reiner Android-Checkout
        val uses = countUses(root, "swift", CAPTION2)
        assertTrue(
            ".caption2 in ios/App: $uses Stellen, erlaubt höchstens $IOS_CAPTION2_CEILING — für Metadaten .caption nehmen",
            uses <= IOS_CAPTION2_CEILING
        )
    }

    /** Treffer in Codezeilen (Kommentarzeilen zählen nicht) aller Dateien mit [extension] unter [dir]. */
    private fun countUses(dir: File, extension: String, pattern: Regex): Int =
        dir.walkTopDown()
            .filter { it.isFile && it.extension == extension }
            .sumOf { file ->
                file.readLines(Charsets.UTF_8)
                    .filterNot { it.trimStart().let { line -> line.startsWith("//") || line.startsWith("*") || line.startsWith("/*") } }
                    .sumOf { line -> pattern.findAll(line).count() }
            }

    private fun androidSources(): File? = findUp(listOf("", "app/", "android/app/").map { it + "src/main/java/com/cryptochecker/app" })

    private fun iosSources(): File? = findUp(listOf("ios/App", "../ios/App", "../../ios/App"))

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
        val LABEL_SMALL = Regex("""typography\.labelSmall\b""")
        val CAPTION2 = Regex("""\.caption2\b""")

        /** Bereich (relativ zu `com/cryptochecker/app`, leer = alles) → höchstens so viele Stellen. */
        val ANDROID_CEILINGS = linkedMapOf(
            "" to 49,
            "ui/features/info" to 9,
            "ui/features/portfolio" to 14,
            "ui/features/watchlist" to 23,
        )

        const val IOS_CAPTION2_CEILING = 45
    }
}
