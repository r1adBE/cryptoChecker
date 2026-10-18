package com.cryptochecker.app.data.remote.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Wache über `runCatching { … }` in Coroutine-Code: Das schluckt auch die CancellationException
 * eines abgebrochenen Laufs (Bildschirm zu, Job beendet) und rechnet weiter. In `suspend fun` und in
 * Lambdas von launch/async/withContext/LaunchedEffect & Co. gehört [runCatchingCancellable] hin.
 * Erlaubt bleibt ein `runCatching`, dessen Kette den Abbruch selbst weiterwirft (CancellationException
 * bzw. ensureActive direkt nach dem Block). Ausserhalb von Coroutines ist `runCatching` in Ordnung.
 *
 * Grobe Quelltext-Analyse (wie [com.cryptochecker.app.ui.theme.TypographyUsageTest]): Klammerebenen
 * mit Herkunft (Funktion, Klasse, Coroutine-Lambda, sonstige Lambda); sonstige Lambdas erben den
 * Kontext der umgebenden Ebene.
 */
class RunCatchingUsageTest {

    @Test
    fun noPlainRunCatchingInCoroutineCode() {
        val root = androidSources()
        assertNotNull("android/app/src/main/java nicht gefunden (Arbeitsverzeichnis?)", root)
        val hits = root!!.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> violations(file.readText(Charsets.UTF_8)).map { "${file.relativeTo(root).path}:$it" } }
            .toList()
        assertTrue(
            "runCatching { } in Coroutine-Code (runCatchingCancellable nehmen): ${hits.joinToString()}",
            hits.isEmpty()
        )
    }

    @Test
    fun detectorFindsSuspendContexts() {
        val code = """
            class A {
                suspend fun a() {
                    if (x) { runCatching { load() } }
                }
                fun b() {
                    scope.launch { list.forEach { runCatching { load() } } }
                }
                suspend fun c(): Int? =
                    runCatching { load() }.getOrNull()
                fun d() = scope.launch(Dispatchers.IO) { runCatching { load() } }
            }
        """.trimIndent()
        assertEquals(listOf(3, 6, 9, 10), violations(code))
    }

    @Test
    fun detectorAllowsPlainAndExplicitRethrow() {
        val code = """
            class A {
                private val x = runCatching { parse() }.getOrNull()
                fun a() { runCatching { open() } }
                suspend fun b() {
                    val o = object : Callback { override fun done() { runCatching { close() } } }
                    runCatching { load() }
                        .onFailure { if (it is CancellationException) throw it }
                    runCatchingCancellable { load() }
                    // runCatching { im Kommentar }
                    val s = "runCatching { im Text }"
                    val c = '{'
                }
                @Composable fun C() { Button(onClick = { runCatching { open() } }) }
            }
        """.trimIndent()
        assertEquals(emptyList<Int>(), violations(code))
    }

    private enum class Frame { SUSPEND, PLAIN, INHERIT }

    /** Zeilennummern (ab 1) mit `runCatching {` in Coroutine-Kontext ohne eigenes Weiterwerfen. */
    private fun violations(source: String): List<Int> {
        val s = stripCommentsAndStrings(source)
        val frames = ArrayList<Frame>()
        val result = mutableListOf<Int>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '{') {
                frames.add(frameFor(s, i))
            } else if (c == '}') {
                if (frames.isNotEmpty()) frames.removeAt(frames.lastIndex)
            } else if (c == 'r' && s.startsWith(CALL, i) && (i == 0 || !isWordOrDot(s[i - 1]))) {
                val brace = skipSpaces(s, i + CALL.length)
                if (brace < s.length && s[brace] == '{') {
                    val ctx = frames.lastOrNull { it != Frame.INHERIT } ?: Frame.PLAIN
                    val inCoroutine = ctx == Frame.SUSPEND || isSuspendExpressionBody(s, i)
                    if (inCoroutine && !rethrowsAfter(s, brace)) {
                        result += source.substring(0, i).count { it == '\n' } + 1
                    }
                }
            }
            i++
        }
        return result
    }

    /** Herkunft der Klammer an [pos]: Funktions-/Klassenrumpf, Coroutine-Lambda oder sonstige Lambda. */
    private fun frameFor(s: String, pos: Int): Frame {
        val head = s.substring(maxOf(0, pos - 400), pos)
        val fn = Regex("""\bfun\b""").findAll(head).lastOrNull()
        if (fn != null && head.substring(fn.range.first).none { it == '{' || it == '}' || it == ';' }) {
            val decl = head.substring(fn.range.first)
            if (!hasExpressionBody(decl)) {
                return if (isSuspendModifier(head, fn.range.first)) Frame.SUSPEND else Frame.PLAIN
            }
        }
        val t = head.trimEnd()
        val type = Regex("""\b(class|object|interface|init)\b[^{}()=]*$""").find(t)
        if (type != null) return Frame.PLAIN
        val name = if (t.endsWith(")")) {
            val open = matchingOpen(t, t.length - 1)
            val before = t.substring(0, maxOf(open, 0)).trimEnd()
            if (Regex("""\b(class|object)\s+\w+\s*(<[^<>]*>)?$""").containsMatchIn(before)) return Frame.PLAIN
            Regex("""(\w+)\s*(<[^<>]*>)?$""").find(before)?.groupValues?.get(1).orEmpty()
        } else {
            Regex("""(\w+)\s*(<[^<>]*>)?$""").find(t)?.groupValues?.get(1).orEmpty()
        }
        return when (name) {
            in COROUTINE_BUILDERS -> Frame.SUSPEND
            in NON_SUSPEND_BLOCKS -> Frame.PLAIN
            else -> Frame.INHERIT
        }
    }

    /** `suspend fun x(): T =` direkt vor [pos] (Ausdrucksrumpf ohne Klammer). */
    private fun isSuspendExpressionBody(s: String, pos: Int): Boolean {
        val head = s.substring(maxOf(0, pos - 400), pos)
        val fn = Regex("""\bfun\b""").findAll(head).lastOrNull() ?: return false
        val decl = head.substring(fn.range.first)
        if (decl.any { it == '{' || it == '}' || it == ';' }) return false
        return decl.trimEnd().endsWith("=") && hasExpressionBody(decl) && isSuspendModifier(head, fn.range.first)
    }

    private fun isSuspendModifier(head: String, funStart: Int): Boolean =
        head.substring(0, funStart).substringAfterLast('\n').contains(Regex("""\bsuspend\b"""))

    /** Steht nach der Parameterliste ein `=` (Ausdrucksrumpf statt `{`-Rumpf)? */
    private fun hasExpressionBody(decl: String): Boolean {
        val open = decl.indexOf('(')
        if (open < 0) return false
        var depth = 0
        var close = -1
        for (k in open until decl.length) {
            if (decl[k] == '(') depth++
            if (decl[k] == ')') {
                depth--
                if (depth == 0) { close = k; break }
            }
        }
        if (close < 0) return false
        return Regex("""(?<![=!<>])=(?![=>])""").containsMatchIn(decl.substring(close + 1))
    }

    /** Wirft die Kette direkt nach dem Block den Abbruch selbst weiter? */
    private fun rethrowsAfter(s: String, brace: Int): Boolean {
        var depth = 0
        var k = brace
        while (k < s.length) {
            if (s[k] == '{') depth++
            if (s[k] == '}') {
                depth--
                if (depth == 0) break
            }
            k++
        }
        // Kommentare sind schon Leerzeichen: Leerraum zusammenfassen, dann nur den Anfang der Kette prüfen
        val tail = s.substring(minOf(k + 1, s.length), minOf(k + 1 + 4 * RETHROW_WINDOW, s.length))
            .replace(Regex("""\s+"""), " ")
            .take(RETHROW_WINDOW)
        return tail.contains("CancellationException") || tail.contains("ensureActive")
    }

    private fun matchingOpen(t: String, close: Int): Int {
        var depth = 0
        for (k in close downTo 0) {
            if (t[k] == ')') depth++
            if (t[k] == '(') {
                depth--
                if (depth == 0) return k
            }
        }
        return -1
    }

    private fun skipSpaces(s: String, from: Int): Int {
        var k = from
        while (k < s.length && s[k].isWhitespace()) k++
        return k
    }

    private fun isWordOrDot(c: Char) = c.isLetterOrDigit() || c == '_' || c == '.'

    /** Kommentare, Strings und Zeichenliterale durch Leerzeichen ersetzen (Zeilenumbrüche bleiben). */
    private fun stripCommentsAndStrings(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        fun blank(to: Int) {
            for (k in i until to) out.append(if (src[k] == '\n') '\n' else ' ')
            i = to
        }
        while (i < src.length) {
            when {
                src.startsWith("//", i) -> blank(src.indexOf('\n', i).let { if (it < 0) src.length else it })
                src.startsWith("/*", i) -> blank(src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 })
                src.startsWith("\"\"\"", i) ->
                    blank(src.indexOf("\"\"\"", i + 3).let { if (it < 0) src.length else it + 3 })
                src[i] == '"' -> {
                    var k = i + 1
                    while (k < src.length && src[k] != '"' && src[k] != '\n') {
                        if (src[k] == '\\') k++
                        k++
                    }
                    blank(minOf(k + 1, src.length))
                }
                src[i] == '\'' && i + 2 < src.length && (src[i + 1] == '\\' || src[i + 2] == '\'') -> {
                    val end = src.indexOf('\'', if (src[i + 1] == '\\') i + 3 else i + 2)
                    blank(if (end < 0) src.length else end + 1)
                }
                else -> { out.append(src[i]); i++ }
            }
        }
        return out.toString()
    }

    private fun androidSources(): File? =
        findUp(listOf("", "app/", "android/app/").map { it + "src/main/java/com/cryptochecker/app" })

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
        const val CALL = "runCatching"
        const val RETHROW_WINDOW = 240

        /** Lambdas, die in einer Coroutine laufen (suspend-Lambda). */
        val COROUTINE_BUILDERS = setOf(
            "launch", "async", "withContext", "coroutineScope", "supervisorScope", "withTimeout",
            "withTimeoutOrNull", "runBlocking", "LaunchedEffect", "produceState", "flow", "channelFlow",
            "callbackFlow", "transformLatest", "collect", "collectLatest", "withPermit", "withTransaction",
        )

        /** Lambdas ohne Coroutine-Kontext bzw. ohne erlaubte Unterbrechung. */
        val NON_SUSPEND_BLOCKS = setOf("synchronized", "sequence", "iterator", "lazy", "get", "set")
    }
}
