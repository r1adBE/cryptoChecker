package com.cryptochecker.app.util.json

/**
 * Leser wie AOSP `org.json.JSONTokener` (gleich nachsichtig): Kommentare (`//`, `/* */`, `#`), `=` bzw. `=>` statt `:`,
 * `;` statt `,`, leere Array-Plätze als `null`, Zahlen dezimal/hex (`0x`)/oktal (führende 0) als Int oder Long, sonst
 * Double; ein Wort ohne Anführungszeichen, das keine Zahl und kein `true`/`false`/`null` ist, wird Text.
 */
internal class JsonTokener(private val input: String) {
    private var pos = 0

    fun nextValue(): Any? {
        return when (val c = nextClean()) {
            -1 -> throw syntaxError("End of input")
            '{'.code -> readObject()
            '['.code -> readArray()
            '\''.code, '"'.code -> nextString(c.toChar())
            else -> {
                pos--
                readLiteral()
            }
        }
    }

    private fun nextClean(): Int {
        while (pos < input.length) {
            val c = input[pos++]
            when (c) {
                '\t', ' ', '\n', '\r' -> continue
                '/' -> {
                    if (pos == input.length) return c.code
                    when (input[pos]) {
                        '*' -> {
                            pos++
                            val end = input.indexOf("*/", pos)
                            if (end == -1) throw syntaxError("Unterminated comment")
                            pos = end + 2
                        }
                        '/' -> {
                            pos++
                            skipToEndOfLine()
                        }
                        else -> return c.code
                    }
                }
                '#' -> skipToEndOfLine()
                else -> return c.code
            }
        }
        return -1
    }

    private fun skipToEndOfLine() {
        while (pos < input.length) {
            val c = input[pos]
            if (c == '\r' || c == '\n') {
                pos++
                return
            }
            pos++
        }
    }

    private fun nextString(quote: Char): String {
        val builder = StringBuilder()
        var start = pos
        while (pos < input.length) {
            val c = input[pos++]
            if (c == quote) {
                builder.append(input, start, pos - 1)
                return builder.toString()
            }
            if (c == '\\') {
                if (pos == input.length) throw syntaxError("Unterminated escape sequence")
                builder.append(input, start, pos - 1)
                builder.append(readEscape())
                start = pos
            }
        }
        throw syntaxError("Unterminated string")
    }

    private fun readEscape(): Char {
        return when (val escaped = input[pos++]) {
            'u' -> {
                if (pos + 4 > input.length) throw syntaxError("Unterminated escape sequence")
                val hex = input.substring(pos, pos + 4)
                pos += 4
                hex.toIntOrNull(16)?.toChar() ?: throw syntaxError("Invalid escape sequence: $hex")
            }
            't' -> '\t'
            'b' -> '\b'
            'n' -> '\n'
            'r' -> '\r'
            'f' -> '\u000C'
            else -> escaped
        }
    }

    private fun readLiteral(): Any {
        val literal = nextToInternal("{}[]/\\:,=;# \t\u000C")
        if (literal.isEmpty()) throw syntaxError("Expected literal value")
        if (literal.equals("null", ignoreCase = true)) return JsonNull
        if (literal.equals("true", ignoreCase = true)) return true
        if (literal.equals("false", ignoreCase = true)) return false
        if (literal.indexOf('.') == -1) {
            var base = 10
            var number = literal
            if (number.startsWith("0x") || number.startsWith("0X")) {
                number = number.substring(2)
                base = 16
            } else if (number.startsWith("0") && number.length > 1) {
                number = number.substring(1)
                base = 8
            }
            parseLongOrNull(number, base)?.let { l ->
                return if (l <= Int.MAX_VALUE && l >= Int.MIN_VALUE) l.toInt() else l
            }
        }
        javaDouble(literal)?.let { return it }
        return literal
    }

    /** Wie `Long.parseLong(s, base)`: optionales Vorzeichen, nur Ziffern der Basis. */
    private fun parseLongOrNull(s: String, base: Int): Long? {
        if (s.isEmpty()) return null
        return s.toLongOrNull(base)
    }

    /** Wie `Double.valueOf`: auch «NaN», «Infinity», Exponent, führende/folgende Leerzeichen. */
    private fun javaDouble(s: String): Double? = when (s.trim()) {
        "NaN", "+NaN", "-NaN" -> Double.NaN
        "Infinity", "+Infinity" -> Double.POSITIVE_INFINITY
        "-Infinity" -> Double.NEGATIVE_INFINITY
        else -> s.trim().removeSuffix("d").removeSuffix("D").removeSuffix("f").removeSuffix("F").toDoubleOrNull()
    }

    private fun nextToInternal(excluded: String): String {
        val start = pos
        while (pos < input.length) {
            val c = input[pos]
            if (c == '\r' || c == '\n' || excluded.indexOf(c) != -1) return input.substring(start, pos)
            pos++
        }
        return input.substring(start)
    }

    private fun readObject(): JsonObject {
        val result = JsonObject()
        val first = nextClean()
        if (first == '}'.code) return result
        if (first != -1) pos--
        while (true) {
            val name = nextValue()
            if (name !is String) {
                if (name == null || name == JsonNull) throw syntaxError("Names cannot be null")
                throw syntaxError("Names must be strings, but $name is of type ${name::class.simpleName}")
            }
            val separator = nextClean()
            if (separator != ':'.code && separator != '='.code) throw syntaxError("Expected ':' after $name")
            if (pos < input.length && input[pos] == '>') pos++
            result.put(name, nextValue())
            when (nextClean()) {
                '}'.code -> return result
                ';'.code, ','.code -> continue
                else -> throw syntaxError("Unterminated object")
            }
        }
    }

    private fun readArray(): JsonArray {
        val result = JsonArray()
        var hasTrailingSeparator = false
        while (true) {
            when (nextClean()) {
                -1 -> throw syntaxError("Unterminated array")
                ']'.code -> {
                    if (hasTrailingSeparator) result.put(null)
                    return result
                }
                ','.code, ';'.code -> {
                    result.put(null)
                    hasTrailingSeparator = true
                    continue
                }
                else -> pos--
            }
            result.put(nextValue())
            when (nextClean()) {
                ']'.code -> return result
                ','.code, ';'.code -> {
                    hasTrailingSeparator = true
                    continue
                }
                else -> throw syntaxError("Unterminated array")
            }
        }
    }

    private fun syntaxError(message: String) = JsonException("$message at character $pos of $input")
}
