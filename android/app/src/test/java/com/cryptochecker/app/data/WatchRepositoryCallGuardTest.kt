package com.cryptochecker.app.data

import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Review TST4-01, F-08: Rückfrage «Merkliste wird nicht gespeichert» je AUFRUF statt je Datei. Die
 * Schreibwege des Nutzers kommen aus dem Repository (Funktionen, deren Rumpf `userWriteRefused(` enthält, plus
 * Weiterleitungen darauf); jeder Aufruf ausserhalb von `data/` (Oberfläche, Mitteilungen, Widgets …) liegt in einer Funktion,
 * die vorher [confirmUserWrite] fragt (Block noch offen) bzw. im Block von `launchWrite { … }`. Ein Hilfsweg ohne eigene
 * Frage (z. B. `deleteSnapshots`) gilt, wenn jeder seiner Aufrufer fragt. Gegenwart siehe [WatchRepositoryLockTest].
 * Review TST5-R2: Die Richtung der Bedingung zählt — nur die im Code benutzten Formen ([consentCovers]); ein Schreibweg im
 * Ablehnungszweig (`if (!confirmUserWrite()) { write() }`, `if (confirmUserWrite()) 0 else write()`) ist ein Befund.
 * Review ARC7-01: auch der Rest der Bedingung zählt (`if (!c && flag) return`, `if (c || flag) write()` sind Befunde).
 * Review ARC7-02: Die Zustimmung gilt nur für die Coroutine der Aktion samt Kindern (`WatchlistNotSavedGate`). Ein
 * Schreibweg in einem Block `x.launch { … }`/`x.async { … }` mit Empfänger (anderer Scope, `GlobalScope`,
 * `CoroutineScope(…)`, auch erneut `viewModelScope`), der NACH der Zustimmung beginnt, ist losgelöst und ein Befund
 * ([escapes]); Kind-Coroutinen (`launch { … }` ohne Empfänger, `withContext`) bleiben erlaubt.
 * Abgrenzung: Das Verhalten der Zustimmung selbst (ohne abgelehnt, mit geschrieben, Kind-Coroutine ja, losgelöster
 * Bereich nein) prüfen [WatchlistWriteConsentTest] und [BulkAddRunnerTest] am Schreibweg. Dieser Wächter sichert
 * zusätzlich, was kein Verhaltenstest abdeckt: dass JEDER Aufruf in Oberfläche, Mitteilungen und Widgets vorher fragt.
 */
class WatchRepositoryCallGuardTest {

    @Test
    fun everyUserWriteCallConfirmsFirst() {
        val root = SourceGuard.android(
            listOf("src/main/java/com/cryptochecker/app", "app/src/main/java/com/cryptochecker/app").map(::File)
                .firstOrNull { it.isDirectory },
            "src/main/java/com/cryptochecker/app",
        )
        // WatchRepository liegt seit Build 142 im Modul :data (gleiches Paket)
        val dataRoot = SourceGuard.android(
            listOf("../data/src/main/java/com/cryptochecker/app", "data/src/main/java/com/cryptochecker/app").map(::File)
                .firstOrNull { it.isDirectory },
            "data/src/main/java/com/cryptochecker/app",
        )
        val repo = File(dataRoot, "data").listFiles { f -> f.name.startsWith("WatchRepository") && f.extension == "kt" }!!
            .map { strip(it.readText()) }
        assertTrue("WatchRepository*.kt nicht gefunden", repo.isNotEmpty())
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
        // Jedes launchWrite fragt selbst und führt seinen Block nur nach der Zustimmung aus
        var launchWrites = 0
        for ((file, text) in sources) {
            launchWrites += functions(text).count { it.name == "launchWrite" }
            assertEquals(file, emptyList<String>(), launchWriteProblems(text))
        }
        assertTrue("launchWrite nicht gefunden — Scan defekt?", launchWrites > 0)
    }

