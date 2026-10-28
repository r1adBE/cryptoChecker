package com.cryptochecker.app.domain.market

/**
 * Eigene Grenzen für Bitcoin-Kennzahlen (reines Kotlin, testbar; Swift-Spiegel `IndicatorAlertKind` /
 * `IndicatorAlertRules` in Shared/Insights/IndicatorAlerts.swift). Die Grenze wählt der Nutzer aus festen
 * Zahlen; die App bewertet nichts und nennt keine Zone — die Meldung sagt nur: Messwert über/unter deiner Grenze.
 *
 * Gespeichert je Art als Ganzzahl ([IndicatorAlertKind.scale]: Faktoren in Hundertsteln, Prozent ganz); 0 = aus.
 * Geprüft zweimal täglich (ZoneCheckWorker / iOS-Hintergrund); gemeldet einmal beim Überschreiten, wieder scharf,
 * sobald der Wert zurück auf der anderen Seite ist.
 */
enum class IndicatorAlertKind(
    val indicator: IndicatorId,
    /** true = melden, wenn der Wert die Grenze erreicht oder übersteigt; false = erreicht oder unterschreitet. */
    val above: Boolean,
    /** Wählbare Grenzen (gespeicherte Ganzzahl), 0 = aus. */
    val choices: List<Int>,
    /** Teiler von gespeicherter Ganzzahl zur Grenze (100 = Faktor in Hundertsteln). */
    val scale: Double,
) {
    MAYER_ABOVE(IndicatorId.MAYER, true, listOf(0, 150, 200, 240, 300), 100.0),
    MAYER_BELOW(IndicatorId.MAYER, false, listOf(0, 100, 80, 60), 100.0),
    MVRV_ABOVE(IndicatorId.MVRV, true, listOf(0, 250, 300, 350), 100.0),
    MVRV_BELOW(IndicatorId.MVRV, false, listOf(0, 100, 80), 100.0),

    /** Abstand zum Allzeithoch höchstens X % (nahe am Hoch oder darüber). */
    ATH_WITHIN(IndicatorId.ATH_DISTANCE, false, listOf(0, 1, 3, 5, 10), 1.0),
    ;

    /** Schlüssel in Einstellungen, Sicherung und Prefs. */
    val key: String get() = name
}

object IndicatorAlertRules {

    /** Grenze aus der gespeicherten Ganzzahl; null = aus oder ungültig. */
    fun threshold(kind: IndicatorAlertKind, stored: Int): Double? = stored.takeIf { it > 0 }?.let { it / kind.scale }

    /** Liegt [value] jenseits der Grenze (Grenze selbst eingeschlossen)? */
    fun crossed(kind: IndicatorAlertKind, value: Double, threshold: Double): Boolean =
        if (kind.above) value >= threshold else value <= threshold

    /** Ergebnis einer Prüfung: melden? und der neue Zustand «jenseits der Grenze» (null = nicht ändern). */
    data class Decision(val notify: Boolean, val crossed: Boolean?)

    /**
     * Einmal melden beim Überschreiten: [wasCrossed] = beim letzten Lauf schon jenseits (gemeldet). Ohne Wert
     * (Daten fehlen) nichts ändern; zurück diesseits → wieder scharf. Schon jenseits und gemeldet: Zustand erneut
     * schreiben, damit die aktuelle Grenze mitgespeichert wird — ältere Stände ohne gemerkte Grenze ([wasCrossed])
     * hielten sonst eine später geänderte Grenze für unverändert, und sie bliebe stumm.
     */
    fun decide(kind: IndicatorAlertKind, value: Double?, threshold: Double?, wasCrossed: Boolean): Decision {
        if (threshold == null) return Decision(false, false)
        val v = value?.takeIf { it.isFinite() } ?: return Decision(false, null)
        val now = crossed(kind, v, threshold)
        return when {
            now && !wasCrossed -> Decision(true, true)
            now -> Decision(false, true)
            else -> Decision(false, false)
        }
    }

    /**
     * Gemerkter Zustand «jenseits der Grenze» für die eingestellte Grenze [current] (gespeicherte Ganzzahl): Wurde er
     * bei einer anderen Grenze [storedThreshold] gemerkt, zählt er nicht — eine neue Grenze bewertet neu, auch wenn der
     * Wert schon jenseits liegt (wie Fear & Greed, [MarketNoticeGate.boundChanged]). [storedThreshold] null = keine
     * Grenze gemerkt: übernehmen.
     */
    fun wasCrossed(storedCrossed: Boolean, storedThreshold: Int?, current: Int): Boolean =
        storedCrossed && !MarketNoticeGate.boundChanged(storedThreshold, current)

    /** Eingeschaltete Grenzen aus den Einstellungen. */
    fun active(stored: Map<IndicatorAlertKind, Int>): Map<IndicatorAlertKind, Double> =
        stored.mapNotNull { (kind, v) -> threshold(kind, v)?.let { kind to it } }.toMap()
}
