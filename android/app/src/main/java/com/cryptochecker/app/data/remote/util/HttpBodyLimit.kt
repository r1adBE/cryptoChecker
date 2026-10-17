package com.cryptochecker.app.data.remote.util

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

    /** Angekündigte Länge (Content-Length, −1 = unbekannt) schon zu gross? */
    fun exceeds(contentLength: Long, maxBytes: Long): Boolean = contentLength > maxBytes

    /**
     * Liest höchstens [maxBytes] aus [input]; null = Antwort ist grösser (dann wird nicht weitergelesen).
     * [contentLength] (−1 = unbekannt) bricht schon vor dem Lesen ab.
     */
    fun read(input: InputStream, contentLength: Long, maxBytes: Long): ByteArray? {
        if (exceeds(contentLength, maxBytes)) return null
        val out = ByteArrayOutputStream(if (contentLength in 1..maxBytes) contentLength.toInt() else 8192)
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

    /** UTF-8 (JSON, RFC 8259), ein vorangestelltes BOM fällt weg. */
    fun decode(bytes: ByteArray): String {
        val bom = bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        return if (bom) String(bytes, 3, bytes.size - 3, Charsets.UTF_8) else String(bytes, Charsets.UTF_8)
    }
}
