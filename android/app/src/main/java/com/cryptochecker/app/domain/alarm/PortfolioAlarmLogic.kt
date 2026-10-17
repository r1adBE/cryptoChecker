package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.domain.convert.CurrencyConversion
import com.cryptochecker.app.domain.portfolio.PortfolioStables

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
    /**
     * Devisenkurse USD → Währung (z. B. «EUR» → 0.92) für Alarme in einer anderen Währung als der Stand —
     * etwa nach einem Wechsel der Umrechnungswährung von CHF auf EUR. Fehlt der Kurs, wird diesmal nicht geprüft.
     */
    val usdTo: Map<String, Double> = emptyMap(),
    /**
     * Offene Coins ohne aktuellen Kurs ([com.cryptochecker.app.domain.portfolio.PortfolioSnapshot.missingPrices]):
     * sie zählen im Gesamtwert als 0 — ein Wert-Alarm wird dann weder gemeldet noch wieder scharf gestellt.
     */
    val missingPrices: Int = 0,
    /**
     * Coins, die in «heute» fehlen (kein Vergleichskurs, [com.cryptochecker.app.domain.portfolio.PortfolioSnapshot.todaySkipped]):
     * die Veränderung gilt nur für einen Teil — ein %-Alarm wird dann weder gemeldet noch wieder scharf gestellt.
     */
    val todaySkipped: Int = 0,
    /**
     * Bewertete Coins mit altem Kurs ([com.cryptochecker.app.domain.portfolio.PortfolioSnapshot.stalePrices]): ein
     * alter Kurs gälte sonst als «jetzt» — dann prüft KEIN Portfolio-Alarm (weder melden noch wieder scharf stellen).
     */
    val stalePrices: Int = 0,
    /**
     * Live-Kurs USDT/USD ([DepegAlarm.liveUsdtUsd]) für Alarme in einer anderen Währung als der Stand: USDT gilt in
     * Fiat (auch USD) seinen Marktkurs wie im Portfolio-Kopf ([PortfolioStables.usdtUsd]); null = keiner (× 1, Näherung).
     */
    val usdtUsdLive: Double? = null,
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
 *    Marke) auf die andere Seite zurückgekehrt ist; bei den Prozent-Alarmen um [changeRearmMargin].
 *  - «Ruhezeit nach dem Auslösen» ([cooldownMinutes]) gilt wie bei den Paar-Alarmen.
 * Die Prozent-Alarme behandeln die Veränderung «heute» wie einen Wert mit der Marke ±x %;
 * «fällt um x %» wird dafür gespiegelt (−Veränderung ≥ x).
 */
object PortfolioAlarmLogic {

    /**
     * Gesamtwert in der Alarmwährung [currency]: der Wert der Momentaufnahme, wenn sie in
     * derselben Währung ist; bei einem USD-Stablecoin der USDT-Wert; bei USD der USDT-Wert mal USDT/USD
     * ([PortfolioReading.usdtUsdLive]); sonst der USDT-Wert mal Devisenkurs ([PortfolioReading.usdTo]) mal USDT/USD
     * ([PortfolioStables.usdtTo]) — wie der Portfolio-Kopf (`PortfolioSnapshotUpdater`, USDT zum Marktkurs). So bleibt
     * ein Alarm in CHF wirksam, nachdem die Umrechnungswährung auf EUR wechselte. Ohne Kurs null (diesmal nicht prüfen).
     */
    fun totalIn(currency: String?, reading: PortfolioReading): Double? {
        val wanted = currency?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (wanted == reading.currency.uppercase()) return reading.total.takeIf { it.isFinite() }
        val usdt = reading.totalUsdt?.takeIf { it.isFinite() } ?: return null
        val usdtUsd = PortfolioStables.usdtUsd(reading.usdtUsdLive)
        if (wanted == "USD") return usdt * usdtUsd.rate
        val stables = CurrencyConversion.USD_STABLES
        if (CurrencyConversion.normalize(wanted) in stables) return usdt
        val rate = reading.usdTo[wanted]?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val factor = PortfolioStables.usdtTo(rate, usdtUsd) ?: return null
        return usdt * factor
    }

    /** Alarmwährungen, für die ein Devisenkurs nötig ist (nicht die des Stands, kein USD-Stablecoin). */
    fun currenciesNeedingRate(currencies: Collection<String?>, readingCurrency: String): Set<String> =
        currencies.mapNotNull { it?.trim()?.uppercase()?.takeIf { c -> c.isNotEmpty() } }
            .filter { it != readingCurrency.uppercase() && CurrencyConversion.normalize(it) !in CurrencyConversion.USD_STABLES }
            .toSet()

