package com.cryptochecker.app.data.remote.util

import com.cryptochecker.app.data.BackupLimits
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Höchstgrösse einer HTTP-Antwort: Ein fehlerhafter oder bösartiger Server könnte sonst beliebig
 * viel in den Speicher schieben (Absturz mit OutOfMemory). Einzelabfragen [DEFAULT_MAX_BYTES],
 * Paar- und Sammellisten [BULK_MAX_BYTES]. Wie iOS `MarketHTTP.checkBodySize`.
 */
object HttpBodyLimit {
    const val DEFAULT_MAX_BYTES = 2L * 1024 * 1024
    const val BULK_MAX_BYTES = 16L * 1024 * 1024

    /**
     * Höchste JSON-Verschachtelung einer Antwort. org.json liest rekursiv: 16 MB «[[[[…» liefen sonst in einen
     * StackOverflowError (ein Error, den kein `catch (Exception)` fängt — Absturz). Echte Antworten der Börsen und
     * Ketten liegen bei Tiefe < 10; iOS (JSONSerialization) bricht von sich aus bei 512 ab. Seit Build 98.
     */
    const val MAX_JSON_DEPTH = 128

    /** Antworttext tiefer verschachtelt als [max] ([com.cryptochecker.app.data.BackupLimits.checkDepth])? */
    fun tooDeep(text: String, max: Int = MAX_JSON_DEPTH): Boolean =
        try {
            BackupLimits.checkDepth(text, max)
            false
        } catch (_: BackupLimits.TooLargeException) {
            true
        }

    /** Angekündigte Länge (Content-Length, −1 = unbekannt) schon zu gross? */
    fun exceeds(contentLength: Long, maxBytes: Long): Boolean = contentLength > maxBytes

    /**
     * Liest höchstens [maxBytes] aus [input]; null = Antwort ist grösser (dann wird nicht weitergelesen).
     * [contentLength] (−1 = unbekannt) bricht schon vor dem Lesen ab.
     */
    fun read(input: InputStream, contentLength: Long, maxBytes: Long): ByteArray? {
        if (exceeds(contentLength, maxBytes)) return null
        val out = ByteArrayOutputStream(initialCapacity(contentLength, maxBytes))
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > maxBytes) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Startgrösse des Puffers: die angekündigte Länge, höchstens [INITIAL_BUFFER_MAX] (seit Build 102). Bis Build 101 bis
     * [BULK_MAX_BYTES] auf einmal — ein Server, der 16 MB ankündigt und dann nichts schickt, belegte so je Anfrage 16 MB.
     * Grössere Antworten wachsen beim Lesen nach (ByteArrayOutputStream verdoppelt).
     */
    fun initialCapacity(contentLength: Long, maxBytes: Long): Int =
        if (contentLength in 1..maxBytes) minOf(contentLength, INITIAL_BUFFER_MAX.toLong()).toInt() else 8192

    /** Obergrenze der Startgrösse ([initialCapacity]): 256 KiB. */
    const val INITIAL_BUFFER_MAX = 256 * 1024

    /** UTF-8 (JSON, RFC 8259), ein vorangestelltes BOM fällt weg. */
    fun decode(bytes: ByteArray): String {
        val bom = bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        return if (bom) String(bytes, 3, bytes.size - 3, Charsets.UTF_8) else String(bytes, Charsets.UTF_8)
    }
}
