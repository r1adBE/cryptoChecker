package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.domain.convert.CurrencyConversion

/**
 * Art eines Portfolio-Alarms («Portfolio-Wert»). Der Name wird so in Datenbank und
 * Sicherung geschrieben (Spiegel: `PortfolioAlarmKind` in Swift).
 */
enum class PortfolioAlarmKind {
    /** Gesamtwert erreicht oder übersteigt den Betrag (in [PortfolioAlarmInput.currency] des Alarms). */
    VALUE_ABOVE,

    /** Gesamtwert erreicht oder unterschreitet den Betrag. */
    VALUE_BELOW,

    /** Veränderung «heute» (gewählte %-Basis, wie im Portfolio) mindestens +x %. */
    CHANGE_UP,

    /** Veränderung «heute» höchstens −x %. */
    CHANGE_DOWN,
    ;

    /** Schwellwert ist ein Betrag (sonst Prozent). */
    val isValue: Boolean get() = this == VALUE_ABOVE || this == VALUE_BELOW

    companion object {
        /** Unbekannt (neuere Sicherung) → null: der Alarm wird übersprungen. */
        fun fromName(name: String?): PortfolioAlarmKind? = entries.firstOrNull { it.name == name }
    }
}

/** Was ein Portfolio-Alarm zur Entscheidung braucht (reine Daten, unabhängig von Room). */
data class PortfolioAlarmInput(
    val kind: PortfolioAlarmKind,
    /** Betrag (VALUE_*) oder Prozent (CHANGE_*), immer positiv. */
    val threshold: Double,
    /** Währung des Betrags bei VALUE_*, z. B. «CHF». */
    val currency: String?,
    val enabled: Boolean,
    /** 0 = scharf, > 0 = gemeldet (wartet auf die Rückkehr hinter die Marke). */
    val referenceAt: Long,
    val lastTriggeredAt: Long,
)

/** Stand des Portfolios nach einer Aktualisierung (aus der Momentaufnahme). */
data class PortfolioReading(
    /** Gesamtwert in [currency]. */
    val total: Double,
    val currency: String,
    /** Gesamtwert in USDT; null = unbekannt. */
    val totalUsdt: Double?,
    /** Veränderung «heute» in Prozent; null = keine Vergleichsbasis. */
    val changePercent: Double?,
    /** Keine offene Position. */
    val empty: Boolean,
)

/** Ergebnis der Prüfung eines Portfolio-Alarms. */
sealed interface PortfolioAlarmDecision {
    data object None : PortfolioAlarmDecision

    /** Gemeldeter Alarm wieder scharf stellen (Wert bzw. Veränderung ist zurück hinter der Marke). */
    data object Rearm : PortfolioAlarmDecision

    /** Melden; [measured] = Gesamtwert (in der Alarmwährung) bzw. Veränderung in Prozent. */
    data class Fire(val measured: Double) : PortfolioAlarmDecision
}

/**
 * Regeln der Portfolio-Alarme (reines Kotlin, getestet in PortfolioAlarmLogicTest; Swift-Spiegel
 * `PortfolioAlarmLogic.swift`). Dieselben Regeln wie die Kursmarken der Paar-Alarme
 * ([AlarmEvaluator.isLevelArmed], [AlarmEvaluator.shouldRearmLevel]):
 *  - Gemeldet wird beim Überschreiten der Marke, solange der Alarm scharf ist (referenceAt 0) —
 *    nicht bei jeder Aktualisierung, solange der Wert jenseits bleibt.
 *  - Wieder scharf erst, wenn der Wert um die Hysterese ([AlarmEvaluator.LEVEL_HYSTERESIS] der
 *    Marke) auf die andere Seite zurückgekehrt ist.
 *  - «Ruhezeit nach dem Auslösen» ([cooldownMinutes]) gilt wie bei den Paar-Alarmen.
 * Die Prozent-Alarme behandeln die Veränderung «heute» wie einen Wert mit der Marke ±x %;
 * «fällt um x %» wird dafür gespiegelt (−Veränderung ≥ x).
 */
