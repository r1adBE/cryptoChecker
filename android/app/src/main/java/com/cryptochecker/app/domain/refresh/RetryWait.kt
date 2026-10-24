package com.cryptochecker.app.domain.refresh

/**
 * Wartezeit einer vom Widget angestossenen Aktualisierung bis zum nächsten Versuch: Ist der
 * Datenbank-Schlüssel beim Start noch nicht lesbar (Keystore vor dem ersten Entsperren), meldet der
 * Worker «erneut versuchen», und WorkManager startet ihn nach einem linearen Backoff neu. Solange
 * zeigt die Merkliste «Speicher noch gesperrt – neuer Versuch in 27 s» (zählt im Prozess herunter), das
 * Listen-Widget die feste Uhrzeit «Neuer Versuch um 14:32», der Bericht danach die Nummer des Versuchs.
 * Reines Kotlin (JVM-testbar); nur Android — iOS kennt keinen wiederholten Auftrag (der Widget-Knopf läuft dort
 * direkt im Intent).
 */
object RetryWait {

    /** Höchster Backoff von WorkManager (WorkRequest.MAX_BACKOFF_MILLIS, 5 h). */
    const val MAX_BACKOFF_MILLIS = 5 * 60 * 60_000L

    /**
     * Zeitpunkt des nächsten Versuchs aus den Werten der WorkInfo: nur ein wartender Auftrag
     * ([enqueued]), der schon einmal lief ([runAttemptCount] > 0), mit bekanntem Zeitpunkt
     * ([nextScheduleTimeMillis]; WorkManager meldet Long.MAX_VALUE für «keiner»). Sonst null.
     */
    fun retryAt(enqueued: Boolean, runAttemptCount: Int, nextScheduleTimeMillis: Long): Long? =
        nextScheduleTimeMillis.takeIf {
            enqueued && runAttemptCount > 0 && it > 0 && it != Long.MAX_VALUE
        }

    /**
     * Nächster Versuch bei linearem Backoff, berechnet im Worker beim Melden von «erneut versuchen»:
     * WorkManager wartet [backoffMillis] × Zahl der Versuche (nach diesem also [runAttemptCount] + 1),
     * höchstens [MAX_BACKOFF_MILLIS].
     */
    fun linearRetryAt(now: Long, backoffMillis: Long, runAttemptCount: Int): Long =
        now + (backoffMillis * (runAttemptCount.coerceAtLeast(0) + 1)).coerceAtMost(MAX_BACKOFF_MILLIS)

    /** Ganze Sekunden bis [retryAt], aufgerundet («27 s» … «1 s»); 0 = vorbei oder keiner. */
    fun remainingSeconds(retryAt: Long?, now: Long): Int {
        val rest = (retryAt ?: return 0) - now
        return if (rest > 0) ((rest + 999) / 1000).toInt() else 0
    }

    /** Bis zur nächsten Änderung von [remainingSeconds] (genau auf der Sekundengrenze); 0 = vorbei. */
    fun millisUntilNextChange(retryAt: Long?, now: Long): Long {
        val rest = (retryAt ?: return 0) - now
        return if (rest > 0) (rest - 1) % 1000 + 1 else 0
    }

    /**
     * Uhrzeit für «Neuer Versuch um …» im Widget: [retryAt], solange er nach [now] liegt; sonst null (nichts zeigen).
     * Fest statt herunterzählend — ohne Neuzeichnen bleibt sie stehen, nie ein negativer Wert.
     */
    fun widgetRetryTime(retryAt: Long, now: Long): Long? = retryAt.takeIf { it > 0 && it > now }

    /**
     * Gemerkte Uhrzeit des Versuchs abgelaufen (gesetzt und nicht nach [now]) — beim App-Start löschen: endete der
     * Prozess vor dem geplanten Löschen, bliebe sie sonst bis zum nächsten vollen Zeichnen gespeichert.
     */
    fun widgetRetryExpired(retryAt: Long, now: Long): Boolean = retryAt > 0 && retryAt <= now

    /** Nummer des Versuchs (1 = erster) aus WorkManagers runAttemptCount (0 = erster). */
    fun attemptNumber(runAttemptCount: Int): Int = runAttemptCount.coerceAtLeast(0) + 1

    /** Zusatz «2. Versuch – …» im Bericht: nur, wenn der Lauf nicht der erste Versuch war. */
    fun showsAttemptNote(attempt: Int?): Boolean = attempt != null && attempt > 1
}
