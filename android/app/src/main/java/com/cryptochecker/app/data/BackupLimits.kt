package com.cryptochecker.app.data

import java.io.InputStream

/**
 * Grenzen beim Einlesen einer Sicherung (reines Kotlin, `BackupLimitsTest`; Swift: `BackupLimits` in
 * BackupLimits.swift). Eine gewählte Datei wird nie ungebremst in den Speicher gelesen: höchstens
 * [MAX_BYTES], und die JSON-Verschachtelung höchstens [MAX_DEPTH] tief (sonst Stacküberlauf im Parser).
 * Eine echte Sicherung mit Tausenden Transaktionen liegt weit darunter (wenige MB, Tiefe 4).
 */
object BackupLimits {
    const val MAX_BYTES = 16 * 1024 * 1024
    const val MAX_DEPTH = 32

    class TooLargeException : IllegalArgumentException("Sicherung grösser als $MAX_BYTES Bytes oder zu tief verschachtelt")

    /** Liest höchstens [max] Bytes; ist der Strom länger, [TooLargeException]. */
    fun readLimited(stream: InputStream, max: Int = MAX_BYTES): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) throw TooLargeException()
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /** Verschachtelungstiefe von Objekten/Listen ausserhalb von Texten; tiefer als [max] → [TooLargeException]. */
    fun checkDepth(text: String, max: Int = MAX_DEPTH) {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> if (++depth > max) throw TooLargeException()
                '}', ']' -> depth--
            }
        }
    }
}
