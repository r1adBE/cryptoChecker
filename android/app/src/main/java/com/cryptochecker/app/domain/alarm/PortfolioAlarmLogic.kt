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
     * alter Kurs gälte sonst als «jetzt» — dann prüft KEIN Portfolio-Alarm (weder melden noch wieder scharf stellen),
     * ausser ihr Wert ist unwesentlich ([staleValueUsdt], [PortfolioAlarmLogic.IMMATERIAL_SHARE]).
     */
    val stalePrices: Int = 0,
    /** Wert (USDT, zum letzten Kurs) der Coins mit altem Kurs; null = unbekannt (ältere Aufnahme: dann wesentlich). */
    val staleValueUsdt: Double? = null,
    /**
     * Zählende Adressen, deren Guthaben noch nie geladen wurde
     * ([com.cryptochecker.app.domain.portfolio.AddressBalance.pendingCount]): sie fehlen im Gesamtwert — dann prüft
     * KEIN Portfolio-Alarm (seit Build 94). Seit über 24 Stunden scheiternde zählen nicht ([addressesFailing]).
     */
    val addressesPending: Int = 0,
    /**
     * Zählende Adressen, deren Guthaben nie geladen hat und seit über 24 Stunden scheitert
     * ([com.cryptochecker.app.domain.portfolio.AddressAlarmState.failing]): die Alarme rechnen ohne sie (keine endlose
     * Pause), der Hinweis nennt sie ([PortfolioAlarmPause.AddressesFailing]).
     */
    val addressesFailing: List<String> = emptyList(),
    /**
     * Adressen mit nicht bestätigten Token-Guthaben ([com.cryptochecker.app.domain.portfolio.WatchedAddress.staleTokens]):
     * zählen wie ein alter Kurs mit Wert [staleTokenValueUsdt] ([PortfolioAlarmLogic.staleMaterial]).
     */
    val staleTokenAddresses: List<String> = emptyList(),
    val staleTokenValueUsdt: Double = 0.0,
    /**
     * Coins mit «Kurs ungeprüft» (Binance-Kurs, Token nicht bestätigt; seit Build 101,
     * [com.cryptochecker.app.domain.portfolio.PortfolioSnapshot.unverifiedCoins]): zählen wie ein alter Kurs mit Wert
     * [unverifiedValueUsdt] ([PortfolioAlarmLogic.staleMaterial]); sperren nur sie, eigener Hinweis
     * ([PortfolioAlarmPause.PriceUnverified]).
     */
    val unverifiedCoins: List<String> = emptyList(),
    val unverifiedValueUsdt: Double = 0.0,
    /**
     * Live-Kurs USDT/USD ([DepegAlarm.liveUsdtUsd]) für Alarme in einer anderen Währung als der Stand: USDT gilt in
     * Fiat (auch USD) seinen Marktkurs wie im Portfolio-Kopf ([PortfolioStables.usdtUsd]); null = keiner (× 1, Näherung).
     */
    val usdtUsdLive: Double? = null,
)

/** Grund, weshalb Portfolio-Alarme gerade nicht prüfen ([PortfolioAlarmLogic.pause]) — Hinweis in der Alarm-Übersicht. */
sealed interface PortfolioAlarmPause {
    /** So viele zählende Adressen ohne je geladenes Guthaben. */
    data class AddressesPending(val count: Int) : PortfolioAlarmPause

    /** Kein aktueller Kurs für diese Coins (leer = Namen unbekannt, ältere Aufnahme). */
    data class NoPrice(val coins: List<String>) : PortfolioAlarmPause

    /** Token-Guthaben dieser Adressen nicht bestätigt (wesentlich, wie ein alter Kurs). */
    data class TokensStale(val addresses: List<String>) : PortfolioAlarmPause

    /** Kurs dieser Coins ungeprüft (Token nicht bestätigt, wesentlich wie ein alter Kurs; seit Build 101). */
    data class PriceUnverified(val coins: List<String>) : PortfolioAlarmPause