    /**
     * Review TST5-R2: Gegenprobe zur Richtung der Bedingung. Alle im Code benutzten Formen bestehen; Schreibwege im
     * Ablehnungszweig, nach einem Zweig ohne `return`, hinter unbenutztem Ergebnis, nach `||` einer positiven Zustimmung
     * oder hinter `!c &&` sind Befunde. Ebenso ein `launchWrite`, das seinen Block im Ablehnungszweig ausführt.
     * ARC7-01: Rest der Bedingung — `!c && flag` vor `return`, `c || flag` und `c && flag || x` sind Befunde.
     */
    @Test
    fun conditionDirectionIsChecked() {
        val writes = setOf("setFavorite", "reorder", "deleteAlarm", "setAlarmEnabled")
        val sample = """
            class C(private val watchRepository: WatchRepository) {
                fun okReturn(id: Long) = scope.launch {
                    if (!watchRepository.confirmUserWrite()) return@launch
                    watchRepository.setFavorite(id, true)
                }
                fun okBlock(id: Long) = scope.launch {
                    if (!watchRepository.confirmUserWrite()) {
                        state = null
                        return@launch
                    }
                    watchRepository.setFavorite(id, true)
                }
                fun okElse(id: Long) = scope.launch {
                    if (!watchRepository.confirmUserWrite()) return@launch else watchRepository.setFavorite(id, true)
                }
                fun okOr(id: Long) = scope.launch {
                    if (!watchRepository.confirmUserWrite() || !watchRepository.deleteAlarm(id)) return@launch
                    watchRepository.setFavorite(id, false)
                }
                fun okValueElse(ids: List<Long>) = scope.launch {
                    val n = if (!watchRepository.confirmUserWrite()) 0 else lock.withLock {
                        watchRepository.reorder(ids)
                    }
                    onDone(n)
                }
                fun okThen(id: Long) = scope.launch { if (watchRepository.confirmUserWrite()) watchRepository.setFavorite(id, true) else other() }
                fun okAnd(id: Long) = scope.launch { onDone(watchRepository.confirmUserWrite() && watchRepository.setAlarmEnabled(id, true)) }
                fun negatedBlock(id: Long) = scope.launch {
                    if (!watchRepository.confirmUserWrite()) { watchRepository.setFavorite(id, true) }
                }
                fun negatedStatement(id: Long) = scope.launch {
                    if (!watchRepository.confirmUserWrite()) watchRepository.setFavorite(id, true)
                }
                fun positiveElse(ids: List<Long>) = scope.launch {
                    val n = if (watchRepository.confirmUserWrite()) 0 else watchRepository.reorder(ids)
                }
                fun afterPositive(id: Long) = scope.launch {
                    if (watchRepository.confirmUserWrite()) other()
                    watchRepository.setFavorite(id, true)
                }
                fun noReturn(id: Long) = scope.launch {
                    if (!watchRepository.confirmUserWrite()) { state = null }
                    watchRepository.setFavorite(id, true)
                }
                fun ignoredResult(id: Long) = scope.launch {
                    watchRepository.confirmUserWrite()
                    watchRepository.setFavorite(id, true)
                }
                fun positiveOr(id: Long) = scope.launch {
                    if (watchRepository.confirmUserWrite() || watchRepository.deleteAlarm(id)) other()
                }
                fun negatedAnd(id: Long) = scope.launch { onDone(!watchRepository.confirmUserWrite() && watchRepository.setAlarmEnabled(id, true)) }
                fun okAndThen(id: Long) = scope.launch { if (watchRepository.confirmUserWrite() && flag) watchRepository.setFavorite(id, true) }
                fun negatedAndRest(id: Long) = scope.launch {
                    if (!watchRepository.confirmUserWrite() && flag) return@launch
                    watchRepository.setFavorite(id, true)
                }
                fun positiveOrRest(id: Long) = scope.launch { if (watchRepository.confirmUserWrite() || flag) watchRepository.setFavorite(id, true) }
                fun positiveAndOr(id: Long) = scope.launch { if (watchRepository.confirmUserWrite() && flag || other) watchRepository.setFavorite(id, true) }
                fun andOrInCondition(id: Long) = scope.launch { if (watchRepository.confirmUserWrite() && flag || watchRepository.deleteAlarm(id)) other() }
            }
        """.trimIndent()
        val result = check(mapOf("ui/Sample.kt" to strip(sample)), writes)
        assertEquals(
            listOf(
                "negatedBlock", "negatedStatement", "positiveElse", "afterPositive", "noReturn", "ignoredResult", "positiveOr", "negatedAnd",
                "negatedAndRest", "positiveOrRest", "positiveAndOr", "andOrInCondition",
            ),
            result.problems.map { it.substringAfter("fun ").substringBefore(":") },
        )
        assertEquals(21, result.calls)
        val launchWrites = """
            class D {
                private fun launchWrite(cancelled: () -> Unit = {}, block: suspend () -> Unit) =
                    scope.launch { if (!watchRepository.confirmUserWrite()) block() else cancelled() }
            }
            class E {
                private fun launchWrite(block: suspend () -> Unit) = scope.launch { if (watchRepository.confirmUserWrite()) block() }
            }
        """.trimIndent()
        assertEquals(1, launchWriteProblems(strip(launchWrites)).size)
    }

