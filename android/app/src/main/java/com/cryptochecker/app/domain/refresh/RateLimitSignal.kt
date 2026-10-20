package com.cryptochecker.app.domain.refresh

import org.json.JSONArray
import org.json.JSONObject

/**
 * «Zu viele Anfragen» auch ohne HTTP 429/418 erkennen — für Kurse und Kerzen gleich: Kraken meldet es mit
 * HTTP 200 («EGeneral:Too many requests», «EAPI:Rate limit exceeded»), Bybit mit HTTP 403 («access too
 * frequent») oder retCode 10006 («Too many visits!»), OKX mit code 50011, BingX mit code 100410
 * («FREQUENCY_LIMIT», Fehlercodes in https://github.com/BingX-API/BingX-Standard-Contract-doc, REST API.md) und
 * bitbank mit HTTP 200 und {"success":0,"data":{"code":10009}} (10009 = zu viele Anfragen laut
 * https://github.com/bitbankinc/bitbank-api-docs, errors.md; bitbank schickt nur den Code, keinen Text).
 * Die HTTP-Schicht reicht so eine Antwort als [HTTP_CODE] weiter; dann greifen Pause je Börse ([ExchangeBackoff]),
 * SingleRateGate, Bericht und «keine ungefilterte Sammelabfrage» wie bei einem echten 429. Wie `RateLimitSignal.swift`.
 */
object RateLimitSignal {

    /** Status, als den ein so erkanntes Rate-Limit weitergereicht wird. */
    const val HTTP_CODE = 429

    /** Antworten mit HTTP 2xx nur bis zu dieser Länge prüfen: Fehlermeldungen sind kurz, Kurslisten nicht. */
    const val MAX_OK_BODY = 4096

    private val TEXTS = listOf(
        "too many requests", "rate limit exceeded", "rate limit reached", "too many visits", "access too frequent",
    )

    /**
     * Fehlercodes im JSON (ohne Leerzeichen verglichen): Bybit retCode 10006, OKX code 50011, BingX code 100410,
     * bitbank data.code 10009.
     */
    private val CODES = listOf(
        "\"retcode\":10006", "\"code\":\"50011\"", "\"code\":100410,", "\"code\":100410}", "\"code\":\"100410\"",
        "\"success\":0,\"data\":{\"code\":10009}",
    )

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
     * Rate-Limit. Bei Fehlercodes zählt der Anfang des Texts. Bei 2xx nur kurze Antworten (≤ [MAX_OK_BODY]) und
     * nur, wo eine Fehlermeldung stehen kann ([okBodyRateLimited]) — sonst hielte ein Kurs, dessen Name oder
     * Beschreibung «rate limit» enthält, die ganze Börse an.
     */
    fun httpCode(status: Int, body: String?): Int? {
        if (ExchangeBackoff.isRateLimitCode(status)) return status
        if (body.isNullOrEmpty()) return null
        if (status in 200..299) {
            if (body.length > MAX_OK_BODY) return null
            return if (okBodyRateLimited(body)) HTTP_CODE else null
        }
        return if (inText(body.take(MAX_OK_BODY))) HTTP_CODE else null
    }

    /** Felder einer JSON-Antwort, in denen Börsen ihre Fehlermeldung schicken. */
    private val ERROR_FIELDS = listOf(
        "error", "errors", "msg", "message", "retMsg", "err_msg", "errmsg", "errorMessage", "error_message",
    )

    /**
     * 2xx-Antwort meldet «zu viele Anfragen»? Lesbares JSON: nur die bekannten Fehlerfelder ([ERROR_FIELDS],
     * Text oder Liste von Texten) und Codes (Bybit retCode 10006, OKX code 50011, BingX code 100410, bitbank
     * data.code 10009 bei success 0); eine JSON-Liste nie (das
     * sind Kurse). Unlesbare Antwort (kein JSON, abgeschnitten, HTML): der ganze Text wie bisher.
     */
    internal fun okBodyRateLimited(body: String): Boolean {
        val trimmed = body.trim()
        when {
            trimmed.startsWith("{") -> {
                val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return inText(body)
                if (codeIs(json.opt("retCode"), 10006) || codeIs(json.opt("code"), 50011)) return true
                if (codeIs(json.opt("code"), 100410) || bitbankTooMany(json)) return true
                return ERROR_FIELDS.any { key -> fieldTexts(json.opt(key)).any { inText(it) } }
            }
            trimmed.startsWith("[") -> {
                if (runCatching { JSONArray(trimmed) }.isSuccess) return false
                return inText(body)
            }
            else -> return inText(body)
        }
    }

    /** bitbank: {"success":0,"data":{"code":10009}} — Fehler ohne Text, nur der Code. */
    private fun bitbankTooMany(json: JSONObject): Boolean =
        codeIs(json.opt("success"), 0) && codeIs(json.optJSONObject("data")?.opt("code"), 10009)

    /** Code als Zahl oder Text gleich [expected]? */
    private fun codeIs(value: Any?, expected: Long): Boolean = when (value) {
        is Number -> value.toDouble() == expected.toDouble()
        is String -> value.trim() == expected.toString()
        else -> false
    }

    /** Texte eines Fehlerfelds: Text oder Liste von Texten. */
    private fun fieldTexts(value: Any?): List<String> = when (value) {
        is String -> listOf(value)
        is JSONArray -> (0 until value.length()).mapNotNull { value.opt(it) as? String }
        else -> emptyList()
    }

    /** Antwort mit HTTP 200, die trotzdem «zu viele Anfragen» meldet (Kerzenquellen). */
    fun inOkBody(body: String): Boolean = httpCode(200, body) != null
}
