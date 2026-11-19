package com.cryptochecker.app.domain.widget

/**
 * Letzte Aktualisierung aus dem Puls- bzw. Portfolio-Widget, für die Kopfzeile wie im Listen-Widget: Zeit der Daten
 * ([dataAt]), Dauer und Ende (Wanduhr, ms; für die 15-s-Sperre des Knopfs). Reine Logik ohne Android (getestet in
 * WidgetHeaderStampTest, Swift-Spiegel `WidgetHeaderStamp.Stamp`).
 */
data class WidgetHeaderStamp(val dataAt: Long = 0L, val durationMillis: Long = 0L, val finishedAt: Long = 0L) {

    /** Dauer nur für genau diesen Stand (Daten aus der App haben keine); 0 = ohne. */
    fun durationFor(time: Long): Long = if (time > 0L && time == dataAt) durationMillis else 0L

    companion object {
        /** Aktualisierung fertig: Stand [dataAt], Dauer seit [startedAt] (nie negativ), Ende [now]. */
        fun finished(dataAt: Long, startedAt: Long, now: Long): WidgetHeaderStamp =
            WidgetHeaderStamp(dataAt, (now - startedAt).coerceAtLeast(0L), now)
    }
}
