package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.domain.portfolio.PortfolioStables
import kotlin.math.abs

/**
 * USDT-Depeg-Alarm: meldet einmal je Abweichung, wenn USDT um mindestens die Schwelle
 * von 1 USD abweicht. Dieselbe Messung ist der Live-Kurs USDT/USD der Bewertung ([liveUsdtUsd]): USDT gilt in Fiat seinen
 * Marktkurs ([com.cryptochecker.app.domain.portfolio.PortfolioStables.usdtUsd]); ohne frische Messung 1 als Näherung.
 *
 * Quellen (bestehende Kurs-Abrufe, kein neuer Anbieter): Kraken USDT/USD (direkt, zuerst), Coinbase USDT-USD (direkt,
 * zweite Quelle) und erst als dritte Quelle bzw. Ersatz Binance USDC/USDT (USDT = 1 / Kurs, USDC als 1 USD angenommen).
 * Regeln siehe [measure]: Im Normalbereich zählt der Wert näher bei 1 (eine Börse allein löst keinen Fehlalarm aus);
 * ein Kurs im Einbruch-Bereich (z. B. 0.30) zählt nur, wenn mindestens zwei Quellen innerhalb von 5 % übereinstimmen.
 * Ein Wert allein aus USDC/USDT löst nie einen Alarm aus — er unterscheidet nicht, ob USDT oder USDC abweicht.
 *
 * Hysterese: Nach einer Meldung erst wieder scharf, wenn die Abweichung auf die halbe Schwelle
 * zurückgeht. Kann die Meldung gerade nicht ankommen (Nachtruhe, keine Erlaubnis), bleibt der Alarm
 * scharf ([QuietHours.mayFire]) — wie die Kursalarme. Reine Logik — wie `DepegAlarm.swift`.
 */
object DepegAlarm {

    /**
     * Messen? Mit eingeschaltetem Alarm immer; sonst, sobald ein Portfolio da ist (eingeschaltet und mit Transaktionen
     * oder beobachteten Adressen): Die Messung ist der Live-Kurs USDT/USD der Bewertung ([liveUsdtUsd], Owner-Entscheid
     * Okt. 2026). Gemeldet wird weiterhin nur mit eingeschaltetem Alarm ([depegAlarm]). Ebenso mit [fiatConversion]:
     * Fiat-Alarm oder ≈ Umrechnung auf einem Paar, dessen Faktor an USDT hängt
     * ([com.cryptochecker.app.domain.convert.FiatAlarmGuard.needsUsdtMeasurement]) — sonst rechnete sie still USDT = 1.
     */
    fun shouldMeasure(depegAlarm: Boolean, portfolioEnabled: Boolean, hasPortfolioData: Boolean, fiatConversion: Boolean = false): Boolean =
        depegAlarm || (portfolioEnabled && hasPortfolioData) || fiatConversion

    /** Schwelle in Zehntel-Prozent: 0.3 / 0.5 / 1 / 2 %. */
    val THRESHOLD_CHOICES = listOf(3, 5, 10, 20)
    const val DEFAULT_THRESHOLD_TENTHS = 5

    // Quellen: Börsen-Schlüssel und Paar-Kennung (wie in der Paarliste der Börse)
    const val KRAKEN_KEY = "Kraken"
    const val KRAKEN_PAIR_ID = "USDTZUSD"
    const val COINBASE_KEY = "Coinbase"
    const val COINBASE_PAIR_ID = "USDT-USD"
    const val BINANCE_KEY = "Binance"
    const val BINANCE_PAIR_ID = "USDCUSDT"

    /** Normalbereich: eine Quelle allein genügt; ausserhalb braucht es eine Bestätigung ([measure]). */
    private const val MIN_PLAUSIBLE = PortfolioStables.USDT_USD_MIN
    private const val MAX_PLAUSIBLE = PortfolioStables.USDT_USD_MAX

    /** Einbruch-Bereich: nur bestätigt gültig; ausserhalb davon immer Börsenfehler. */
    private const val MIN_BREAK = PortfolioStables.USDT_USD_BREAK_MIN
    private const val MAX_BREAK = PortfolioStables.USDT_USD_BREAK_MAX