    /**
     * Abstand, um den die Veränderung «heute» hinter die Marke zurück muss, bevor ein %-Alarm wieder scharf
     * ist: 20 % der Marke, mindestens 0.5 Prozentpunkte (Marke 5 % → wieder scharf unter 4 %). Die
     * Hysterese der Beträge (0.2 % der Marke) wäre bei Prozenten nur 0.01 Punkte — ein wiederholender
     * Alarm hätte bei jedem Zittern um die Marke erneut gemeldet.
     */
    fun changeRearmMargin(threshold: Double): Double =
        maxOf(threshold * CHANGE_REARM_FRACTION, CHANGE_REARM_MIN_POINTS)

    const val CHANGE_REARM_FRACTION = 0.2
    const val CHANGE_REARM_MIN_POINTS = 0.5

    /** Gemessener Wert für [alarm]: Betrag, +Veränderung (steigt) oder −Veränderung (fällt). */
    fun measure(alarm: PortfolioAlarmInput, reading: PortfolioReading): Double? = when (alarm.kind) {
        PortfolioAlarmKind.VALUE_ABOVE, PortfolioAlarmKind.VALUE_BELOW -> totalIn(alarm.currency, reading)
        PortfolioAlarmKind.CHANGE_UP -> reading.changePercent?.takeIf { it.isFinite() }
        PortfolioAlarmKind.CHANGE_DOWN -> reading.changePercent?.takeIf { it.isFinite() }?.let { -it }
    }

    /** Schwelle «darunter» (VALUE_BELOW) — sonst «darüber» (die Prozent-Alarme sind gespiegelt). */
    private fun below(kind: PortfolioAlarmKind): Boolean = kind == PortfolioAlarmKind.VALUE_BELOW

    /**
     * Die Messung ist unvollständig: Wert-Alarm mit fehlendem Kurs (Coin zählt als 0 — «unter Betrag» löste sonst
     * fälschlich aus), %-Alarm mit fehlendem Vergleichskurs. Dann diesmal gar nicht prüfen (kein Melden, kein
     * Wieder-scharf-Stellen), für «darüber» wie «darunter». Ein alter Kurs ([PortfolioReading.stalePrices])
     * sperrt jede Art.
     */
    fun incomplete(kind: PortfolioAlarmKind, reading: PortfolioReading): Boolean =
        reading.stalePrices > 0 || if (kind.isValue) reading.missingPrices > 0 else reading.todaySkipped > 0

    fun decide(
        alarm: PortfolioAlarmInput,
        reading: PortfolioReading,
        now: Long,
        cooldownMinutes: Int,
    ): PortfolioAlarmDecision {
        if (!alarm.enabled || reading.empty) return PortfolioAlarmDecision.None
        if (incomplete(alarm.kind, reading)) return PortfolioAlarmDecision.None
        val threshold = alarm.threshold
        if (!threshold.isFinite() || threshold <= 0.0) return PortfolioAlarmDecision.None
        val value = measure(alarm, reading) ?: return PortfolioAlarmDecision.None
        // Ein leeres oder wertloses Portfolio meldet nie «unter Betrag»
        if (alarm.kind.isValue && !(value > 0.0)) return PortfolioAlarmDecision.None
        val armed = alarm.referenceAt <= 0L
        if (!armed) {
            // Frühestens [AlarmEvaluator.MIN_LEVEL_REARM_MILLIS] nach der Meldung wieder scharf (wie die Kursmarken):
            // sonst meldete ein Wert, der mit Abklingzeit 0 um mehr als die Hysterese pendelt, bei fast jeder Prüfung.
            // Meldung in der Zukunft (Uhr zurückgestellt): nicht blockieren
            if (alarm.lastTriggeredAt > 0 && now - alarm.lastTriggeredAt in 0 until AlarmEvaluator.MIN_LEVEL_REARM_MILLIS) {
                return PortfolioAlarmDecision.None
            }
            val back = when {
                below(alarm.kind) -> value > threshold * (1.0 + AlarmEvaluator.LEVEL_HYSTERESIS)
                alarm.kind.isValue -> value < threshold * (1.0 - AlarmEvaluator.LEVEL_HYSTERESIS)
                // Prozent-Alarme: deutlich zurück, nicht nur ein Zittern um die Marke
                else -> value < threshold - changeRearmMargin(threshold)
            }
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

    /**
     * Melden nur, wenn das bedingte Speichern eine Zeile geändert hat ([updatedRows] > 0); 0 = der
     * Alarm wurde seit dem Lesen geändert (ausgeschaltet, bearbeitet, schon ausgelöst) — nicht melden.
     */
    fun notifyAfterSave(updatedRows: Int): Boolean = updatedRows > 0

    /** Gültige Eingabe? Betrag bzw. Prozent über 0 (Prozent höchstens 1000). */
    fun isValidThreshold(kind: PortfolioAlarmKind, threshold: Double?): Boolean {
        if (threshold == null || !threshold.isFinite() || threshold <= 0.0) return false
        return kind.isValue || threshold <= 1000.0
    }
}
