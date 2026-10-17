package com.cryptochecker.app.domain.alarm

import kotlin.math.abs

/**
 * Bewegungs-Alarm «x % in y Stunden» (MOVE_PERCENT_WINDOW) mit gleitendem Fenster. Reines Kotlin,
 * als Unit-Test prüfbar (Spiegel: `MoveWindow` in Shared/Services/AlarmLogic.swift, gemeinsame
 * Fälle in testdata/parity/alarms.json).
 *
 * Je Paar ein kleiner Kursverlauf ([PricePoint]; die letzten [DENSE_MILLIS] dicht, ältere Punkte
 * ausgedünnt, [RETENTION_MILLIS] lang). Verglichen wird der aktuelle Kurs mit jedem Punkt der
 * letzten y Stunden und dazu mit dem jüngsten Punkt knapp davor (höchstens [maxAgeMillis] alt) —
 * so deckt auch eine Hintergrund-Aktualisierung im Stundentakt ein 1-Stunden-Fenster ab. Es zählt
 * die grösste Bewegung. Punkte vor `since` (gespeichert in `referenceAt`: Beginn des Alarms bzw.
 * letzte Meldung) zählen nicht, damit dieselbe Bewegung nicht zweimal meldet. Der gespeicherte
 * Bezug (`referencePrice` zur Zeit `referenceAt`) zählt wie ein Punkt des Verlaufs — ohne Verlauf
 * (z. B. direkt nach dem Update) wird also wenigstens gegen ihn geprüft.
 */
object MoveWindow {

    /** Ein Kurs zum Zeitpunkt [time] (Epoch-ms). */
    data class PricePoint(val price: Double, val time: Long)

    private const val HOUR_MILLIS = 3_600_000L

    /** Verlauf so lange aufbewahren: längstes Fenster (24 h) plus Spielraum ([maxAgeMillis]) plus Reserve. */
    const val RETENTION_MILLIS = 31 * HOUR_MILLIS

    /** Jüngere Punkte dicht ([DENSE_SPACING_MILLIS]), ältere ausgedünnt ([SPARSE_SPACING_MILLIS]). */
    const val DENSE_MILLIS = 2 * HOUR_MILLIS
    const val DENSE_SPACING_MILLIS = 2 * 60_000L
    const val SPARSE_SPACING_MILLIS = 15 * 60_000L

    /** Vergleichspunkt vor dem Fenster darf höchstens so alt sein: y Stunden + max(30 Min., y/4) (wie Open Interest). */
    fun maxAgeMillis(hours: Int): Long {
        val window = hours.coerceAtLeast(1) * HOUR_MILLIS
        return window + maxOf(30 * 60_000L, window / 4)
    }

    /**
     * Grösste Veränderung in % von [price] gegenüber den Punkten aus [history] und [reference]
     * innerhalb von [hours] Stunden (plus dem jüngsten Punkt knapp davor, siehe oben); nur Punkte
     * ab [since]. null ohne gültigen Kurs oder ohne Vergleichspunkt.
     */
    fun changePercent(
        history: List<PricePoint>,
        reference: PricePoint?,
        price: Double,
        hours: Int,
        since: Long,
        now: Long,
    ): Double? {
        if (!price.isFinite() || price <= 0.0) return null
        val window = hours.coerceAtLeast(1) * HOUR_MILLIS
        val maxAge = maxAgeMillis(hours)
        val points = (if (reference != null) history + reference else history)
            .filter { it.price.isFinite() && it.price > 0.0 && it.time >= since && it.time <= now }
        val inside = points.filter { now - it.time <= window }
        val bridge = points.filter { now - it.time > window && now - it.time <= maxAge }.maxByOrNull { it.time }
        var best: Double? = null
        for (p in if (bridge != null) inside + bridge else inside) {
            val change = (price - p.price) / p.price * 100.0
            if (best == null || abs(change) > abs(best)) best = change
        }
        return best
    }

    /** Neuen Punkt anhängen (nur wenn der letzte mindestens [DENSE_SPACING_MILLIS] älter ist) und aufräumen ([prune]). */
    fun append(history: List<PricePoint>, point: PricePoint, now: Long): List<PricePoint> {
        val valid = point.price.isFinite() && point.price > 0.0
        val last = history.maxByOrNull { it.time }
        val add = valid && (last == null || point.time - last.time >= DENSE_SPACING_MILLIS)
        return prune(if (add) history + point else history, now)
    }

    /**
     * Verlauf aufräumen: nach Zeit sortiert, ungültige, zu alte (> [RETENTION_MILLIS]) und künftige
     * (> 1 Min.) Punkte weg; dann ausdünnen — von der ältesten an bleibt ein Punkt, wenn er
     * mindestens [DENSE_SPACING_MILLIS] (jünger als [DENSE_MILLIS]) bzw. [SPARSE_SPACING_MILLIS]
     * (älter) nach dem zuletzt behaltenen liegt.
     */
    fun prune(history: List<PricePoint>, now: Long): List<PricePoint> {
        val sorted = history
            .filter { it.price.isFinite() && it.price > 0.0 && now - it.time <= RETENTION_MILLIS && it.time - now <= 60_000L }
            .sortedBy { it.time }
        val kept = ArrayList<PricePoint>(sorted.size)
        for (p in sorted) {
            val previous = kept.lastOrNull()
            if (previous == null) {
                kept += p
                continue
            }
            val spacing = if (now - p.time <= DENSE_MILLIS) DENSE_SPACING_MILLIS else SPARSE_SPACING_MILLIS
            if (p.time - previous.time >= spacing) kept += p
        }
        return kept
    }
}
