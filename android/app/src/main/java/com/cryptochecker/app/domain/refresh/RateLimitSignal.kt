package com.cryptochecker.app.domain.refresh

/**
 * «Zu viele Anfragen» auch ohne HTTP 429/418 erkennen — für Kurse und Kerzen gleich: Kraken meldet es mit
 * HTTP 200 («EGeneral:Too many requests», «EAPI:Rate limit exceeded»), Bybit mit HTTP 403 («access too
 * frequent») oder retCode 10006 («Too many visits!»), OKX mit code 50011. Die HTTP-Schicht reicht so eine
 * Antwort als [HTTP_CODE] weiter; dann greifen Pause je Börse ([ExchangeBackoff]), SingleRateGate,
 * Bericht und «keine ungefilterte Sammelabfrage» wie bei einem echten 429. Wie `RateLimitSignal.swift`.
 */
object RateLimitSignal {

    /** Status, als den ein so erkanntes Rate-Limit weitergereicht wird. */
    const val HTTP_CODE = 429

    /** Antworten mit HTTP 2xx nur bis zu dieser Länge prüfen: Fehlermeldungen sind kurz, Kurslisten nicht. */
    const val MAX_OK_BODY = 4096

    private val TEXTS = listOf(
        "too many requests", "rate limit exceeded", "rate limit reached", "too many visits", "access too frequent",
    )

    /** Fehlercodes im JSON (ohne Leerzeichen verglichen): Bybit retCode 10006, OKX code 50011. */
    private val CODES = listOf("\"retcode\":10006", "\"code\":\"50011\"")

    /** Meldet [text] (Fehlertext oder Antwort) «zu viele Anfragen»? */
    fun inText(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        val lower = text.lowercase()
        if (TEXTS.any { it in lower }) return true
        val compact = lower.filterNot { it.isWhitespace() }
        return CODES.any { it in compact }
    }

    /**
     * Status zum Weiterreichen: 429/418 bleiben, ein Rate-Limit im Text [body] wird [HTTP_CODE]; null = kein
     * Rate-Limit. Bei 2xx nur kurze Antworten (≤ [MAX_OK_BODY]), sonst nur ihr Anfang.
     */
    fun httpCode(status: Int, body: String?): Int? {
        if (ExchangeBackoff.isRateLimitCode(status)) return status
        if (body.isNullOrEmpty()) return null
        if (status in 200..299 && body.length > MAX_OK_BODY) return null
        return if (inText(body.take(MAX_OK_BODY))) HTTP_CODE else null
    }

    /** Antwort mit HTTP 200, die trotzdem «zu viele Anfragen» meldet (Kerzenquellen). */
    fun inOkBody(body: String): Boolean = httpCode(200, body) != null
}
