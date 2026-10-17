package com.cryptochecker.app.domain.refresh

/**
 * Fehlertext → kurzer technischer Code zum Speichern (WatchEntity.lastError, beobachtete Adressen).
 * Rohe Ausnahmetexte und Server-Antworten können Hosts, URLs oder Adressen enthalten — gespeichert
 * wird nur, was die Anzeige und die Einordnung brauchen ([RefreshReportLogic.classify] bleibt gleich):
 * eigene Marker unverändert, sonst «HTTP 429 (retry-after 30 s)», «Too many requests», «Timeout»,
 * «NETWORK_OFFLINE» oder «ERROR:<Ausnahme>». Spiegel: StoredError.swift (Fälle in stored_error.json).
 */
object StoredError {
    /** Eigene, sprachunabhängige Marker (schon ohne Details) — bleiben, wie sie sind. */
    private val KEEP = setOf(
        com.cryptochecker.app.domain.watch.NotTraded.MARKER,
        "Response data is empty", "Parsed ticker has no data", "Unknown error (empty)",
        "Market unavailable", "Börse nicht verfügbar", "Response too large",
        RefreshReportLogic.OFFLINE_MARKER, CheckBudget.TIMEOUT_ERROR,
    )

    /** Fehler der Massenabfrage («Bulk format error: 3 of 5 rows unreadable»): nur Zahlen, bleiben. */
    private val KEEP_PREFIXES = listOf("Bulk format error", "Bulk row unreadable")

    private const val MAX_KEPT = 80

    private val RETRY_AFTER = Regex("""retry-after (\d+) s""")

    /** Klassenname am Anfang eines Ausnahmetexts («java.net.SocketException: …» → SocketException). */
    private val EXCEPTION_NAME = Regex("""^(?:[\w$]+\.)*([A-Z][\w$]*(?:Exception|Error))\b""")

    fun sanitize(raw: String?): String? {
        if (raw == null) return null
        val text = raw.trim()
        if (text.isEmpty()) return raw
        if (text in KEEP) return text
        if (KEEP_PREFIXES.any { text.startsWith(it) }) return text.take(MAX_KEPT)
        RefreshReportLogic.httpStatus(text)?.let { code ->
            val retry = RETRY_AFTER.find(text.lowercase())?.groupValues?.get(1)
            return "HTTP $code" + (retry?.let { " (retry-after $it s)" } ?: "")
        }
        return when (RefreshReportLogic.classify(text)) {
            RefreshFailure.RATE_LIMIT -> "Too many requests"
            RefreshFailure.TIMEOUT -> "Timeout"
            RefreshFailure.OFFLINE -> RefreshReportLogic.OFFLINE_MARKER
            else -> EXCEPTION_NAME.find(text)?.groupValues?.get(1)?.let { "ERROR:$it" } ?: "ERROR"
        }
    }
}
