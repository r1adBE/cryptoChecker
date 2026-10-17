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

    /**
     * Pausiert? Eine Pause, die weiter als die längste mögliche ([GROUP_MAX_MILLIS], auch mit
     * «Retry-After» von Binance) in der Zukunft endet, gilt als abgelaufen: Uhr zurückgestellt oder
     * gespeicherter Wert kaputt — sonst bliebe die Börse womöglich tage- oder jahrelang stumm.
     */
    fun isPaused(state: State?, now: Long): Boolean =
        state != null && state.pausedUntil > now && state.pausedUntil - now <= GROUP_MAX_MILLIS

    /**
     * Neuer Zustand nach einem Durchlauf; null = nichts zu merken (alles gut).
     * @param retryAfterMillis Wartezeit aus dem «Retry-After» der Börse, sofern geschickt.
     * @param banned HTTP 418 («IP gesperrt»): mindestens [BAN_DEFAULT_MILLIS] wie bei Kerzen —
     *   ein kurzer neuer Versuch verlängerte die Sperre.
     * @param shared Börse mit gemeinsamer Pause ([sharedGroup], Binance): «Retry-After» gilt wie bei
     *   Kerzen bis [GROUP_MAX_MILLIS] statt nur bis [MAX_MILLIS].
     */
    fun next(
        state: State?,
        outcome: Outcome,
        now: Long,
        retryAfterMillis: Long? = null,
        banned: Boolean = false,
        shared: Boolean = false,
    ): State? {
        val s = state ?: State()
        return when (outcome) {
            Outcome.SUCCESS -> null
            Outcome.RATE_LIMITED -> {
                val strikes = s.strikes + 1
                val own = delayMillis(strikes).let { if (banned) maxOf(it, BAN_DEFAULT_MILLIS) else it }
                val asked = (retryAfterMillis ?: 0L).coerceIn(0L, if (shared) GROUP_MAX_MILLIS else MAX_MILLIS)
                // Eine laufende Pause (z. B. gemeinsame von Kerzen) nie verkürzen — eine unmögliche ([isPaused]) zählt nicht
                State(strikes, maxOf(now + maxOf(own, asked), plausibleUntil(s.pausedUntil, now)), RefreshFailure.RATE_LIMIT, 0)
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
     * «Retry-After»-Kopfzeile → Sekunden (0 … [MAX_RETRY_AFTER_SECONDS]): entweder eine Zahl oder ein
     * HTTP-Datum («Wed, 21 Oct 2026 07:28:00 GMT»). Unlesbar oder fehlend → null.
     */
    fun parseRetryAfterSeconds(header: String?, now: Long): Long? {
        val value = header?.trim().orEmpty()
        if (value.isEmpty()) return null
        value.toLongOrNull()?.let { return it.coerceIn(0L, MAX_RETRY_AFTER_SECONDS) }
        return runCatching {
            val at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            ((at - now) / 1000L).coerceIn(0L, MAX_RETRY_AFTER_SECONDS)
        }.getOrNull()
    }

    /** HTTP 418 («IP gesperrt», Binance) in einem der Fehlertexte? */
    fun banned(errors: List<String?>): Boolean = errors.any { RefreshReportLogic.httpStatus(it) == 418 }

    /** Anhang an den Fehlertext von HTTP-Fehlern, z. B. «HttpCode: 429 (retry-after 30 s)». */
    fun retryAfterSuffix(seconds: Long?): String = if (seconds == null) "" else " (retry-after $seconds s)"

    /** Längstes «Retry-After» aus gespeicherten Fehlertexten (Millisekunden); null = keines. */
    fun retryAfterMillis(errors: List<String?>): Long? =
        errors.mapNotNull { e -> e?.let { RETRY_AFTER.find(it.lowercase())?.groupValues?.get(1)?.toLongOrNull() } }
            .maxOrNull()?.let { it.coerceAtMost(MAX_RETRY_AFTER_SECONDS) * 1000L }

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

    // ---------------- Gemeinsame Pause aller Binance-Endpunkte ----------------

    /**
     * Binance zählt Anfragen je IP über alle diese Hosts zusammen; nach 429/418 verlängern
     * weitere Anfragen — gleich an welchen Endpunkt — die Sperre. Deshalb pausieren Kerzen,
     * Futures-Kennzahlen, Aktivität und Kurse gemeinsam (Schlüssel [BINANCE_GROUP]).
     * Binance Alpha (www.binance.com) gehört dazu; Binance.US (api.binance.us) ist eine eigene
     * Börse und gehört nicht dazu.
     */
    val BINANCE_HOSTS: Set<String> = setOf(
        "api.binance.com", "data-api.binance.vision", "fapi.binance.com", "dapi.binance.com", "www.binance.com",
    )

    /** Börsen-Schlüssel der Kursabfrage, die dieselben Hosts nutzen. */
    val BINANCE_MARKET_KEYS: Set<String> = setOf("Binance", "BinanceFutures")

    const val BINANCE_GROUP = "binance"

    /**
     * Längstes beachtetes «Retry-After» (1 Tag); grössere Angaben werden gekürzt — sonst liefe
     * `Sekunden × 1000` still über (wie iOS).
     */
    const val MAX_RETRY_AFTER_SECONDS = 86_400L

    /** Längste gemeinsame Pause: ein «Retry-After» von Binance gilt bis zu dieser Dauer. */
    const val GROUP_MAX_MILLIS = 6 * 60 * 60_000L

    /** Mindestpause nach 418 ohne «Retry-After» (IP schon gesperrt, Binance: ab 2 Min.). */
    const val BAN_DEFAULT_MILLIS = 2 * 60_000L

    /** Gruppe, deren Endpunkte sich eine Pause teilen (Hostname oder Börsen-Schlüssel); null = keine. */
    fun sharedGroup(hostOrMarketKey: String): String? =
        if (hostOrMarketKey in BINANCE_HOSTS || hostOrMarketKey in BINANCE_MARKET_KEYS) BINANCE_GROUP else null

    /** «Zu viele Anfragen» (429) oder «IP gesperrt» (418). */
    fun isRateLimitCode(httpCode: Int): Boolean = httpCode == 429 || httpCode == 418

    /**
     * Neue gemeinsame Pause nach 429/418 ausserhalb der Kursabfrage. Ohne «Retry-After» gilt die
     * Stufe aus [delayMillis] (nach 418 mindestens [BAN_DEFAULT_MILLIS]); ein längeres
     * «Retry-After» gilt bis [GROUP_MAX_MILLIS]. Eine laufende Pause wird nie verkürzt, und
     * Antworten, die während der Pause noch eintreffen (parallel gestartete Anfragen), erhöhen
     * die Stufe nicht. Kein Erfolgssignal: Liegt das Ende der letzten Pause länger als
     * [MAX_MILLIS] zurück, beginnt die Zählung neu.
     */
    fun groupPause(state: State?, now: Long, httpCode: Int, retryAfterSeconds: Long?): State {
        // Unmögliche Pause (weiter als [GROUP_MAX_MILLIS] in der Zukunft, [isPaused]): wie keine
        val recent = state?.takeIf { now - it.pausedUntil <= MAX_MILLIS && it.pausedUntil - now <= GROUP_MAX_MILLIS }
        val running = recent != null && recent.pausedUntil > now
        val strikes = when {
            recent == null -> 1
            running -> recent.strikes.coerceAtLeast(1)
            else -> recent.strikes + 1
        }
        val own = delayMillis(strikes).let { if (httpCode == 418) maxOf(it, BAN_DEFAULT_MILLIS) else it }
        val asked = retryAfterSeconds?.let { (it.coerceIn(0L, GROUP_MAX_MILLIS / 1000L)) * 1000L } ?: 0L
        val until = maxOf(now + maxOf(own, asked), recent?.pausedUntil ?: 0L)
        return State(strikes, until, RefreshFailure.RATE_LIMIT, 0)
    }

    /** Ende einer bestehenden Pause, sofern möglich ([isPaused]); sonst 0 (keine). */
    private fun plausibleUntil(until: Long, now: Long): Long = if (until - now > GROUP_MAX_MILLIS) 0L else until

    private const val MAX_SHIFT = 10

    private val RETRY_AFTER = Regex("""retry-after (\d+) s""")
}