    /** Zwei Kurse stimmen überein, wenn sie höchstens 5 % (vom grösseren) auseinanderliegen. */
    const val BREAK_AGREEMENT = 0.05

    /** Gespeicherte Schwelle; unbekannte Werte → Standard. */
    fun threshold(tenths: Int): Int = if (tenths in THRESHOLD_CHOICES) tenths else DEFAULT_THRESHOLD_TENTHS

    fun thresholdPercent(tenths: Int): Double = threshold(tenths) / 10.0

    private fun normal(price: Double): Boolean = price.isFinite() && price in MIN_PLAUSIBLE..MAX_PLAUSIBLE

    private fun inBreakRange(price: Double): Boolean = price.isFinite() && price in MIN_BREAK..MAX_BREAK

    private fun agree(a: Double, b: Double): Boolean = abs(a - b) <= BREAK_AGREEMENT * maxOf(a, b)

    /**
     * Ergebnis einer Messung: USDT in USD [price]; [viaUsdc] = nur aus Binance USDC/USDT abgeleitet (USDC = 1
     * angenommen) — dann nie ein Alarm ([mayAlarm]) und die Karte nennt die Herkunft.
     */
    data class Measurement(val price: Double, val viaUsdc: Boolean) {
        /** Bestätigter Einbruch (ausserhalb des Normalbereichs). */
        val broken: Boolean get() = PortfolioStables.isUsdtBreak(price)
    }

    /**
     * USDT in USD aus den drei Quellen ([kraken], [coinbase] = letzter Kurs USDT/USD; [binanceUsdc] = letzter Kurs
     * USDC/USDT; null = fehlt):
     *  1. Jeder Kurs ausserhalb 0.05…3 ist ein Börsenfehler.
     *  2. Im Normalbereich (0.5…1.5) genügt eine Quelle; im Einbruch-Bereich (0.05…0.5, 1.5…3) zählt ein Kurs nur,
     *     wenn eine andere Quelle innerhalb von [BREAK_AGREEMENT] übereinstimmt — ein einzelner Ausreisser bleibt
     *     unplausibel, ein echter Einbruch (mehrere Börsen bei 0.30) wird nicht mehr weggefiltert.
     *  3. Beide direkten Quellen gültig: der Wert näher bei 1 (USDC/USDT zählt nicht). Eine direkte: der Wert näher bei
     *     1 aus ihr und USDC/USDT (wie bisher: gemeldet wird nur, wenn beide abweichen). Keine direkte: USDC/USDT
     *     allein ([Measurement.viaUsdc]).
     */
    fun measure(kraken: Double?, coinbase: Double?, binanceUsdc: Double?): Measurement? {
        val usdc = binanceUsdc?.takeIf { it.isFinite() && it > 0 }?.let { 1.0 / it }
        val raw = listOf(kraken, coinbase, usdc).map { v -> v?.takeIf { inBreakRange(it) } }
        fun accepted(i: Int): Double? {
            val v = raw[i] ?: return null
            if (normal(v)) return v
            return v.takeIf { raw.withIndex().any { (j, o) -> j != i && o != null && agree(v, o) } }
        }
        val direct = listOfNotNull(accepted(0), accepted(1))
        val fromUsdc = accepted(2)
        return when {
            // Zwei Direktquellen widersprechen sich, USDC/USDT bestätigt eine davon: die Mehrheit (2 von 3) gilt
            direct.size == 2 && !agree(direct[0], direct[1]) && fromUsdc != null &&
                direct.count { agree(it, fromUsdc) } == 1 -> Measurement(direct.first { agree(it, fromUsdc) }, viaUsdc = false)
            direct.size == 2 -> Measurement(closestToOne(direct), viaUsdc = false)
            direct.size == 1 -> Measurement(closestToOne(direct + listOfNotNull(fromUsdc)), viaUsdc = false)
            fromUsdc != null -> Measurement(fromUsdc, viaUsdc = true)
            else -> null
        }
    }

    private fun closestToOne(values: List<Double>): Double = values.minBy { abs(it - 1.0) }

