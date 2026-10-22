package com.cryptochecker.app.ui.theme

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Wache über die Dateilänge: jede Produktionsdatei hat höchstens [LIMIT] Zeilen — Kotlin unter
 * `android/app/src/main` und `android/marketdata/src/main`, Swift unter `ios/App`, `ios/Shared` und
 * `ios/Widgets`. Lange Dateien werden beim Lesen und in Reviews unübersichtlich und wachsen sonst
 * unbemerkt weiter; aufteilen geht fast immer (Erweiterungen, Hilfsdateien daneben).
 *
 * Ausnahmen stehen mit Begründung in [CEILINGS]; ihre Obergrenze darf nur sinken — wer eine solche Datei
 * kürzt, senkt die Zahl hier mit, und fällt sie unter [LIMIT], kommt der Eintrag weg. Liest die Quellen
 * von der Platte (wie [SpacingUsageTest]); das iOS-Verzeichnis ist optional (reiner Android-Checkout).
 */
class FileLengthTest {

    @Test
    fun androidDateienBleibenKurz() {
        val root = androidRoot()
        assertNotNull("android/app/src/main nicht gefunden (Arbeitsverzeichnis?)", root)
        val files = ANDROID_MODULES.flatMap { module ->
            val main = File(root, "$module/src/main")
            if (!main.isDirectory) emptyList()
            else sources(main, "kt").map { it.relativeTo(main).invariantSeparatorsPath.removePrefix("java/com/cryptochecker/") to it }
        }
        assertTrue("keine Kotlin-Quellen gefunden", files.isNotEmpty())
        assertShort(files)
    }

    @Test
    fun iosDateienBleibenKurz() {
        val root = iosRoot() ?: return // reiner Android-Checkout
        val files = IOS_AREAS.flatMap { area ->
            val dir = File(root, area)
            if (!dir.isDirectory) emptyList()
            else sources(dir, "swift").map { "ios/$area/" + it.relativeTo(dir).invariantSeparatorsPath to it }
        }
        assertShort(files)
    }

    /** [files]: Anzeigename (Schlüssel in [CEILINGS]) → Datei. */
    private fun assertShort(files: List<Pair<String, File>>) {
        val lengths = files.associate { (name, file) -> name to file.readLines(Charsets.UTF_8).size }
        val tooLong = lengths.filter { (name, lines) -> lines > (CEILINGS[name] ?: LIMIT) }
            .toSortedMap()
            .map { (name, lines) ->
                val ceiling = CEILINGS[name]
                if (ceiling != null) "$name: $lines Zeilen, Obergrenze $ceiling (darf nur sinken)"
                else "$name: $lines Zeilen, erlaubt $LIMIT"
            }
        assertTrue(
            "Dateien länger als $LIMIT Zeilen — aufteilen (Erweiterung oder Hilfsdatei daneben):\n" + tooLong.joinToString("\n"),
            tooLong.isEmpty()
        )
        // Ausnahmen, die nicht mehr nötig sind, kommen aus der Liste (sonst wächst die Datei unbemerkt wieder)
        val stale = CEILINGS.keys.filter { name -> lengths[name]?.let { it <= LIMIT } == true }
        assertTrue(
            "Ausnahmen nicht mehr nötig (Datei hat höchstens $LIMIT Zeilen), Eintrag in CEILINGS entfernen: $stale",
            stale.isEmpty()
        )
    }

    private fun sources(dir: File, extension: String): List<File> =
        dir.walkTopDown().filter { it.isFile && it.extension == extension }.toList()

    /** Ordner `android/` (mit `app/src/main`). */
    private fun androidRoot(): File? = findUp(listOf("app/src/main", "android/app/src/main"))?.parentFile?.parentFile?.parentFile

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
        const val LIMIT = 400

        val ANDROID_MODULES = listOf("app", "marketdata")
        val IOS_AREAS = listOf("App", "Shared", "Widgets")

        /**
         * Datei → höchstens so viele Zeilen. Android relativ zu `src/main/java/com/cryptochecker/`
         * (`app/…`, `marketdata/…`), iOS mit `ios/` davor.
         */
        // Seit Build 103 leer: AppSettings.kt (vorher 422) teilt Wahlmöglichkeiten mit AppSettingsDefaults.kt
        val CEILINGS: Map<String, Int> = emptyMap()
    }
}
