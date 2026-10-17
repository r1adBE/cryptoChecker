package com.cryptochecker.app.domain.refresh

/** Wer die Prüfung ausgelöst hat; [code] steht in der Datei (gleich auf iOS). */
enum class CheckTrigger(val code: String, val scheduled: Boolean) {
    /** WorkManager (periodisch). */
    WORKER("worker", true),

    /** Exakter Wecker «Pünktlich prüfen» (nur Android). */
    EXACT("exact", true),

    /** iOS: BGAppRefreshTask. */
    BG_TASK("bgtask", true),

    /** Widget (Android: Knopf; iOS: Zeichnen bzw. Knopf — Kurse und Kursalarme, keine Alarme mit Zusatzdaten). */
    WIDGET("widget", false),

    /** Per Knopf in der offenen App. */
    FOREGROUND("foreground", false),
    ;

    companion object {
        fun fromCode(code: String): CheckTrigger? = entries.firstOrNull { it.code == code }
    }
}

/** Ergebnis einer Prüfung; [code] steht in der Datei (gleich auf iOS). */
enum class CheckResult(val code: String) {
    OK("ok"),
    PARTIAL("partial"),
    FAILED("failed"),

    /** Nicht gelaufen: eben erst geprüft, offline oder nichts zu tun. */
    SKIPPED("skipped"),
    ;

    companion object {
        fun fromCode(code: String): CheckResult? = entries.firstOrNull { it.code == code }
    }
}

/** Ein Lauf: Ende, Auslöser, Ergebnis, Dauer. */
data class CheckRun(
    val at: Long,
    val trigger: CheckTrigger,
    val result: CheckResult,
    val durationMillis: Long,
)

/**
 * «Letzte Prüfungen» (Build 46): die letzten [MAX] Läufe der Hintergrund-Prüfung, neueste zuerst, in
 * einer kleinen Textdatei ohne Sicherung (eine Zeile je Lauf: `at;trigger;result;dauer`). Zeigt
 * ehrlich, wann das System wirklich geprüft hat. Reine Logik — wie `CheckRunLog.swift`.
 */
object CheckRunLog {

    const val MAX = 20

    /** Neuen Lauf vorne anfügen, höchstens [max] behalten (Ringpuffer). */
    fun add(runs: List<CheckRun>, run: CheckRun, max: Int = MAX): List<CheckRun> =
        (listOf(run) + runs).take(max.coerceAtLeast(0))

    /** Ergebnis aus geprüften und gescheiterten Paaren; nichts zu prüfen (alles live, keine Paare) = OK. */
    fun result(checked: Int, failed: Int): CheckResult = when {
        checked <= 0 || failed <= 0 -> CheckResult.OK
        failed >= checked -> CheckResult.FAILED
        else -> CheckResult.PARTIAL
    }

    fun encode(runs: List<CheckRun>): String =
        runs.joinToString("\n") { "${it.at};${it.trigger.code};${it.result.code};${it.durationMillis}" }

    /** Unlesbare Zeilen fallen weg; höchstens [MAX] Läufe, Reihenfolge der Datei. */
    fun decode(text: String?): List<CheckRun> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(';')
            if (parts.size != 4) return@mapNotNull null
            val at = parts[0].toLongOrNull() ?: return@mapNotNull null
            val trigger = CheckTrigger.fromCode(parts[1]) ?: return@mapNotNull null
            val result = CheckResult.fromCode(parts[2]) ?: return@mapNotNull null
            val duration = parts[3].toLongOrNull() ?: return@mapNotNull null
            CheckRun(at, trigger, result, duration.coerceAtLeast(0))
        }.take(MAX).toList()
    }
}
