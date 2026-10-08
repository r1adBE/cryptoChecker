package com.cryptochecker.app.parity

import java.io.File

/**
 * Kleiner JSON-Leser für die gemeinsamen Testfälle (JSON-Dateien in `testdata/parity/`). Unit-Tests
 * laufen gegen die Android-Stubs, dort wirft `org.json` nur «not mocked» — deshalb ein
 * eigener Leser: Objekt → Map (Reihenfolge bleibt), Array → List, Zahl → Double,
 * true/false, null, Text → String.
 */
internal object ParityJson {

    /** Ordner `testdata/parity`: Gradle startet Unit-Tests im Modulordner (`app/`), andere Läufer im Projektordner. */
    fun dir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "testdata/parity")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        throw IllegalStateException("testdata/parity not found above ${System.getProperty("user.dir")}")
    }

    @Suppress("UNCHECKED_CAST")
    fun load(name: String): Map<String, Any?> = parse(File(dir(), name).readText(Charsets.UTF_8)) as Map<String, Any?>

    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.value()
        reader.skipSpace()
        check(reader.pos == text.length) { "trailing characters at ${reader.pos}" }
        return value
    }

    private class Reader(val s: String) {
        var pos = 0

        fun skipSpace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            skipSpace()
            check(pos < s.length) { "unexpected end" }
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else error("unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, result: Any?): Any? {
            check(s.startsWith(word, pos)) { "expected $word at $pos" }
            pos += word.length
            return result
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            pos++
            skipSpace()
            if (s[pos] == '}') { pos++; return out }
            while (true) {
                skipSpace()
                val key = string()
                skipSpace()
                check(s[pos] == ':') { "expected : at $pos" }
                pos++
                out[key] = value()
                skipSpace()
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return out }
                    else -> error("expected , or } at $pos")
                }
            }
        }

        private fun array(): List<Any?> {
            val out = ArrayList<Any?>()
            pos++
            skipSpace()
            if (s[pos] == ']') { pos++; return out }
            while (true) {
                out.add(value())
                skipSpace()
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return out }
                    else -> error("expected , or ] at $pos")
                }
            }
        }

        private fun string(): String {
            check(s[pos] == '"') { "expected string at $pos" }
            pos++
            val sb = StringBuilder()
            while (true) {
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> { sb.append(s.substring(pos, pos + 4).toInt(16).toChar()); pos += 4 }
                            else -> error("bad escape \\$e at $pos")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): Double {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
            return s.substring(start, pos).toDouble()
        }
    }
}

/** Zahl aus den Testfällen: null bleibt null, «NaN»/«Infinity»/«-Infinity» als Text. */
internal fun Any?.num(): Double? = when (this) {
    null -> null
    is Double -> this
    is Number -> toDouble()
    "NaN" -> Double.NaN
    "Infinity" -> Double.POSITIVE_INFINITY
    "-Infinity" -> Double.NEGATIVE_INFINITY
    else -> error("not a number: $this")
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.obj(): Map<String, Any?> = this as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
internal fun Any?.list(): List<Any?> = this as List<Any?>
