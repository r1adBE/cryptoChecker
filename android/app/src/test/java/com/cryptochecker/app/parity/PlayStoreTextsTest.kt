package com.cryptochecker.app.parity

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Play-Texte (`fastlane/metadata/android/<Sprache>/`): Längen wie die Play Console zählt (Zeichen samt Zeilenende,
 * Beschreibung ≤ 4000) und der Satz zu den Hintergrund-Prüfungen eindeutig «höchstens einmal je 15 Minuten».
 */
class PlayStoreTextsTest {

    private val root = File(ParityJson.dir().parentFile.parentFile, "fastlane/metadata/android")

    private fun chars(f: File): Int = f.readText(Charsets.UTF_8).let { it.codePointCount(0, it.length) }

    @Test
    fun fullDescriptionsFitThePlayLimit() {
        val dirs = root.listFiles { f -> f.isDirectory }.orEmpty()
        assertTrue(dirs.size >= 31)
        for (d in dirs) {
            val n = chars(File(d, "full_description.txt"))
            assertTrue("${d.name}: $n > 4000", n <= 4000)
        }
    }

    @Test
    fun backgroundChecksSayOncePerFifteenMinutes() {
        val expected = mapOf(
            "en-US" to "at most once per 15 minutes",
            "fr-FR" to "pas plus d’une fois toutes les 15 minutes",
            "it-IT" to "al massimo una volta ogni 15 minuti",
            "es-ES" to "como mucho una vez cada 15 min.",
            "nl-NL" to "hooguit eens per 15 minuten",
            "da-DK" to "højst én gang pr. 15 minutter",
            "no-NO" to "høyst én gang per 15 minutter",
            "sv-SE" to "högst en gång per 15 minuter",
            "el-GR" to "το πολύ μία φορά ανά 15 λεπτά",
            "fa" to "حداکثر یک بار در هر ۱۵ دقیقه",
            "sq" to "jo më shpesh se çdo 15 minuta",
        )
        for ((lang, phrase) in expected) {
            val text = File(root, "$lang/full_description.txt").readText(Charsets.UTF_8)
            assertTrue("$lang: «$phrase» fehlt", phrase in text)
            // Seit Build 103: die Grenze steht im ersten Absatz (sichtbar vor «Mehr»), nicht erst weit unten
            assertTrue("$lang: Hintergrund-Grenze nicht im ersten Absatz", phrase in text.substringBefore("\n"))
        }
    }
}