    /**
     * Taugt der letzte Kurs [last] einer Quelle für die Messung? Dieselbe Regel wie bei Kursalarmen
     * ([AlarmFreshness.exchangeTimeTooOld]): Meldet die Börse eine Zeit des Kurses ([exchangeTime]) älter als 2 Std.,
     * steht der Wert (Handel ruht) — er zählt wie keine Antwort (null), damit weder eine alte Zahl als frische Messung
     * gespeichert wird noch der Alarm auf ihr auslöst. Ohne Zeit der Börse zählt der Kurs wie bisher.
     */
    fun usablePrice(last: Double?, exchangeTime: Long?, now: Long): Double? =
        last?.takeUnless { AlarmFreshness.exchangeTimeTooOld(exchangeTime, now) }

    /** Darf diese Messung den Alarm auslösen? Nicht, wenn sie allein aus USDC/USDT stammt. */
    fun mayAlarm(measurement: Measurement): Boolean = !measurement.viaUsdc

    /** Abweichung von 1 USD in Prozent (negativ = unter 1). */
    fun deviationPercent(price: Double): Double = (price - 1.0) * 100.0

    /** Betrag der Abweichung, auf 4 Stellen gerundet (keine Fliesskomma-Ränder an der Schwelle). */
    private fun magnitude(deviationPercent: Double): Double = Math.round(abs(deviationPercent) * 10_000.0) / 10_000.0

    data class Decision(val fire: Boolean, val armed: Boolean)

    /**
     * @param mayFire Meldung jetzt erlaubt und zustellbar ([QuietHours.mayFire]).
     * @return melden? und «scharf danach» (gilt für eine abgesetzte Meldung, siehe [armedAfter]).
     */
    fun evaluate(deviationPercent: Double, thresholdPercent: Double, armed: Boolean, mayFire: Boolean): Decision {
        if (thresholdPercent <= 0) return Decision(fire = false, armed = true)
        val m = magnitude(deviationPercent)
        return when {
            // Fällig, aber nicht zustellbar: nicht verbrauchen
            armed && m >= thresholdPercent -> if (mayFire) Decision(true, false) else Decision(false, true)
            // Zurück innerhalb der halben Schwelle: wieder scharf
            !armed && m <= thresholdPercent / 2 -> Decision(false, true)
            else -> Decision(false, armed)
        }
    }

    /** Zu speichernder Zustand: Ging die fällige Meldung nicht ans System, bleibt der alte Zustand. */
    fun armedAfter(fire: Boolean, posted: Boolean, armedBefore: Boolean, next: Boolean): Boolean =
        if (fire && !posted) armedBefore else next

    /**
     * Letzte Messung der Prüfung (USDT in USD, Zeitpunkt) — gespeichert, damit Karte und Alarm übereinstimmen.
     * [viaUsdc]: nur aus Binance USDC/USDT abgeleitet ([Measurement.viaUsdc]).
     */
    data class Reading(val price: Double, val atMillis: Long, val viaUsdc: Boolean = false)

    /** So lange gilt eine Messung auf der Portfolio-Karte (Prüfung höchstens alle 5 Minuten). */
    const val CARD_MAX_AGE_MILLIS = 30 * 60_000L

    /**
     * So lange gilt eine Messung als Live-Kurs USDT/USD der Bewertung ([liveUsdtUsd]): 6 Std. — mit langen
     * Hintergrund-Intervallen fiele die Bewertung sonst oft auf Ersatz 1 («USDT = 1 angenommen») zurück; USDT bewegt
     * sich in dieser Zeit kaum. Die Warnzeile der Karte ([cardWarning]) bleibt bei [CARD_MAX_AGE_MILLIS].
     */
    const val LIVE_MAX_AGE_MILLIS = 6 * 60 * 60_000L

    /** Uhr leicht vorgestellt/zurückgestellt: kleine Abweichung in die Zukunft noch gültig. */
    private const val CARD_FUTURE_TOLERANCE_MILLIS = 60_000L

