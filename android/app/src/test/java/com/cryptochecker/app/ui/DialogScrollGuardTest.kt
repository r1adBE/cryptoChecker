package com.cryptochecker.app.ui

import com.cryptochecker.app.util.SourceGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Wache über scrollbare Dialogtexte: Der `text`-Slot eines `AlertDialog(` mit mehr als einem Satz
 * scrollt (`DialogText { … }` bzw. `verticalScroll`), sonst schneidet Android ihn bei grosser Schrift
 * oder kleinem Bildschirm ab, und die Schaltflächen rutschen weg.
 *
 * Als «lang» gilt ein Slot ohne Scrollen, wenn er
 * - mehrere `Text(` enthält oder eine `Column` (mehrere Bausteine untereinander),
 * - Texte mit `+` oder `\n` zusammensetzt,
 * - eine Text-Ressource (Englisch, `values/`) mit mehr als einem Satz oder Zeilenumbruch nennt,
 * - einen Text zeigt, dessen Inhalt hier nicht prüfbar ist (keine eigene `R.string`/`R.plurals`).
 * Kurze einzeilige Ressourcen-Texte dürfen ohne Scrollen bleiben.
 */
class DialogScrollGuardTest {

    @Test
    fun langeDialogtexteScrollen() {
        val java = SourceGuard.android(
            findUp(listOf("", "app/", "android/app/").map { it + "src/main/java/com/cryptochecker/app" }),
            "android/app/src/main/java"
        )
        val res = SourceGuard.android(
            findUp(listOf("src/main/res/values", "app/src/main/res/values", "android/app/src/main/res/values")),
            "android/app/src/main/res/values"
        )
        val strings = loadStrings(res)
        assertTrue("keine Text-Ressourcen gelesen", strings.size > 100)
        var dialogs = 0
        val problems = java.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }
            .flatMap { file ->
                val source = file.readText(Charsets.UTF_8)
                dialogs += Regex("""(?<![\w])AlertDialog\(""").findAll(stripComments(source)).count()
                violations(source, strings).map { "${file.relativeTo(java).invariantSeparatorsPath}:$it" }
            }
            .toList()
        assertTrue("keine AlertDialog-Aufrufe gefunden (Wächter liest nichts?)", dialogs > 20)
        assertTrue(
            "Dialogtext mit mehr als einem Satz scrollt nicht — text = { DialogText { … } } nehmen:\n" +
                problems.joinToString("\n"),
            problems.isEmpty()
        )
    }

    /** Gegenprobe: Der Wächter erkennt lange Slots und lässt kurze bzw. scrollende durch. */
    @Test
    fun waechterErkenntBeispiele() {
        val strings = mapOf(
            "kurz" to listOf("This alert will be removed."),
            "lang" to listOf("Deletes all data. This cannot be undone."),
            "mehrzahl" to listOf("One entry.", "%d entries were skipped. Check the file."),
        )
        fun dialog(text: String) = "fun f() {\n    AlertDialog(\n        onDismissRequest = {},\n" +
            "        text = $text,\n        confirmButton = { TextButton(onClick = {}) { Text(\"OK\") } }\n    )\n}\n"
        val bad = listOf(
            """{ Column { Text(stringResource(R.string.kurz)); Text(stringResource(R.string.kurz)) } }""",
            """{ Text(stringResource(R.string.kurz) + "\n\n" + stringResource(R.string.kurz)) }""",
            """{ Text("${'$'}a\n${'$'}b") }""",
            """{ Text(stringResource(R.string.lang)) }""",
            """{ Text(pluralStringResource(R.plurals.mehrzahl, n, n)) }""",
            """{ Text(message) }""",
            """{ Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(stringResource(R.string.kurz)) } }""",
        )
        for (slot in bad) {
            assertEquals("nicht erkannt: $slot", 1, violations(dialog(slot), strings).size)
        }
        val good = listOf(
            """{ Text(stringResource(R.string.kurz)) }""",
            """{ DialogText { Text(stringResource(R.string.lang)) } }""",
            """{ DialogText { Text(a); Text(b) } }""",
            """{ Column(Modifier.verticalScroll(rememberScrollState())) { Text(x + "\n" + y) } }""",
            // Kommentar mit «Text(» und Strings mit Klammern zählen nicht
            """{
            // Text(message) — nur Kommentar
            Text(stringResource(R.string.kurz, "(}"))
        }""",
        )
        for (slot in good) {
            assertEquals("fälschlich gemeldet: $slot", emptyList<String>(), violations(dialog(slot), strings))
        }
        // `text =` eines inneren Aufrufs (z. B. in confirmButton) ist nicht der Slot des Dialogs
        val inner = "AlertDialog(\n    onDismissRequest = {},\n" +
            "    confirmButton = { Foo(text = { Text(a); Text(b) }) }\n)"
        assertEquals(emptyList<String>(), violations(inner, strings))
    }

    private companion object {
        val DIALOG = Regex("""(?<![\w])AlertDialog\(""")
        val TEXT_SLOT = Regex("""\btext\s*=\s*\{""")
        val TEXT_CALL = Regex("""(?<![\w])Text\(""")
        val COLUMN = Regex("""(?<![\w])Column\s*[({]""")
        val JOINED = Regex("\"\\s*\\+|\\+\\s*\"|\\+\\s*(?:pluralS|s)tringResource\\(")
        val RESOURCE = Regex("""(?<![\w.])R\.(?:string|plurals)\.(\w+)""")
        val SENTENCE_BREAK = Regex("""[.!?]["”»)]?\s+\S""")
        val SCROLLS = listOf("DialogText", "verticalScroll", "LazyColumn")

        /** Verstösse in [source] als «Zeile: Grund»; [strings]: Ressourcenname → Texte (Plural: alle Formen). */
        fun violations(source: String, strings: Map<String, List<String>>): List<String> {
            val code = stripComments(source)
            return DIALOG.findAll(code).mapNotNull { match ->
                val open = match.range.last
                val close = closing(code, open)
                if (close < 0) return@mapNotNull null
                val slot = textSlot(code, open, close) ?: return@mapNotNull null
                val reason = reason(slot, strings) ?: return@mapNotNull null
                "${code.substring(0, match.range.first).count { it == '\n' } + 1}: $reason"
            }.toList()
        }

        /** Warum [slot] scrollen muss, oder null. */
        fun reason(slot: String, strings: Map<String, List<String>>): String? {
            if (SCROLLS.any { slot.contains(it) }) return null
            val names = RESOURCE.findAll(slot).map { it.groupValues[1] }.toList()
            val texts = TEXT_CALL.findAll(slot).count()
            return when {
                texts > 1 -> "mehrere Text("
                COLUMN.containsMatchIn(slot) -> "Column ohne verticalScroll"
                JOINED.containsMatchIn(slot) || slot.contains("\\n") -> "zusammengesetzter Text (+ bzw. \\n)"
                names.any { name -> strings[name].orEmpty().any(::isLong) } -> "Ressource mit mehr als einem Satz"
                texts == 1 && names.isEmpty() -> "Inhalt nicht prüfbar (keine Text-Ressource)"
                else -> null
            }
        }

        fun isLong(text: String): Boolean = text.contains("\\n") || SENTENCE_BREAK.containsMatchIn(text)

        /** Inhalt des Arguments `text = { … }` direkt in der Klammer [open]..[close], sonst null. */
        fun textSlot(code: String, open: Int, close: Int): String? {
            var depth = 0
            var i = open + 1
            while (i < close) {
                val c = code[i]
                when {
                    c == '"' -> { i = skipString(code, i); continue }
                    c == '\'' -> { i = skipChar(code, i); continue }
                    c == '(' || c == '{' || c == '[' -> depth++
                    c == ')' || c == '}' || c == ']' -> depth--
                    depth == 0 && c == 't' && !code[i - 1].isLetterOrDigit() && code[i - 1] != '_' -> {
                        val m = TEXT_SLOT.toPattern().matcher(code).region(i, close)
                        if (m.lookingAt()) {
                            val brace = m.end() - 1
                            return code.substring(brace + 1, closing(code, brace))
                        }
                    }
                }
                i++
            }
            return null
        }

        /** Index der schliessenden Klammer zu `code[open]`; Strings und Zeichen werden übersprungen. */
        fun closing(code: String, open: Int): Int {
            var depth = 0
            var i = open
            while (i < code.length) {
                val c = code[i]
                when {
                    c == '"' -> { i = skipString(code, i); continue }
                    c == '\'' -> { i = skipChar(code, i); continue }
                    c == '(' || c == '{' || c == '[' -> depth++
                    c == ')' || c == '}' || c == ']' -> if (--depth == 0) return i
                }
                i++
            }
            return -1
        }

        /** Ende (exklusiv) des String-Literals ab `code[start]` (`"` oder `"""`), samt `${…}`. */
        fun skipString(code: String, start: Int): Int {
            val raw = code.startsWith("\"\"\"", start)
            var i = start + if (raw) 3 else 1
            while (i < code.length) {
                when {
                    raw && code.startsWith("\"\"\"", i) -> {
                        var end = i + 3
                        while (end < code.length && code[end] == '"') end++
                        return end
                    }
                    !raw && code[i] == '\\' -> i += 2
                    !raw && code[i] == '"' -> return i + 1
                    code.startsWith("\${", i) -> i = closing(code, i + 1).let { if (it < 0) code.length else it + 1 }
                    else -> i++
                }
            }
            return code.length
        }

        /** Ende (exklusiv) des Zeichen-Literals ab `code[start]`. */
        fun skipChar(code: String, start: Int): Int {
            val from = if (code.getOrNull(start + 1) == '\\') start + 3 else start + 2
            val end = code.indexOf('\'', from)
            return if (end < 0) code.length else end + 1
        }

        /** Kommentare durch Leerzeichen ersetzen (Zeilenumbrüche bleiben, Strings unverändert). */
        fun stripComments(source: String): String {
            val out = StringBuilder(source)
            var i = 0
            while (i < source.length) {
                when {
                    source[i] == '"' -> { i = skipString(source, i); continue }
                    source[i] == '\'' -> { i = skipChar(source, i); continue }
                    source.startsWith("//", i) -> {
                        val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                        for (k in i until end) out.setCharAt(k, ' ')
                        i = end
                        continue
                    }
                    source.startsWith("/*", i) -> {
                        var depth = 0
                        var k = i
                        while (k < source.length) {
                            if (source.startsWith("/*", k)) { depth++; k += 2; continue }
                            if (source.startsWith("*/", k)) { k += 2; if (--depth == 0) break; continue }
                            k++
                        }
                        for (j in i until minOf(k, source.length)) if (source[j] != '\n') out.setCharAt(j, ' ')
                        i = k
                        continue
                    }
                }
                i++
            }
            return out.toString()
        }

        /** `<string>` und `<plurals>` (alle Formen) aus den XML-Dateien in `values/` (Englisch). */
        fun loadStrings(values: File): Map<String, List<String>> {
            val result = mutableMapOf<String, List<String>>()
            val single = Regex("""<string name="(\w+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            val plural = Regex("""<plurals name="(\w+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            val item = Regex("""<item quantity="\w+">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
            values.listFiles { f -> f.extension == "xml" }.orEmpty().forEach { file ->
                val xml = file.readText(Charsets.UTF_8)
                single.findAll(xml).forEach { result[it.groupValues[1]] = listOf(it.groupValues[2]) }
                plural.findAll(xml).forEach { p ->
                    result[p.groupValues[1]] = item.findAll(p.groupValues[2]).map { it.groupValues[1] }.toList()
                }
            }
            return result
        }

        /** Sucht [candidates] vom Arbeitsverzeichnis aus aufwärts. */
        fun findUp(candidates: List<String>): File? {
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
    }
}