    /** Keine Pause, nur Hinweis: die Alarme rechnen ohne diese seit über 24 Stunden scheiternden Adressen. */
    data class AddressesFailing(val addresses: List<String>) : PortfolioAlarmPause
}

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
     * derselben Währung ist; bei USDT der USDT-Wert; bei USD und den übrigen USD-Stablecoins (USDC, DAI …) der USDT-Wert
     * mal USDT/USD ([PortfolioReading.usdtUsdLive]) — sie gelten wie USD 1 Dollar, nicht 1 USDT; sonst der USDT-Wert mal
     * Devisenkurs ([PortfolioReading.usdTo]) mal USDT/USD ([PortfolioStables.usdtTo]) — wie der Portfolio-Kopf
     * (`PortfolioSnapshotUpdater`, USDT zum Marktkurs). So bleibt ein Alarm in CHF wirksam, nachdem die
     * Umrechnungswährung auf EUR wechselte. Ohne Kurs null (diesmal nicht prüfen).
     */
    fun totalIn(currency: String?, reading: PortfolioReading): Double? {
        val wanted = currency?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (wanted == reading.currency.uppercase()) return reading.total.takeIf { it.isFinite() }
        val usdt = reading.totalUsdt?.takeIf { it.isFinite() } ?: return null
        val usdtUsd = PortfolioStables.usdtUsdLive(reading.usdtUsdLive)
        if (wanted == "USDT") return usdt
        // USD-Stablecoins ausser USDT wie USD: USDT-Wert × USDT/USD (bei entkoppeltem USDT sonst falsch)
        if (wanted == "USD" || CurrencyConversion.normalize(wanted) in CurrencyConversion.USD_STABLES) return usdt * usdtUsd.rate
        val rate = reading.usdTo[wanted]?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val factor = PortfolioStables.usdtTo(rate, usdtUsd) ?: return null
        return usdt * factor
    }

    /**
     * Rechnet [totalIn] für einen Betrags-Alarm ([input]) mit USDT = 1, weil USDT/USD nicht gemessen ist
     * ([PortfolioReading.usdtUsdLive] fehlt)? Nur in einer anderen Währung als der Stand und nicht in USDT selbst —
     * dann steht «USDT = 1 angenommen» im Alarmtext. Wie iOS.
     */
    fun usdtAssumed(input: PortfolioAlarmInput, reading: PortfolioReading): Boolean {
        if (!input.kind.isValue) return false
        val wanted = input.currency?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return false
        if (wanted == reading.currency.uppercase() || wanted == "USDT") return false
        return PortfolioStables.usdtUsdLive(reading.usdtUsdLive).approximate
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

    /**
     * Gemessener Wert für [alarm]: Betrag, +Veränderung (steigt) oder −Veränderung (fällt). Prozente auf 1e-6 gerundet
     * ([AlarmPercent.rounded]) wie bei den Kursalarmen — sonst verfehlt «+3 %» genau 3 % um einen Gleitkomma-Rest.
     */
    fun measure(alarm: PortfolioAlarmInput, reading: PortfolioReading): Double? = when (alarm.kind) {
        PortfolioAlarmKind.VALUE_ABOVE, PortfolioAlarmKind.VALUE_BELOW -> totalIn(alarm.currency, reading)
        PortfolioAlarmKind.CHANGE_UP -> reading.changePercent?.takeIf { it.isFinite() }?.let { AlarmPercent.rounded(it) }
        PortfolioAlarmKind.CHANGE_DOWN -> reading.changePercent?.takeIf { it.isFinite() }?.let { AlarmPercent.rounded(-it) }
    }

    /** Schwelle «darunter» (VALUE_BELOW) — sonst «darüber» (die Prozent-Alarme sind gespiegelt). */
    private fun below(kind: PortfolioAlarmKind): Boolean = kind == PortfolioAlarmKind.VALUE_BELOW

    /**
     * Die Messung ist unvollständig: Wert-Alarm mit fehlendem Kurs (Coin zählt als 0 — «unter Betrag» löste sonst
     * fälschlich aus), %-Alarm mit fehlendem Vergleichskurs. Dann diesmal gar nicht prüfen (kein Melden, kein
     * Wieder-scharf-Stellen), für «darüber» wie «darunter». Ein wesentlicher alter Kurs ([staleMaterial]) und
     * nie geladene Adressen ([PortfolioReading.addressesPending]) sperren jede Art.
     */
    fun incomplete(kind: PortfolioAlarmKind, reading: PortfolioReading): Boolean =
        reading.addressesPending > 0 || staleMaterial(reading) ||
            if (kind.isValue) reading.missingPrices > 0 else reading.todaySkipped > 0

    /**
     * Alte Kurse wesentlich? Nein, wenn ihr Wert (zum letzten Kurs) unter [IMMATERIAL_SHARE] des bekannten Gesamtwerts
     * liegt — sonst schwiege ein einzelner kleiner Coin ohne frischen Kurs ALLE Portfolio-Alarme für immer. Ohne
     * bekannten Wert ([PortfolioReading.staleValueUsdt] null) oder Gesamtwert: wesentlich. Coins ganz ohne Kurs
     * ([PortfolioReading.missingPrices]) haben keinen schätzbaren Wert und sperren Wert-Alarme weiter.
     */
    fun staleMaterial(reading: PortfolioReading): Boolean {
        val prices = reading.stalePrices > 0
        // Nicht bestätigte Token-Guthaben (seit Build 95) und ungeprüfte Kurse (seit Build 101) zählen wie Coins mit
        // altem Kurs, Wert zusammen
        val tokens = reading.staleTokenAddresses.isNotEmpty()
        val unverified = reading.unverifiedCoins.isNotEmpty()
        if (!prices && !tokens && !unverified) return false
        var stale = 0.0
        if (prices) stale += reading.staleValueUsdt?.takeIf { it.isFinite() && it >= 0.0 } ?: return true
        if (tokens) stale += reading.staleTokenValueUsdt.takeIf { it.isFinite() && it >= 0.0 } ?: return true
        if (unverified) stale += reading.unverifiedValueUsdt.takeIf { it.isFinite() && it >= 0.0 } ?: return true
        val total = reading.totalUsdt?.takeIf { it.isFinite() && it > 0.0 } ?: return true
        return stale >= total * IMMATERIAL_SHARE
    }

    /**
     * Spielraum der Wesentlichkeit: Coins mit altem Kurs unter 1 % des Gesamtwerts. Selbst wenn ihr Kurs seither um
     * die Hälfte fiel, verschiebt das den Gesamtwert um höchstens 0.5 % — ein Wert-Alarm meldet dann schlimmstenfalls
     * so viel zu früh oder zu spät, ein %-Alarm «heute» um höchstens so viele Prozentpunkte.
     */
    const val IMMATERIAL_SHARE = 0.01

    /**
     * Warum die eingeschalteten Portfolio-Alarme ([kinds]) gerade nicht prüfen (Hinweis in der Alarm-Übersicht); null =
     * mindestens nicht wegen Adressen oder Kursen. Adressen zuerst (betrifft jede Art); sonst Coins ohne aktuellen
     * Kurs: die wesentlichen alten ([staleCoins]) und — bei Wert-Alarmen — die ganz ohne Kurs ([missingCoins]); ohne
     * solche Coins die mit ungeprüftem Kurs ([PortfolioAlarmPause.PriceUnverified], seit Build 101), sonst die Adressen
     * nicht bestätigter Token-Guthaben ([PortfolioAlarmPause.TokensStale]). Sperren alte bzw. fehlende Kurse mit, bleibt
     * es beim Satz «kein aktueller Kurs» mit nur diesen Coins (ungeprüfte zeigen ihren Hinweis in der Zeile). Ohne Pause
     * der Hinweis auf seit über 24 Stunden scheiternde Adressen ([PortfolioAlarmPause.AddressesFailing]).
     */
    fun pause(
        kinds: Collection<PortfolioAlarmKind>,
        reading: PortfolioReading,
        missingCoins: List<String>,
        staleCoins: List<String>,
    ): PortfolioAlarmPause? {
        if (kinds.isEmpty() || reading.empty) return null
        if (reading.addressesPending > 0) return PortfolioAlarmPause.AddressesPending(reading.addressesPending)
        val staleBlocks = staleMaterial(reading)
        val missingBlocks = kinds.any { it.isValue } && reading.missingPrices > 0
        if (!staleBlocks && !missingBlocks) {
            return reading.addressesFailing.takeIf { it.isNotEmpty() }?.let { PortfolioAlarmPause.AddressesFailing(it) }
        }
        if (staleBlocks && reading.stalePrices <= 0 && !missingBlocks) {
            if (reading.unverifiedCoins.isNotEmpty()) return PortfolioAlarmPause.PriceUnverified(reading.unverifiedCoins)
            return PortfolioAlarmPause.TokensStale(reading.staleTokenAddresses)
        }
        val coins = LinkedHashSet<String>()
        if (staleBlocks) coins += staleCoins
        if (missingBlocks) coins += missingCoins
        return PortfolioAlarmPause.NoPrice(coins.toList())
    }

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

    /**
     * Gültige Eingabe? Betrag bzw. Prozent über 0 (steigend höchstens 1000 %). Seit Build 105 (Review FUN3-01) «fällt um»
     * nur unter [MAX_FALL_PERCENT]: Die Veränderung «heute» fällt nie unter −100 %, so ein Alarm löste nie aus.
     * Gemeinsame Fälle `portfolio_alarm.json` («threshold»).
     */
    fun isValidThreshold(kind: PortfolioAlarmKind, threshold: Double?): Boolean {
        if (threshold == null || !threshold.isFinite() || threshold <= 0.0) return false
        return when (kind) {
            PortfolioAlarmKind.CHANGE_DOWN -> threshold < MAX_FALL_PERCENT
            PortfolioAlarmKind.CHANGE_UP -> threshold <= 1000.0
            else -> true
        }
    }

    /** Seit Build 105: Obergrenze (ausschliesslich) von «fällt um x %» — wie die Paar-Alarme (`AlarmJson.exceedsFall`). */
    const val MAX_FALL_PERCENT = 100.0

    /**
     * Seit Build 105 (Review FUN3-01): «fällt um ≥ 100 %» — löst nie aus. Aus einer Sicherung übernommen, aber
     * ausgeschaltet, einmalig bereinigt ([com.cryptochecker.app.data.portfolio.PortfolioFallAlarmCleanup]) und in der
     * Übersicht als «Schwelle ungültig» gekennzeichnet; nicht einschaltbar. Seit Build 106 (FUN4-R2): kein Editor, darum
     * Hinweis «löschen und neu anlegen» (`portfolio_alarm_threshold_invalid_recreate`). Wie iOS.
     */
    fun exceedsFall(kind: PortfolioAlarmKind, threshold: Double): Boolean =
        kind == PortfolioAlarmKind.CHANGE_DOWN && threshold.isFinite() && threshold >= MAX_FALL_PERCENT
}
