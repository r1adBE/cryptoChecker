package com.cryptochecker.app.domain.diagnostics

import com.cryptochecker.app.domain.alarm.AlarmLogEntry
import com.cryptochecker.app.domain.alarm.AlarmReadiness
import com.cryptochecker.app.domain.refresh.CheckRun
import com.cryptochecker.app.domain.refresh.ExchangeBackoff
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Einstellungen, die für Alarme zählen — bewusst eigene Felder statt einer freien Liste: kein Platz für Beträge. */
data class DiagnosticSettings(
    val updateMode: String,
    val backgroundIntervalMinutes: Int,
    /** «Pünktlich prüfen»; null = gibt es auf dieser Plattform nicht (iOS). */
    val exactChecks: Boolean?,
    val priceNotifications: Boolean,
    val alarmSignal: String,
    val alarmCooldownMinutes: Int,
    val quietHoursEnabled: Boolean,
    val quietHoursStart: Int,
    val quietHoursEnd: Int,
    val activeAlarms: Int,
)

/**
 * Alles für «Diagnose teilen». Bewusst ohne Portfolio, Beträge, Adressen und Notizen: diese Typen kommen hier gar
 * nicht vor. Vom Alarm-Protokoll gehen nur Zeit, Paar, Börse, Bedingung und Ergebnis in den Text — keine Kurse und
 * keine Schwellen.
 */
data class DiagnosticInput(
    val createdAt: Long,
    /** Zeitzone des Geräts (z. B. «Europe/Zurich»); alle Zeiten im Text in dieser Zone. */
    val zone: String,
    val appVersion: String,
    val appBuild: String,
    /** z. B. «Android 15 (API 35)» bzw. «iOS 18.1». */
    val system: String,
    val device: String,
    val locale: String,
    val settings: DiagnosticSettings,
    val readiness: List<AlarmReadiness.Item>,
    val checkRuns: List<CheckRun>,
    val alarmEvents: List<AlarmLogEntry>,
    val exchangePauses: Map<String, ExchangeBackoff.State>,
    /** Fehlerprotokoll des Release-Builds; null = die App führt keines. */
    val errorLog: List<String>? = null,
)

/**
 * «Diagnose teilen» (Build 81): reiner Textbericht, nur auf dem Gerät erstellt und erst nach der Vorschau über das
 * Teilen-Menü weitergegeben — nichts wird automatisch gesendet. Bezeichnungen fest auf Englisch (für den Support in
 * jeder Sprache gleich lesbar), Werte als Codes. Gleiches Format wie `DiagnosticReport.swift`.
 */
object DiagnosticReport {

    const val FILE_NAME = "cryptochecker-diagnostics.txt"

    /** Höchstens so viele Läufe von «Letzte Prüfungen» (wie [com.cryptochecker.app.domain.refresh.CheckRunLog.MAX]). */
    const val MAX_CHECK_RUNS = 20

    const val REDACTED = "[redacted]"

    private const val NONE = "(none)"

    // Alles, was nach einer Adresse oder einem erweiterten Schlüssel aussieht, fliegt raus — auch wenn ein Paarname
    // (z. B. ein DEX-Paar mit Vertragsadresse) eine enthält
    private val ADDRESS_PATTERNS = listOf(
        Regex("""0x[0-9a-fA-F]{16,}"""),
        Regex("""\b(?:bc1|tb1|ltc1|bcrt1)[0-9a-zA-Z]{11,}\b"""),
        Regex("""\b[xyzuvtXYZUVT]pub[1-9A-HJ-NP-Za-km-z]{20,}\b"""),
        Regex("""\b[1-9A-HJ-NP-Za-km-z]{25,}\b"""),
    )

    /** Adressähnliche Zeichenfolgen durch [REDACTED] ersetzen. */
    fun redact(text: String): String = ADDRESS_PATTERNS.fold(text) { acc, regex -> regex.replace(acc, REDACTED) }

