package com.cryptochecker.app.data

import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Seit Build 106 (Review TST4-01, F-08): Rückfrage «Merkliste wird nicht gespeichert» je AUFRUF statt je Datei. Die
 * Schreibwege des Nutzers kommen aus dem Repository (Funktionen, deren Rumpf `userWriteRefused(` enthält, plus
 * Weiterleitungen darauf); jeder Aufruf ausserhalb von `data/` (Oberfläche, Mitteilungen, Widgets …) liegt in einer Funktion,
 * die vorher [confirmUserWrite] fragt (Block noch offen) bzw. im Block von `launchWrite { … }`. Ein Hilfsweg ohne eigene
 * Frage (z. B. `deleteSnapshots`) gilt, wenn jeder seiner Aufrufer fragt. Gegenwart siehe [WatchRepositoryLockTest].
 */
class WatchRepositoryCallGuardTest {

    @Test
    fun everyUserWriteCallConfirmsFirst() {
        val root = SourceGuard.android(
            listOf("src/main/java/com/cryptochecker/app", "app/src/main/java/com/cryptochecker/app").map(::File)
                .firstOrNull { it.isDirectory },
            "src/main/java/com/cryptochecker/app",
        )
        val repo = File(root, "data").listFiles { f -> f.name.startsWith("WatchRepository") && f.extension == "kt" }!!
            .map { strip(it.readText()) }
        val paths = writePaths(repo)
        // Folge-Schreibwege fragen nie und lehnen nie ab (`userWriteRefused(notify = false)`): laufen nur nach einem
        // bestätigten Schreibweg (Meldekurs nach dem Einschalten, Prozent-Bezug nach dem Paarwechsel)
        assertEquals(setOf("setNotifiedPrice", "setMissingPercentReferences"), paths.second)
        // Ableitung greift (Stichprobe direkter Wege und Weiterleitungen)
        assertTrue(paths.first.toString(), paths.first.containsAll(listOf("setFavorite", "tryAddWatch", "addWatch", "saveAlarm", "deleteAlarm")))
        val sources = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(root).invariantSeparatorsPath to strip(it.readText()) }
            .filterKeys { !it.startsWith("data/") }
        val result = check(sources, paths.first)
        assertTrue("zu wenige Aufrufe gefunden (${result.calls}) — Scan defekt?", result.calls >= 20)
        assertEquals("Ausnahmeliste veraltet", SYSTEM_CALLS.toSet(), result.exempted)
        assertTrue("Schreibweg ohne Rückfrage F-08 davor:\n" + result.problems.joinToString("\n"), result.problems.isEmpty())
        // Jedes launchWrite fragt selbst
        for ((file, text) in sources) {
            functions(text).filter { it.name == "launchWrite" }.forEach {
                assertTrue(file, text.substring(it.start, it.end).contains("confirmUserWrite()"))
            }
        }
    }

    /** Gegenprobe mit eingebautem Beispiel: ohne Rückfrage, nach dem Block, Hilfsweg ohne fragenden Aufrufer → Befund. */
    @Test
    fun counterSampleIsFlagged() {
        val writes = setOf("setFavorite", "reorder", "deleteWatches", "setAlarmEnabled")
        val sample = """
            class C(private val watchRepository: WatchRepository, private val other: Store) {
                private fun launchWrite(block: suspend () -> Unit) = scope.launch { if (watchRepository.confirmUserWrite()) block() }
                fun ok(id: Long) {
                    launchWrite { watchRepository.setFavorite(id, true) }
                }
                fun okGuard(id: Long) {
                    scope.launch {
                        if (!watchRepository.confirmUserWrite()) return@launch
                        helper(id)
                    }
                }
                private suspend fun helper(id: Long) { watchRepository.deleteWatches(listOf(id)) }
                fun bad(id: Long) {
                    scope.launch { watchRepository.setFavorite(id, false) }
                }
                fun afterBlock(ids: List<Long>) {
                    launchWrite { }
                    scope.launch { watchRepository.reorder(ids) }
                }
                fun closedGuard(id: Long) {
                    scope.launch { watchRepository.confirmUserWrite() }
                    scope.launch { watchRepository.setAlarmEnabled(id, true) }
                }
                fun systemNotListed(id: Long) { scope.launch { watchRepository.setAlarmEnabled(id, true, fromUser = false) } }
                // watchRepository.setFavorite(1, true) — Kommentar zählt nicht
                fun notRepo() { scope.launch { other.reorder(emptyList()) } }
            }
        """.trimIndent()
        val result = check(mapOf("ui/Sample.kt" to strip(sample)), writes)
        assertEquals(listOf("bad", "afterBlock", "closedGuard", "systemNotListed"), result.problems.map { it.substringAfter("fun ").substringBefore(":") })
        assertEquals(6, result.calls)
    }

    // ---------------------------------------------------------------------------------------------------------------------

    private class Fn(val name: String, val receiver: String?, val start: Int, val bodyStart: Int, val end: Int)

    private class Result(val calls: Int, val problems: List<String>, val exempted: Set<Pair<String, String>>)

    /**
     * Kommentare durch Leerraum ersetzen (Positionen bleiben). Kleiner Zerleger: Zeichenketten (normal, roh, Zeichen) bleiben
     * stehen, Kommentarzeichen darin öffnen keinen Kommentar (z. B. MIME-Muster «text/STERN» oder «https:»);
     * Blockkommentare verschachtelt wie in Kotlin.
     */
    private fun strip(text: String): String {
        val out = StringBuilder(text)
        var i = 0
        fun blank(from: Int, to: Int) { for (k in from until to) if (out[k] != '\n') out.setCharAt(k, ' ') }
        while (i < text.length) {
            when {
                text.startsWith("//", i) -> { val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }; blank(i, end); i = end }
                text.startsWith("/*", i) -> {
                    var depth = 0
                    var k = i
                    while (k < text.length) {
                        if (text.startsWith("/*", k)) { depth++; k += 2 } else if (text.startsWith("*/", k)) { depth--; k += 2; if (depth == 0) break } else k++
                    }
                    blank(i, k); i = k
                }
                text.startsWith("\"\"\"", i) -> { val end = text.indexOf("\"\"\"", i + 3); i = if (end < 0) text.length else end + 3 }
                text[i] == '"' || text[i] == '\'' -> {
                    val quote = text[i]
                    var k = i + 1
                    while (k < text.length && text[k] != quote && text[k] != '\n') k += if (text[k] == '\\') 2 else 1
                    i = k + 1
                }
                else -> i++
            }
        }
        return out.toString()
    }

    private fun functions(text: String): List<Fn> {
        val found = FUN.findAll(text).toList()
        return found.mapIndexed { i, m ->
            Fn(m.groupValues[2], m.groupValues[1].removeSuffix(".").ifEmpty { null }, m.range.first, m.range.last + 1,
                found.getOrNull(i + 1)?.range?.first ?: text.length)
        }
    }

    /** (Schreibwege, stille Folge-Schreibwege) aus den Repository-Dateien. */
    private fun writePaths(repo: List<String>): Pair<Set<String>, Set<String>> {
        val writes = mutableSetOf<String>()
        val silent = mutableSetOf<String>()
        val all = repo.flatMap { text -> functions(text).map { it to text } }
        for ((fn, text) in all) {
            if (fn.name == "userWriteRefused" || fn.name == "confirmUserWrite") continue
            val calls = REFUSED.findAll(text.substring(fn.bodyStart, fn.end)).map { it.groupValues[1] }.toList()
            if (calls.isEmpty()) continue
            if (calls.all { it.contains("notify = false") }) silent += fn.name else writes += fn.name
        }
        // Weiterleitungen (saveAlarm → trySaveAlarm, addWatch → tryAddWatch) zählen mit
        do {
            val added = all.filter { (fn, text) ->
                fn.name !in writes && fn.name !in silent &&
                    writes.any { Regex("""(?<![\w])$it\(""").containsMatchIn(text.substring(fn.bodyStart, fn.end)) }
            }.map { it.first.name }
            writes += added
        } while (added.isNotEmpty())
        return writes to silent
    }

    private fun check(sources: Map<String, String>, writes: Set<String>): Result {
        val problems = mutableListOf<String>()
        val exempted = mutableSetOf<Pair<String, String>>()
        var calls = 0
        for ((file, text) in sources) {
            val receivers = receivers(text)
            val fns = functions(text)
            for (m in CALL.findAll(text)) {
                val receiver = m.groupValues[1]
                val method = m.groupValues[2]
                if (method !in writes) continue
                val inExtension = receiver.isEmpty() && fns.any { it.receiver == "WatchRepository" && m.range.first in it.bodyStart until it.end }
                if (!(receiver in receivers || inExtension)) continue
                if (fns.any { it.start <= m.range.first && m.range.first < it.bodyStart }) continue // Deklaration
                calls++
                val args = text.substring(m.range.last, matching(text, m.range.last, '(', ')') + 1)
                if (args.contains("fromUser = false") && (file to method) in SYSTEM_CALLS) {
                    exempted += file to method
                    continue
                }
                if (!confirmed(sources, file, m.range.first, emptySet())) {
                    val fn = fns.lastOrNull { it.start <= m.range.first }?.name
                    problems += "$file:${text.take(m.range.first).count { it == '\n' } + 1} fun $fn: ${m.value}"
                }
            }
        }
        return Result(calls, problems, exempted)
    }

    /** Namen der `WatchRepository`-Felder der Datei (sonst «watchRepository», wenn nicht anders getypt). */
    private fun receivers(text: String): Set<String> {
        val typed = TYPED.findAll(text).map { it.groupValues[1] to it.groupValues[2].substringAfterLast('.') }.toList()
        val own = typed.filter { it.second == "WatchRepository" }.map { it.first }.toSet()
        val other = typed.filter { it.second != "WatchRepository" }.map { it.first }.toSet()
        return (setOf("watchRepository") - other) + own
    }

    /** Liegt [pos] hinter einer noch offenen Rückfrage — oder fragen alle Aufrufer des umschliessenden Hilfswegs? */
    private fun confirmed(sources: Map<String, String>, file: String, pos: Int, seen: Set<String>): Boolean {
        val text = sources.getValue(file)
        val fn = functions(text).lastOrNull { it.start <= pos } ?: return false
        if (pos >= fn.end) return false
        if (asksBefore(text, fn.bodyStart, pos)) return true
        if (fn.name in seen || seen.size > 4) return false
        val sites = sources.flatMap { (other, t) ->
            val decls = functions(t).map { it.start until it.bodyStart }
            Regex("""(?<![\w])${fn.name}\(""").findAll(t).map { it.range.first }
                .filter { at -> decls.none { at in it } }.map { other to it }.toList()
        }
        return sites.isNotEmpty() && sites.all { (other, at) -> confirmed(sources, other, at, seen + fn.name) }
    }

    private fun asksBefore(text: String, from: Int, pos: Int): Boolean {
        val part = text.substring(from, pos)
        // confirmUserWrite(): sein Block ist beim Aufruf noch offen
        if (Regex("""confirmUserWrite\(\)""").findAll(part).any { neverBelow(text, from + it.range.last + 1, pos, 0) }) return true
        // launchWrite(…) { … }: Aufruf im Block
        return LAUNCH_WRITE.findAll(part).any { m ->
            var at = from + m.groups[1]!!.range.first
            if (text[at] == '(') {
                at = matching(text, at, '(', ')') + 1
                while (text[at].isWhitespace()) at++
            }
            text[at] == '{' && neverBelow(text, at + 1, pos, 1, start = 1)
        }
    }

    /** Klammertiefe von [from] bis [to] fällt nie unter [min] (Start bei [start]). */
    private fun neverBelow(text: String, from: Int, to: Int, min: Int, start: Int = 0): Boolean {
        var depth = start
        for (i in from until to) {
            if (text[i] == '{') depth++ else if (text[i] == '}') depth--
            if (depth < min) return false
        }
        return true
    }

    private fun matching(text: String, open: Int, a: Char, b: Char): Int {
        var depth = 0
        for (i in open until text.length) {
            if (text[i] == a) depth++ else if (text[i] == b) depth--
            if (depth == 0) return i
        }
        return text.length - 1
    }

    private companion object {
        /**
         * Erlaubte Aufrufe ohne Rückfrage (Datei relativ zu `app/`, Schreibweg), nur mit `fromUser = false`:
         * - «1 h später» aus der Alarm-Mitteilung stellt die Kursmarke wieder scharf — Systemweg ohne Bildschirm, die
         *   Sperre fragt dort bewusst nicht (`setAlarmEnabled(…, fromUser = false)`).
         * Folge-Schreibwege (Meldekurs, Prozent-Bezug) sind über `notify = false` schon aus der Liste der Schreibwege.
         */
        val SYSTEM_CALLS = listOf("notification/AlarmActionReceiver.kt" to "setAlarmEnabled")

        val FUN = Regex(
            """(?m)^[ \t]*(?:@\w+(?:\([^)\n]*\))?\s+)*(?:(?:private|internal|public|protected|override|suspend|inline|operator|infix|tailrec)\s+)*""" +
                """fun\s+(?:<[^>\n]*>\s*)?((?:[\w<>?]+\.)?)(\w+)\s*\(""",
        )
        val CALL = Regex("""(?:\b(\w+)\s*\??\.\s*|(?<![\w.]))(\w+)\(""")
        val REFUSED = Regex("""userWriteRefused\(([^)]*)\)""")
        val TYPED = Regex("""\b(\w+)\s*:\s*([\w.]+)""")
        val LAUNCH_WRITE = Regex("""(?<!fun )\blaunchWrite\s*([({])""")
    }
}
