package com.cryptochecker.app.domain.refresh

/**
 * Zeitbudget für Prüfungen mit harter Frist (exakter Wecker: [deadlineAt] = Ende von goAsync).
 * Jede Börse bekommt höchstens [GROUP_MILLIS]; danach wird ausgewertet, was da ist — eine langsame
 * Börse lässt die anderen nicht leer ausgehen. Ohne Frist (WorkManager, Vordergrund) gilt nichts
 * davon: dort begrenzen die HTTP-Zeitgrenzen.
 */
object CheckBudget {
    /** Höchstens so lange je Börse (Sammel- und Einzelabfragen zusammen). */
    const val GROUP_MILLIS = 8_000L

    /** Nach dem Netz bleibt mindestens so viel für Speichern, Alarme, Widgets und Ansagen. */
    const val EVALUATE_RESERVE_MILLIS = 5_000L

    /**
     * Doze (Gerät im Ruhemodus): Nach einem allow-while-idle-Wecker darf die App nur rund 10 s ins
     * Netz. So lange dauern die Abfragen dann höchstens (ab Start des Empfängers).
     */
    const val IDLE_NETWORK_MILLIS = 9_000L

    /**
     * Zeitgrenze des exakten Weckers (goAsync) ab seinem Start: sonst [normalMillis]; im Ruhemodus
     * [IDLE_NETWORK_MILLIS] fürs Netz plus [EVALUATE_RESERVE_MILLIS] (Auswerten braucht kein Netz)
     * plus [marginMillis] — so endet das Netz-Budget je Börse ([groupDeadline], höchstens
     * [GROUP_MILLIS]) im Netz-Fenster. Nie länger als [normalMillis].
     */
    fun exactTimeoutMillis(deviceIdle: Boolean, normalMillis: Long, marginMillis: Long): Long =
        if (!deviceIdle) normalMillis
        else minOf(normalMillis, IDLE_NETWORK_MILLIS + EVALUATE_RESERVE_MILLIS + marginMillis)

    /** Fehlertext eines Paares, dessen Abfrage am Budget scheiterte (wie OkHttp → «Zeitüberschreitung»). */
    const val TIMEOUT_ERROR = "timeout"

    /**
     * Frist für die Kursabfragen einer Börse (absolut, ms); null = ohne Budget.
     * Nie vor [startedAt]: Ist schon alles verbraucht, wird nichts mehr angefragt.
     */
    fun groupDeadline(startedAt: Long, deadlineAt: Long?): Long? {
        if (deadlineAt == null) return null
        return minOf(startedAt + GROUP_MILLIS, deadlineAt - EVALUATE_RESERVE_MILLIS).coerceAtLeast(startedAt)
    }

    /**
     * Wartezeit bis [deadline], höchstens [cap]; ohne Frist [cap]. [reserve] bleibt übrig
     * (z. B. für das Auswerten nach dem Warten). Nie negativ.
     */
    fun waitMillis(deadline: Long?, now: Long, cap: Long, reserve: Long = 0L): Long {
        if (deadline == null) return cap
        return (deadline - reserve - now).coerceIn(0L, cap)
    }

    /**
     * Pause je Börse ([ExchangeBackoff]) nach einem Lauf mit Budget: Paare, die nur am eigenen
     * Budget scheiterten ([cutByBudget]), sagen nichts über die Börse. Kam kein Kurs und gab es
     * ausser diesen keine Fehler → null (Zustand unverändert lassen, weder Pause noch Erfolg).
     */
    fun backoffOutcome(failures: List<RefreshFailure>, updated: Int, cutByBudget: Int): ExchangeBackoff.Outcome? {
        if (cutByBudget > 0 && updated == 0 && failures.isEmpty()) return null
        return ExchangeBackoff.outcome(failures, updated)
    }
}