    fun build(input: DiagnosticInput): String {
        val zone = runCatching { ZoneId.of(input.zone) }.getOrDefault(ZoneId.of("UTC"))
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(zone)
        fun at(millis: Long): String = time.format(Instant.ofEpochMilli(millis))
        val s = input.settings
        val lines = ArrayList<String>()
        lines += "Crypto Checker diagnostics"
        lines += "Created: ${at(input.createdAt)} (${zone.id})"
        lines += "App: ${clean(input.appVersion)} (${clean(input.appBuild)})"
        lines += "System: ${clean(input.system)}"
        lines += "Device: ${clean(input.device)}"
        lines += "Language: ${clean(input.locale)}"
        lines += "Contains no portfolio data, amounts, addresses or notes. Nothing is sent automatically."

        lines += ""
        lines += "== Settings =="
        lines += "Update mode: ${clean(s.updateMode)}"
        lines += "Background interval: ${s.backgroundIntervalMinutes} min"
        s.exactChecks?.let { lines += "Exact checks: ${onOff(it)}" }
        lines += "Price notifications: ${onOff(s.priceNotifications)}"
        lines += "Alarm signal: ${clean(s.alarmSignal)}"
        lines += "Alarm cooldown: ${s.alarmCooldownMinutes} min"
        lines += "Quiet hours: " +
            if (s.quietHoursEnabled) "on ${clock(s.quietHoursStart)}-${clock(s.quietHoursEnd)}" else "off"
        lines += "Active alarms: ${s.activeAlarms}"

        lines += ""
        lines += "== Alarm delivery =="
        if (input.readiness.isEmpty()) lines += NONE
        input.readiness.forEach { lines += "${it.row.code}: ${it.state.code} (${it.detail.code})" }

        lines += ""
        lines += "== Last checks (newest first) =="
        val runs = input.checkRuns.take(MAX_CHECK_RUNS)
        if (runs.isEmpty()) lines += NONE
        runs.forEach { run ->
            lines += listOf(
                at(run.at), run.trigger.code, run.result.code,
                String.format(Locale.ROOT, "%.1f s", run.durationMillis.coerceAtLeast(0) / 1000.0),
            ).joinToString(" | ")
        }

        lines += ""
        lines += "== Alarm log (newest first) =="
        if (input.alarmEvents.isEmpty()) lines += NONE
        input.alarmEvents.forEach { e ->
            val period = if (e.until > e.at) "${at(e.at)} - ${at(e.until)}" else at(e.at)
            lines += listOf(period, clean(e.pair), clean(e.exchange), e.condition.name, e.outcome.code).joinToString(" | ")
        }

        lines += ""
        lines += "== Exchange pauses =="
        if (input.exchangePauses.isEmpty()) lines += NONE
        input.exchangePauses.toSortedMap().forEach { (key, state) ->
            val paused = if (ExchangeBackoff.isPaused(state, input.createdAt)) "paused until ${at(state.pausedUntil)}" else "not paused"
            lines += listOf(clean(key), paused, state.reason?.name ?: "-", "strikes ${state.strikes}").joinToString(" | ")
        }

        lines += ""
        lines += "== Error log =="
        val errors = input.errorLog
        when {
            errors == null -> lines += "none - the app keeps no error log"
            errors.isEmpty() -> lines += NONE
            else -> errors.forEach { lines += clean(it) }
        }
        return lines.joinToString("\n") + "\n"
    }

    /** Eine Zeile, ohne Steuerzeichen und ohne Adressen. */
    private fun clean(text: String): String =
        redact(text.replace(Regex("""[\r\n\t]+"""), " ").trim()).ifEmpty { "-" }

    private fun onOff(on: Boolean) = if (on) "on" else "off"

    private fun clock(minute: Int): String {
        val m = Math.floorMod(minute, 24 * 60)
        return String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60)
    }
}
