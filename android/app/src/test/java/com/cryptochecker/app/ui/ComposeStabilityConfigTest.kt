package com.cryptochecker.app.ui

import com.cryptochecker.app.parity.ParityJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `gradle/compose-stability.conf`: Modelle aus :domain und :marketdata (Module ohne Compose-Compiler), die Compose als
 * stabil behandeln darf. Das stimmt nur, solange sie unveränderlich sind — sonst überspringt Compose eine Zeile, deren
 * Inhalt sich geändert hat. Dieser Wächter prüft jeden Eintrag am Quelltext: Datei vorhanden, kein `var`, keine
 * veränderliche Sammlung im Konstruktor; dazu die Einbindung in :app.
 */
class ComposeStabilityConfigTest {

    private val android: File = ParityJson.dir().parentFile!!.parentFile!!.normalize()
    private val entries: List<String> = File(android, "gradle/compose-stability.conf").readLines(Charsets.UTF_8)
        .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }

    @Test
    fun appUsesTheFile() {
        val gradle = File(android, "app/build.gradle.kts").readText(Charsets.UTF_8)
        assertTrue(gradle.contains(
            "stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file(\"gradle/compose-stability.conf\"))"))
    }

    @Test
    fun listsExactlyTheImmutableModels() {
        assertEquals(
            listOf(
                "com.cryptochecker.app.domain.model.AlarmEntity",
                "com.cryptochecker.app.domain.model.ExchangeInfo",
                "com.cryptochecker.app.domain.model.ExchangePairsInfo",
                "com.cryptochecker.app.domain.model.WatchEntity",
                "com.cryptochecker.marketdata.model.ExchangePair",
                "com.cryptochecker.marketdata.model.PairQuery",
                "com.cryptochecker.marketdata.model.PostRequest",
            ),
            entries,
        )
    }

    @Test
    fun everyEntryIsImmutableInTheSource() {
        for (entry in entries) {
            val name = entry.substringAfterLast('.')
            val relative = entry.substringBeforeLast('.').replace('.', '/') + "/$name.kt"
            val file = listOf("domain", "marketdata").map { File(android, "$it/src/main/java/$relative") }.firstOrNull { it.isFile }
            assertTrue("$entry: Quelldatei fehlt (umbenannt? Eintrag in gradle/compose-stability.conf anpassen)", file != null)
            val code = file!!.readText(Charsets.UTF_8).replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")
            assertFalse("$entry: `var` in ${file.name} — nicht mehr unveränderlich", VAR.containsMatchIn(code))
            val head = Regex("""\bclass\s+$name\s*\(""").find(code)
            assertTrue("$entry: Klasse mit Konstruktor nicht gefunden", head != null)
            val parameters = constructor(code, head!!.range.last)
            assertFalse("$entry: veränderlicher Typ im Konstruktor: $parameters", MUTABLE_TYPE.containsMatchIn(parameters))
        }
    }

    /** Gegenprobe: Der Prüfer erkennt `var` und veränderliche Typen (an den bewusst nicht gelisteten Klassen). */
    @Test
    fun checkerSeesMutableModels() {
        val model = File(android, "marketdata/src/main/java/com/cryptochecker/marketdata/model")
        assertTrue(VAR.containsMatchIn(File(model, "SimpleTicker.kt").readText(Charsets.UTF_8)))
        val exchange = File(model, "Exchange.kt").readText(Charsets.UTF_8)
        val typeAlias = exchange.lineSequence().first { it.startsWith("typealias CurrencyPairsMap") }
        assertTrue(MUTABLE_TYPE.containsMatchIn(typeAlias))
        assertFalse(entries.any { it.endsWith(".Ticker") || it.endsWith(".SimpleTicker") || it.endsWith(".Exchange") })
        assertFalse(entries.any { it.endsWith(".BulkTickers") || it.endsWith(".ExchangeTickerResult") || it.endsWith("*") })
    }

    /** Text zwischen der Klammer bei [open] und ihrer schliessenden Klammer. */
    private fun constructor(code: String, open: Int): String {
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return code.substring(open + 1, index)
            }
        }
        return code.substring(open + 1)
    }

    private companion object {
        val VAR = Regex("""\bvar\s""")
        val MUTABLE_TYPE = Regex("""\b(?:Mutable\w+|Array(?:List)?|\w*HashMap|\w*HashSet|StringBuilder|IntArray|DoubleArray|LongArray)\b""")
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("""//[^\n]*""")
    }
}
