package com.cryptochecker.app.domain.refresh

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Pause je Börse (#19): Antwortet eine Börse mit HTTP 429/418 («zu viele Anfragen») oder
 * scheitert sie zweimal hintereinander nur an Zeitüberschreitungen, wird sie eine Weile
 * übersprungen — 30 s, 1, 2, 4, 8 Min., höchstens 15 Min.; ein «Retry-After» der Börse gilt,
 * wenn es länger ist. Ihre Paare behalten solange den letzten Kurs; der Bericht zeigt
 * «pausiert bis 19:45 (zu viele Anfragen)». Ein Erfolg setzt alles zurück. Keine endlosen
 * Wiederholungen: Während der Pause geht keine einzige Anfrage an diese Börse.
 *
 * Reines Kotlin — wie `ExchangeBackoff.swift`.
 */
object ExchangeBackoff {

    /** Erste Pause. */
    const val BASE_MILLIS = 30_000L

    /** Längste Pause (auch ein längeres «Retry-After» wird darauf gekürzt). */
    const val MAX_MILLIS = 15 * 60_000L

    /** So viele Durchläufe in Folge nur mit Zeitüberschreitungen → Pause. */
    const val TIMEOUT_RUNS = 2

    /** Was ein Durchlauf bei einer Börse erlebt hat. */
    enum class Outcome { SUCCESS, RATE_LIMITED, TIMEOUT, OTHER }

    data class State(
        /** Pausen in Folge ohne Erfolg dazwischen — bestimmt die Dauer der nächsten. */
        val strikes: Int = 0,
        /** Bis dahin wird die Börse übersprungen (0 = keine Pause). */
        val pausedUntil: Long = 0,
        /** [RefreshFailure.RATE_LIMIT] oder [RefreshFailure.TIMEOUT]; null = noch nie pausiert. */
        val reason: RefreshFailure? = null,
        /** Durchläufe in Folge, die nur an Zeitüberschreitungen scheiterten. */
        val timeoutRuns: Int = 0,
    )

    /** Dauer der n-ten Pause in Folge (1 → 30 s, 2 → 1 Min., 3 → 2 Min. … höchstens 15 Min.). */
    fun delayMillis(strike: Int): Long {
        if (strike <= 1) return BASE_MILLIS
        val shift = (strike - 1).coerceAtMost(MAX_SHIFT)
        return (BASE_MILLIS shl shift).coerceAtMost(MAX_MILLIS)
    }

    fun isPaused(state: State?, now: Long): Boolean = state != null && state.pausedUntil > now

    /**
     * Neuer Zustand nach einem Durchlauf; null = nichts zu merken (alles gut).
     * @param retryAfterMillis Wartezeit aus dem «Retry-After» der Börse, sofern geschickt.
     */
    fun next(state: State?, outcome: Outcome, now: Long, retryAfterMillis: Long? = null): State? {
        val s = state ?: State()
        return when (outcome) {
            Outcome.SUCCESS -> null
            Outcome.RATE_LIMITED -> {
                val strikes = s.strikes + 1
                val asked = (retryAfterMillis ?: 0L).coerceIn(0L, MAX_MILLIS)
                State(strikes, now + maxOf(delayMillis(strikes), asked), RefreshFailure.RATE_LIMIT, 0)
            }
            Outcome.TIMEOUT -> {
                val runs = s.timeoutRuns + 1
                if (runs < TIMEOUT_RUNS) {
                    s.copy(timeoutRuns = runs)
                } else {
                    val strikes = s.strikes + 1
                    State(strikes, now + delayMillis(strikes), RefreshFailure.TIMEOUT, 0)
                }
            }
            // Anderer Fehler (Paar unbekannt, kein Netz …): keine Pause, Zeitüberschreitungen nicht mehr «in Folge»
            Outcome.OTHER -> s.copy(timeoutRuns = 0).takeUnless { it.strikes == 0 && it.pausedUntil == 0L }
        }
    }

    /**
     * Ergebnis einer Börse aus den Ursachen ihrer Fehler: Schon ein «zu viele Anfragen»
     * pausiert (weitere Anfragen verschlimmerten es); sonst zählt jeder Kurs als Erfolg;
     * nur Zeitüberschreitungen → [Outcome.TIMEOUT].
     */
    fun outcome(failures: List<RefreshFailure>, updated: Int): Outcome = when {
        RefreshFailure.RATE_LIMIT in failures -> Outcome.RATE_LIMITED
        updated > 0 || failures.isEmpty() -> Outcome.SUCCESS
        failures.all { it == RefreshFailure.TIMEOUT } -> Outcome.TIMEOUT
        else -> Outcome.OTHER
    }

    /**
     * «Retry-After»-Kopfzeile → Sekunden: entweder eine Zahl oder ein HTTP-Datum
     * («Wed, 21 Oct 2026 07:28:00 GMT»). Unlesbar oder fehlend → null.
     */
    fun parseRetryAfterSeconds(header: String?, now: Long): Long? {
        val value = header?.trim().orEmpty()
        if (value.isEmpty()) return null
        value.toLongOrNull()?.let { return it.coerceAtLeast(0L) }
        return runCatching {
            val at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            ((at - now) / 1000L).coerceAtLeast(0L)
        }.getOrNull()
    }

    /** Anhang an den Fehlertext von HTTP-Fehlern, z. B. «HttpCode: 429 (retry-after 30 s)». */
    fun retryAfterSuffix(seconds: Long?): String = if (seconds == null) "" else " (retry-after $seconds s)"

    /** Längstes «Retry-After» aus gespeicherten Fehlertexten (Millisekunden); null = keines. */
    fun retryAfterMillis(errors: List<String?>): Long? =
        errors.mapNotNull { e -> e?.let { RETRY_AFTER.find(it.lowercase())?.groupValues?.get(1)?.toLongOrNull() } }
            .maxOrNull()?.let { it * 1000L }

    /** Zustände je Börse als kurzer Text (SharedPreferences / UserDefaults). */
    fun encode(states: Map<String, State>): String =
        states.entries.sortedBy { it.key }.joinToString(";") { (key, s) ->
            listOf(key, s.strikes, s.pausedUntil, s.reason?.name.orEmpty(), s.timeoutRuns).joinToString("|")
        }

    /** Unlesbare Einträge fallen weg. */
    fun decode(text: String?): Map<String, State> {
        if (text.isNullOrEmpty()) return emptyMap()
        val result = LinkedHashMap<String, State>()
        for (entry in text.split(';')) {
            val parts = entry.split('|')
            if (parts.size != 5 || parts[0].isEmpty()) continue
            val strikes = parts[1].toIntOrNull() ?: continue
            val until = parts[2].toLongOrNull() ?: continue
            val runs = parts[4].toIntOrNull() ?: continue
            val reason = RefreshFailure.entries.firstOrNull { it.name == parts[3] }
            result[parts[0]] = State(strikes, until, reason, runs)
        }
        return result
    }

    private const val MAX_SHIFT = 10

    private val RETRY_AFTER = Regex("""retry-after (\d+) s""")
}