    /**
     * Review ARC7-02: Gegenprobe zum Ausweichen in eine losgelöste Coroutine. Kind-Coroutinen und ein geschlossener Block
     * davor bestehen; ein Schreibweg in `x.launch`/`x.async` mit Empfänger nach der Zustimmung (anderer Scope, erneut
     * `viewModelScope`, `GlobalScope`, `CoroutineScope(…)`, im Block von `launchWrite`, über einen Hilfsweg) ist ein Befund.
     */
    @Test
    fun foreignScopeIsFlagged() {
        val writes = setOf("setFavorite", "reorder", "deleteWatches")
        val sample = """
            class C(private val watchRepository: WatchRepository) {
                private fun launchWrite(block: suspend () -> Unit) = scope.launch { if (watchRepository.confirmUserWrite()) block() }
                fun okChild(id: Long) = viewModelScope.launch {
                    if (!watchRepository.confirmUserWrite()) return@launch
                    launch { watchRepository.setFavorite(id, true) }
                    withContext(Dispatchers.IO) { watchRepository.reorder(listOf(id)) }
                }
                fun okClosedLaunch(id: Long) = viewModelScope.launch {
                    if (!watchRepository.confirmUserWrite()) return@launch
                    appScope.launch { log() }
                    watchRepository.setFavorite(id, true)
                }
                fun foreign(id: Long) = viewModelScope.launch {
                    if (!watchRepository.confirmUserWrite()) return@launch
                    appScope.launch { watchRepository.setFavorite(id, true) }
                }
                fun sameScopeAgain(id: Long) = viewModelScope.launch {
                    if (!watchRepository.confirmUserWrite()) return@launch
                    viewModelScope.launch { watchRepository.setFavorite(id, true) }
                }
                fun global(id: Long) = viewModelScope.launch {
                    if (watchRepository.confirmUserWrite()) GlobalScope.launch(Dispatchers.IO) { watchRepository.setFavorite(id, true) }
                }
                fun newScope(id: Long) = viewModelScope.launch {
                    if (!watchRepository.confirmUserWrite()) return@launch
                    CoroutineScope(Dispatchers.IO).async { watchRepository.reorder(listOf(id)) }
                }
                fun inLaunchWrite(id: Long) { launchWrite { appScope.launch { watchRepository.setFavorite(id, false) } } }
                fun viaHelper(id: Long) = viewModelScope.launch {
                    if (!watchRepository.confirmUserWrite()) return@launch
                    appScope.launch { helper(id) }
                }
                private suspend fun helper(id: Long) { watchRepository.deleteWatches(listOf(id)) }
            }
        """.trimIndent()
        val result = check(mapOf("ui/Sample.kt" to strip(sample)), writes)
        assertEquals(
            listOf("foreign", "sameScopeAgain", "global", "newScope", "inLaunchWrite", "helper"),
            result.problems.map { it.substringAfter("fun ").substringBefore(":") },
        )
        assertEquals(9, result.calls)
        assertEquals(emptyList<String>(), launchWriteProblems(strip(sample)))
        val detached = """
            class D {
                private fun launchWrite(block: suspend () -> Unit) =
                    scope.launch { if (watchRepository.confirmUserWrite()) appScope.launch { block() } }
            }
        """.trimIndent()
        assertEquals(1, launchWriteProblems(strip(detached)).size)
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
                fun cancelledPath(id: Long) { launchWrite(cancelled = { watchRepository.setFavorite(id, false) }) { } }
                // watchRepository.setFavorite(1, true) — Kommentar zählt nicht
                fun notRepo() { scope.launch { other.reorder(emptyList()) } }
            }
        """.trimIndent()
        val result = check(mapOf("ui/Sample.kt" to strip(sample)), writes)
        assertEquals(
            listOf("bad", "afterBlock", "closedGuard", "systemNotListed", "cancelledPath"),
            result.problems.map { it.substringAfter("fun ").substringBefore(":") },
        )
        assertEquals(7, result.calls)
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
        // confirmUserWrite(): nur im Zweig nach der Zustimmung (TST5-R2)
        val consent = CONFIRM.findAll(part).any {
            val end = from + it.range.last + 1
            consentCovers(text, from + it.range.first, end, pos) && !escapes(text, end, pos)
        }
        if (consent) return true
        // launchWrite(…) { … }: Aufruf im Block
        return LAUNCH_WRITE.findAll(part).any { m ->
            var at = from + m.groups[1]!!.range.first
            if (text[at] == '(') {
                at = matching(text, at, '(', ')') + 1
                while (text[at].isWhitespace()) at++
            }
            // TST5-R1: Aufruf in den Klammern (z. B. `cancelled = { … }`) liegt vor dem Block, gilt nicht
            text[at] == '{' && at < pos && neverBelow(text, at + 1, pos, 1, start = 1) && !escapes(text, at + 1, pos)
        }
    }

    /** `launchWrite`-Funktionen, die ihren Block nicht nur nach der Zustimmung ausführen (Befund je Funktion). */
    private fun launchWriteProblems(text: String): List<String> =
        functions(text).filter { it.name == "launchWrite" }.filter { fn ->
            val blocks = BLOCK_CALL.findAll(text.substring(0, fn.end)).filter { it.range.first >= fn.bodyStart }.toList()
            blocks.isEmpty() || blocks.any { b ->
                CONFIRM.findAll(text.substring(fn.bodyStart, b.range.first)).none {
                    val end = fn.bodyStart + it.range.last + 1
                    consentCovers(text, fn.bodyStart + it.range.first, end, b.range.first) && !escapes(text, end, b.range.first)
                }
            }
        }.map { "launchWrite@${it.start}" }

    /**
     * Liegt [pos] im Zweig NACH der Zustimmung des Aufrufs `confirmUserWrite()` ([start] bis [end])? Nur die im Code
     * benutzten Formen (TST5-R2):
     * - `if (!c) return…` bzw. `if (!c) { …; return… }`: danach im selben Block, auch im `else`-Zweig;
     * - `if (!c || write()) return…`: im Rest der Bedingung; `if (!c) 0 else write()`: im `else`-Zweig;
     * - `if (c) write() else …`: im Rest der Bedingung nach `&&` und im then-Zweig;
     * - `f(c && write())`: im Rest des Ausdrucks.
     * ARC7-01: Der Rest der Bedingung muss passen — verneint leer oder `|| …`, positiv leer oder `&& …` ohne `||`.
     * Alles andere zählt nicht (Ablehnungszweig, Zweig ohne `return`, unbenutztes Ergebnis, `||` nach positiver Zustimmung).
     */
    private fun consentCovers(text: String, start: Int, end: Int, pos: Int): Boolean {
        if (pos < end) return false
        val window = maxOf(0, start - 200)
        val head = IF_HEAD.find(text.substring(window, start))
        if (head == null) {
            if (NEGATED.containsMatchIn(text.substring(window, start))) return false
            val rest = skipWs(text, end)
            return text.startsWith("&&", rest) && pos < statementEnd(text, rest, stopAtElse = false) && !hasTopLevelOr(text, rest, pos)
        }
        val negated = head.groups[1] != null
        val condOpen = text.indexOf('(', window + head.range.first)
        val condClose = matching(text, condOpen, '(', ')')
        val restAt = skipWs(text, end)
        // ARC7-01: verneint nur `|| …` (Ablehnung genügt fürs `return`), positiv nur `&& …` ohne `||` auf oberster Ebene
        if (pos < condClose) return if (negated) text.startsWith("||", restAt)
        else text.startsWith("&&", restAt) && !hasTopLevelOr(text, restAt, pos)
        val restOk = when {
            restAt >= condClose -> true
            negated -> text.startsWith("||", restAt)
            else -> text.startsWith("&&", restAt) && !hasTopLevelOr(text, restAt, condClose)
        }
        if (!restOk) return false
        val thenStart = skipWs(text, condClose + 1)
        val thenEnd = if (text.getOrNull(thenStart) == '{') matching(text, thenStart, '{', '}') + 1
        else statementEnd(text, thenStart, stopAtElse = true)
        if (pos < thenEnd) return !negated && pos >= thenStart
        if (!negated) return false
        val exits = text.substring(thenStart, thenEnd).trim().removePrefix("{").removeSuffix("}").trim()
            .lines().last().trim().startsWith("return")
        var stmtEnd = thenEnd
        val elseAt = skipWs(text, thenEnd)
        if (isWord(text, elseAt, "else")) {
            val elseStart = skipWs(text, elseAt + 4)
            stmtEnd = if (text.getOrNull(elseStart) == '{') matching(text, elseStart, '{', '}') + 1
            else statementEnd(text, elseStart, stopAtElse = false)
            if (pos < stmtEnd) return true
        }
        return exits && neverBelow(text, stmtEnd, pos, 0)
    }

    /**
     * ARC7-02: Beginnt zwischen der Zustimmung ([from]) und dem Schreibweg ([pos]) ein Block `x.launch { … }` bzw.
     * `x.async { … }` mit Empfänger, der [pos] noch umschliesst? Dann läuft der Schreibweg losgelöst von der Coroutine der
     * Aktion (ihre Zustimmung gilt dort nicht). Geschlossene Blöcke davor zählen nicht.
     */
    private fun escapes(text: String, from: Int, pos: Int): Boolean =
        from < pos && ESCAPE.findAll(text.substring(from, pos)).any { m ->
            var at = from + m.groups[1]!!.range.first
            if (text[at] == '(') {
                at = matching(text, at, '(', ')') + 1
                while (at < text.length && text[at].isWhitespace()) at++
            }
            at < pos && text[at] == '{' && neverBelow(text, at + 1, pos, 1, start = 1)
        }

    /** Steht zwischen [from] und [to] ein `||` auf oberster Klammerebene? */
    private fun hasTopLevelOr(text: String, from: Int, to: Int): Boolean {
        var depth = 0
        for (i in from until minOf(to, text.length - 1)) {
            when (text[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                '|' -> if (depth == 0 && text[i + 1] == '|') return true
            }
        }
        return false
    }

    private fun skipWs(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private fun isWord(text: String, at: Int, word: String): Boolean =
        text.startsWith(word, at) && (at == 0 || !text[at - 1].isLetterOrDigit() && text[at - 1] != '_') &&
            (at + word.length >= text.length || !text[at + word.length].isLetterOrDigit() && text[at + word.length] != '_')

    /** Ende einer Anweisung ab [from]: Zeilenende bzw. (mit [stopAtElse]) `else` auf oberster Ebene, oder eine Klammer zu. */
    private fun statementEnd(text: String, from: Int, stopAtElse: Boolean): Int {
        var depth = 0
        for (i in from until text.length) {
            when (text[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth == 0) return i else depth--
                else -> if (depth == 0 && (text[i] == '\n' || stopAtElse && isWord(text, i, "else"))) return i
            }
        }
        return text.length
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
        val CONFIRM = Regex("""confirmUserWrite\(\)""")
        val IF_HEAD = Regex("""\bif\s*\(\s*(!\s*)?(?:\w+\s*\??\.\s*)?$""")
        val NEGATED = Regex("""!\s*(?:\w+\s*\??\.\s*)?$""")
        val BLOCK_CALL = Regex("""(?<![\w.])block\(""")
        /** ARC7-02: neue Coroutine mit Empfänger (`appScope.launch(…) {`, `GlobalScope.async {`, `CoroutineScope(…).launch {`). */
        val ESCAPE = Regex("""(?:\b\w+|\))\s*\??\.\s*(?:launch|async)\s*([({])""")
        val LAUNCH_WRITE = Regex("""(?<!fun )\blaunchWrite\s*([({])""")
    }
}