object PortfolioAlarmLogic {

    /**
     * Gesamtwert in der Alarmwährung [currency]: der Wert der Momentaufnahme, wenn sie in
     * derselben Währung ist; bei USD bzw. einem USD-Stablecoin sonst der USDT-Wert; sonst null
     * (z. B. Devisenkurs gerade unbekannt — dann diesmal nicht prüfen).
     */
    fun totalIn(currency: String?, reading: PortfolioReading): Double? {
        val wanted = currency?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (wanted == reading.currency.uppercase()) return reading.total.takeIf { it.isFinite() }
        val stables = CurrencyConversion.USD_STABLES
        if (CurrencyConversion.normalize(wanted) in stables) return reading.totalUsdt?.takeIf { it.isFinite() }
        return null
    }

    /** Gemessener Wert für [alarm]: Betrag, +Veränderung (steigt) oder −Veränderung (fällt). */
    fun measure(alarm: PortfolioAlarmInput, reading: PortfolioReading): Double? = when (alarm.kind) {
        PortfolioAlarmKind.VALUE_ABOVE, PortfolioAlarmKind.VALUE_BELOW -> totalIn(alarm.currency, reading)
        PortfolioAlarmKind.CHANGE_UP -> reading.changePercent?.takeIf { it.isFinite() }
        PortfolioAlarmKind.CHANGE_DOWN -> reading.changePercent?.takeIf { it.isFinite() }?.let { -it }
    }

    /** Schwelle «darunter» (VALUE_BELOW) — sonst «darüber» (die Prozent-Alarme sind gespiegelt). */
    private fun below(kind: PortfolioAlarmKind): Boolean = kind == PortfolioAlarmKind.VALUE_BELOW

    fun decide(
        alarm: PortfolioAlarmInput,
        reading: PortfolioReading,
        now: Long,
        cooldownMinutes: Int,
    ): PortfolioAlarmDecision {
        if (!alarm.enabled || reading.empty) return PortfolioAlarmDecision.None
        val threshold = alarm.threshold
        if (!threshold.isFinite() || threshold <= 0.0) return PortfolioAlarmDecision.None
        val value = measure(alarm, reading) ?: return PortfolioAlarmDecision.None
        // Ein leeres oder wertloses Portfolio meldet nie «unter Betrag»
        if (alarm.kind.isValue && !(value > 0.0)) return PortfolioAlarmDecision.None
        val armed = alarm.referenceAt <= 0L
        if (!armed) {
            val back = if (below(alarm.kind)) value > threshold * (1.0 + AlarmEvaluator.LEVEL_HYSTERESIS)
            else value < threshold * (1.0 - AlarmEvaluator.LEVEL_HYSTERESIS)
            return if (back) PortfolioAlarmDecision.Rearm else PortfolioAlarmDecision.None
        }
        if (alarm.lastTriggeredAt > 0 && cooldownMinutes > 0 &&
            now - alarm.lastTriggeredAt in 0 until cooldownMinutes * 60_000L
        ) return PortfolioAlarmDecision.None
        val crossed = if (below(alarm.kind)) value <= threshold else value >= threshold
        return if (crossed) PortfolioAlarmDecision.Fire(value) else PortfolioAlarmDecision.None
    }

    /** Nach dem Melden: ein einmaliger Alarm schaltet sich ab, ein wiederholender bleibt an. */
    fun enabledAfterFire(repeating: Boolean): Boolean = repeating

    /** Gültige Eingabe? Betrag bzw. Prozent über 0 (Prozent höchstens 1000). */
    fun isValidThreshold(kind: PortfolioAlarmKind, threshold: Double?): Boolean {
        if (threshold == null || !threshold.isFinite() || threshold <= 0.0) return false
        return kind.isValue || threshold <= 1000.0
    }
}