    /**
     * Warnzeile «USDT = 1 angenommen – Markt bei …» auf der Portfolio-Karte: nur mit einer Messung,
     * die höchstens [CARD_MAX_AGE_MILLIS] alt ist und um mindestens die Schwelle abweicht (gleiche
     * Rundung wie [evaluate]). Ohne Prüfung (Alarm aus) gibt es keine Messung und damit keine Zeile.
     */
    fun cardWarning(reading: Reading?, thresholdTenths: Int, now: Long): Reading? {
        reading ?: return null
        val age = now - reading.atMillis
        if (age > CARD_MAX_AGE_MILLIS || age < -CARD_FUTURE_TOLERANCE_MILLIS) return null
        if (!reading.price.isFinite()) return null
        return reading.takeIf { magnitude(deviationPercent(it.price)) >= thresholdPercent(thresholdTenths) }
    }

    /**
     * Live-Kurs USDT/USD für die Bewertung ([com.cryptochecker.app.domain.portfolio.UsdtUsdRates.live]): die letzte
     * Messung, wenn höchstens [LIVE_MAX_AGE_MILLIS] alt und im Einbruch-Bereich 0.05…3 (gespeichert wird nur, was
     * [measure] geprüft hat — ausserhalb des Normalbereichs also nur ein bestätigter Einbruch); sonst null (Bewertung
     * mit Ersatz 1, Näherung).
     */
    fun liveUsdtUsd(reading: Reading?, now: Long): Double? {
        reading ?: return null
        val age = now - reading.atMillis
        if (age > LIVE_MAX_AGE_MILLIS || age < -CARD_FUTURE_TOLERANCE_MILLIS) return null
        return reading.price.takeIf { inBreakRange(it) }
    }

    /** Art der Zeile zu «USDT = 1» auf der Portfolio-Karte ([cardNote]). */
    enum class CardNoteKind {
        /** Frische Messung weicht um mindestens die Schwelle ab: «USDT = 1 angenommen – Markt bei …» (Warnfarbe). */
        DEVIATION,
        /** Werte hängen an USDT = USD (Anzeigewährung, USD, gemischte Börsen): «Kurse: Binance · USDT = 1». */
        PRICE_SOURCE,
        /** Der Bestand enthält USDT (zählt 1) bzw. mit USDT zum Marktkurs: Ersatz 1 ohne Kurs — «USDT = 1 angenommen». */
        USDT_ASSUMED,
        /** Bestätigter USDT-Einbruch: die Werte rechnen mit dem Marktkurs — «USDT-Einbruch – Markt bei …» (Warnfarbe). */
        USDT_BREAK,
        /** Live-Kurs nur aus Binance USDC/USDT (USDC = 1 angenommen): «USDT/USD über USDC/USDT …». */
        VIA_USDC,
    }

    /** Zeile der Karte; [reading] bei [CardNoteKind.DEVIATION], [CardNoteKind.USDT_BREAK] und [CardNoteKind.VIA_USDC]. */
    data class CardNote(val kind: CardNoteKind, val reading: Reading? = null)

