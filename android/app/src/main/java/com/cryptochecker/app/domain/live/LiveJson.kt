package com.cryptochecker.app.domain.live

/**
 * Kleiner JSON-Leser für die Nachrichten der Kurs-Datenströme ([LiveParser]): Objekt → Map,
 * Array → List, Zahl → Double, Text → String, true/false, null. Reines Kotlin, damit die
 * Parser ohne Android (org.json) testbar sind. Ungültiges JSON ergibt null — eine kaputte
 * Nachricht wird still übergangen, der Strom läuft weiter.
 */
internal object LiveJson {

    fun parse(text: String): Any? = try {
        val reader = Reader(text)
        val value = reader.value()
        reader.skipSpace()
        if (reader.pos == text.length) value else null
    } catch (_: IllegalStateException) {
        null
    } catch (_: NumberFormatException) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    private class Reader(val s: String) {
        var pos = 0

        fun skipSpace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            skipSpace()
            check(pos < s.length) { "end" }
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else error("unexpected $c")
            }
        }

        private fun literal(word: String, result: Any?): Any? {
            check(s.startsWith(word, pos)) { "literal" }
            pos += word.length
            return result
        }

        private fun obj(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++
            skipSpace()
            if (s[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipSpace()
                check(s[pos] == '"') { "key" }
                val key = string()
                skipSpace()
                check(s[pos] == ':') { "colon" }
                pos++
                map[key] = value()
                skipSpace()
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> error("object")
                }
            }
        }

        private fun array(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++
            skipSpace()
            if (s[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list += value()
                skipSpace()
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> error("array")
                }
            }
        }

        private fun string(): String {
            pos++
            val out = StringBuilder()
            while (true) {
                val c = s[pos++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        when (val e = s[pos++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                out.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> error("escape $e")
                        }
                    }
                    else -> out.append(c)
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
