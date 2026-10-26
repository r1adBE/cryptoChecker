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

    // ---------------- Kurzer Börsentext für den Bericht ----------------

    /** Höchstlänge des Börsentexts im Bericht. */
    const val MAX_DETAIL = 80

    /** Ausnahme-Klasse am Anfang («java.io.IOException: …»): weg, die Meldung danach bleibt. */
    private val EXCEPTION_PREFIX = Regex("""^(?:[A-Za-z0-9_$]+\.)*[A-Z][A-Za-z0-9_$]*(?:Exception|Error)(?::\s*|$)""")
    private val URL = Regex("""[A-Za-z][A-Za-z0-9+.-]*://\S+""")
    private val IPV4 = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?\b""")
    private val IPV6 = Regex("""\b(?:[0-9A-Fa-f]{1,4}:){2,7}[0-9A-Fa-f]{1,4}\b""")
    private val HOST = Regex("""\b(?:[A-Za-z0-9-]+\.)+[A-Za-z]{2,}\b""")
    private val EMPTY_BRACKETS = Regex("""\(\s*\)|\[\s*\]|\{\s*\}|"\s*"|'\s*'|«\s*»""")
    private val WHITESPACE = Regex("""\s+""")
    private const val TRIM_CHARS = " :;,-"

    /**
     * Fehlertext der Börse, gekürzt für «Letzte Aktualisierung» (z. B. «Invalid symbol.»): ohne URLs,
     * Hosts, IP-Adressen, Ausnahme-Klassen und Zeichenfolgen, die nach Schlüssel oder Kennung aussehen
     * (mindestens 5 Ziffern, oder ab 12 Zeichen mit Ziffern), höchstens [MAX_DETAIL] Zeichen. null, wenn
     * der Text nichts über die allgemeine Ursache hinaus sagt (eigene Marker, HTTP-Code, Netz, Zeit,
     * Rate-Limit) oder danach kaum etwas übrig bleibt. Wie StoredError.swift (Fälle in
     * `testdata/parity/stored_error_detail.json`).
     */
    fun exchangeDetail(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text in KEEP || KEEP_PREFIXES.any { text.startsWith(it) }) return null
        if (RefreshReportLogic.httpStatus(text) != null) return null
        when (RefreshReportLogic.classify(text)) {
            RefreshFailure.TIMEOUT, RefreshFailure.OFFLINE, RefreshFailure.RATE_LIMIT,
            RefreshFailure.UNAVAILABLE -> return null
            else -> Unit
        }
        var t = EXCEPTION_PREFIX.replace(text, "")
        t = URL.replace(t, " ")
        t = IPV4.replace(t, " ")
        t = IPV6.replace(t, " ")
        t = HOST.replace(t, " ")
        t = t.split(WHITESPACE).filterNot(::looksLikeKey).joinToString(" ")
        // Was vom Entfernten übrig bleibt: leere Klammern und Anführungszeichen
        t = EMPTY_BRACKETS.replace(t, " ")
        t = WHITESPACE.replace(t, " ").trim { it in TRIM_CHARS }
        if (t.count { it.isLetter() } < 3) return null
        return if (t.length > MAX_DETAIL) t.take(MAX_DETAIL - 1).trimEnd() + "…" else t
    }

    /** Schlüssel, Kennung, Zeitstempel: mindestens 5 Ziffern, oder ab 12 Zeichen mit Ziffer. */
    private fun looksLikeKey(token: String): Boolean {
        val digits = token.count { it in '0'..'9' }
        return digits >= 5 || (token.length >= 12 && digits > 0)
    }

    /**
     * Börsentext für den Bericht einer Börse: der häufigste [exchangeDetail] unter den Fehlern der
     * Ursache [reason] (bei Gleichstand der alphabetisch erste); null ohne verwertbaren Text.
     * [failures]: Ursache je Fehler (gleiche Reihenfolge, z. B. aus [MarketFailure]); ohne Angabe aus dem Text.
     */
    fun reportDetail(
        errors: List<String?>,
        reason: RefreshFailure?,
        failures: List<RefreshFailure> = errors.map(RefreshReportLogic::classify),
    ): String? {
        if (reason == null) return null
        return errors
            .filterIndexed { i, text -> (failures.getOrNull(i) ?: RefreshReportLogic.classify(text)) == reason }
            .mapNotNull(::exchangeDetail)
            .groupingBy { it }.eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()?.key
    }
}

/**
 * Gespeicherter «Fehler» für Paare, die die Börse nicht mehr führt. Das ist
 * kein Fehler, sondern ein Zustand: Die Merkliste zeigt ihn neutral an und
 * zählt ihn weder als veraltet noch als «ohne Kurs».
 */
const val NOT_TRADED_MARKER = com.cryptochecker.app.domain.watch.NotTraded.MARKER