    /**
     * Zeile zu «USDT = 1» auf der Portfolio-Karte — UNABHÄNGIG vom Depeg-Alarm: Die Annahme gilt immer, also
     * steht sie da, sobald sie die Zahlen trägt; der Alarm liefert nur die Abweichung dazu.
     *  1. frische Messung ≥ Schwelle ([cardWarning], wie bisher immer auf der Karte) → [CardNoteKind.DEVIATION];
     *  2. sonst [valuesViaUsdt] (`PortfolioExchangeRoute.usdtParityShown`) → [CardNoteKind.PRICE_SOURCE];
     *  3. sonst enthält der Bestand [holdingCoins] USDT ([PortfolioStables.isValuation]) → [CardNoteKind.USDT_ASSUMED];
     *  4. sonst keine Zeile (null).
     * Mit USDT zum Marktkurs ([usdtMarket], Owner-Entscheid Okt. 2026): frischer Kurs ([liveUsdtUsd]) → keine Zeile
     * (nichts angenommen, die Werte rechnen mit dem Markt); ohne ihn trägt Ersatz 1 die Zahlen → wie 2./3., aber immer
     * [CardNoteKind.USDT_ASSUMED] («USDT = 1 angenommen» = Markierung der Näherung). Mit frischem Kurs und Werten, die
     * an USDT hängen: bestätigter Einbruch → [CardNoteKind.USDT_BREAK]; Kurs nur aus USDC/USDT → [CardNoteKind.VIA_USDC].
     */
    fun cardNote(
        holdingCoins: Collection<String>,
        valuesViaUsdt: Boolean,
        reading: Reading?,
        thresholdTenths: Int,
        now: Long,
        usdtMarket: Boolean = false,
    ): CardNote? {
        if (usdtMarket) {
            val dependsOnUsdt = valuesViaUsdt || holdingCoins.any { PortfolioStables.isValuation(it) }
            val live = liveUsdtUsd(reading, now)
            if (live != null && reading != null) {
                return when {
                    !dependsOnUsdt -> null
                    PortfolioStables.isUsdtBreak(live) -> CardNote(CardNoteKind.USDT_BREAK, reading)
                    reading.viaUsdc -> CardNote(CardNoteKind.VIA_USDC, reading)
                    else -> null
                }
            }
            return if (dependsOnUsdt) CardNote(CardNoteKind.USDT_ASSUMED) else null
        }
        cardWarning(reading, thresholdTenths, now)?.let { return CardNote(CardNoteKind.DEVIATION, it) }
        if (valuesViaUsdt) return CardNote(CardNoteKind.PRICE_SOURCE)
        if (holdingCoins.any { PortfolioStables.isValuation(it) }) return CardNote(CardNoteKind.USDT_ASSUMED)
        return null
    }

    /**
     * Seite USDC/USDT je Börse (Web-Adresse; ist die App der Börse installiert, öffnet das System
     * sie dort, wo sie die Adresse übernimmt). Bewusst kurz gehalten: nur grosse Börsen mit dem Paar.
     * Reihenfolge = Vorrang bei Gleichstand. Kein Handel in der App — nur ein Link.
     */
    val EXCHANGE_URLS: List<Pair<String, String>> = listOf(
        "Binance" to "https://www.binance.com/en/trade/USDC_USDT?type=spot",
        "Kraken" to "https://pro.kraken.com/app/trade/usdc-usdt",
        "Coinbase" to "https://www.coinbase.com/advanced-trade/spot/USDT-USDC",
        "Bybit" to "https://www.bybit.com/en/trade/spot/USDC/USDT",
        "Okex" to "https://www.okx.com/trade-spot/usdc-usdt",
        "Bitget" to "https://www.bitget.com/spot/USDCUSDT",
        "Kucoin" to "https://www.kucoin.com/trade/USDC-USDT",
        "GateIo" to "https://www.gate.io/trade/USDC_USDT",
        "Mexc" to "https://www.mexc.com/exchange/USDC_USDT",
    )

    /** Futures-Börsen zählen für ihre Spot-Börse. */
    private val ALIASES = mapOf("BinanceFutures" to "Binance", "OkexFutures" to "Okex")

    /** Ohne passende Börse in der Merkliste. */
    const val FALLBACK_EXCHANGE = "Kraken"

    /** Hauptbörse: die in der Merkliste am häufigsten genutzte Börse mit Link; Gleichstand → [EXCHANGE_URLS]-Reihenfolge. */
    fun mainExchange(exchangeKeys: List<String>): String {
        val counts = exchangeKeys.map { ALIASES[it] ?: it }.groupingBy { it }.eachCount()
        var best: String? = null
        var bestCount = 0
        for ((key, _) in EXCHANGE_URLS) {
            val count = counts[key] ?: 0
            if (count > bestCount) {
                best = key
                bestCount = count
            }
        }
        return best ?: FALLBACK_EXCHANGE
    }

    /** Link «Börse öffnen» für die Hauptbörse. */
    fun exchangeUrl(exchangeKeys: List<String>): String {
        val key = mainExchange(exchangeKeys)
        return EXCHANGE_URLS.firstOrNull { it.first == key }?.second
            ?: EXCHANGE_URLS.first { it.first == FALLBACK_EXCHANGE }.second
    }
}
