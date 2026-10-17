package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.domain.portfolio.PortfolioStables
import kotlin.math.abs

/**
 * USDT-Depeg-Alarm (Build 46): meldet einmal je Abweichung, wenn USDT um mindestens die Schwelle
 * von 1 USD abweicht. Bewertungen in der App rechnen weiter mit USDT = 1 (nur ein Hinweis).
 *
 * Quellen (bestehende Kurs-Abrufe, kein neuer Anbieter): Kraken USDT/USD (direkt) und Binance
 * USDC/USDT (USDT = 1 / Kurs, USDC als 1 USD angenommen). Beide da: der Wert näher bei 1 zählt —
 * gemeldet wird also nur, wenn beide Quellen abweichen (eine Börse allein löst keinen Fehlalarm aus).
 * Nur eine da: diese. Keine: keine Prüfung.
 *
 * Hysterese: Nach einer Meldung erst wieder scharf, wenn die Abweichung auf die halbe Schwelle
 * zurückgeht. Kann die Meldung gerade nicht ankommen (Nachtruhe, keine Erlaubnis), bleibt der Alarm
 * scharf ([QuietHours.mayFire]) — wie die Kursalarme. Reine Logik — wie `DepegAlarm.swift`.
 */
object DepegAlarm {

    /** Schwelle in Zehntel-Prozent: 0.3 / 0.5 / 1 / 2 %. */
    val THRESHOLD_CHOICES = listOf(3, 5, 10, 20)
    const val DEFAULT_THRESHOLD_TENTHS = 5

    // Quellen: Börsen-Schlüssel und Paar-Kennung (wie in der Paarliste der Börse)
    const val KRAKEN_KEY = "Kraken"
    const val KRAKEN_PAIR_ID = "USDTZUSD"
    const val BINANCE_KEY = "Binance"
    const val BINANCE_PAIR_ID = "USDCUSDT"

    /** Unplausible Kurse (Fehler der Börse) fallen weg. */
    private const val MIN_PLAUSIBLE = 0.5
    private const val MAX_PLAUSIBLE = 1.5

    /** Gespeicherte Schwelle; unbekannte Werte → Standard. */
    fun threshold(tenths: Int): Int = if (tenths in THRESHOLD_CHOICES) tenths else DEFAULT_THRESHOLD_TENTHS

    fun thresholdPercent(tenths: Int): Double = threshold(tenths) / 10.0

    private fun plausible(price: Double): Boolean = price.isFinite() && price in MIN_PLAUSIBLE..MAX_PLAUSIBLE

    /** USDT in USD aus Kraken USDT/USD. */
    fun fromKraken(last: Double?): Double? = last?.takeIf { plausible(it) }

    /** USDT in USD aus Binance USDC/USDT (1 USDC = [last] USDT). */
    fun fromBinanceUsdc(last: Double?): Double? =
        last?.takeIf { it.isFinite() && it > 0 }?.let { 1.0 / it }?.takeIf { plausible(it) }

    /** Beide Quellen: der Wert näher bei 1; eine: diese; keine: null. */
    fun combine(kraken: Double?, binance: Double?): Double? =
        listOfNotNull(kraken, binance).minByOrNull { abs(it - 1.0) }

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

    /** Letzte Messung der Prüfung (USDT in USD, Zeitpunkt) — gespeichert, damit Karte und Alarm übereinstimmen. */
    data class Reading(val price: Double, val atMillis: Long)

    /** So lange gilt eine Messung auf der Portfolio-Karte (Prüfung höchstens alle 5 Minuten). */
    const val CARD_MAX_AGE_MILLIS = 30 * 60_000L

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

    /** Art der Zeile zu «USDT = 1» auf der Portfolio-Karte ([cardNote]). */
    enum class CardNoteKind {
        /** Frische Messung weicht um mindestens die Schwelle ab: «USDT = 1 angenommen – Markt bei …» (Warnfarbe). */
        DEVIATION,
        /** Werte hängen an USDT = USD (Anzeigewährung, USD, gemischte Börsen): «Kurse: Binance · USDT = 1». */
        PRICE_SOURCE,
        /** Der Bestand enthält USDT (zählt immer genau 1): «USDT = 1 angenommen». */
        USDT_ASSUMED,
    }

    /** Zeile der Karte; [reading] nur bei [CardNoteKind.DEVIATION] (Zahlen der Warnzeile). */
    data class CardNote(val kind: CardNoteKind, val reading: Reading? = null)

    /**
     * Zeile zu «USDT = 1» auf der Portfolio-Karte (Build 58) — UNABHÄNGIG vom Depeg-Alarm: Die Annahme gilt immer, also
     * steht sie da, sobald sie die Zahlen trägt; der Alarm liefert nur die Abweichung dazu.
     *  1. frische Messung ≥ Schwelle ([cardWarning], wie bisher immer auf der Karte) → [CardNoteKind.DEVIATION];
     *  2. sonst [valuesViaUsdt] (`PortfolioExchangeRoute.usdtParityShown`) → [CardNoteKind.PRICE_SOURCE];
     *  3. sonst enthält der Bestand [holdingCoins] USDT ([PortfolioStables.isValuation]) → [CardNoteKind.USDT_ASSUMED];
     *  4. sonst keine Zeile (null).
     */
    fun cardNote(
        holdingCoins: Collection<String>,
        valuesViaUsdt: Boolean,
        reading: Reading?,
        thresholdTenths: Int,
        now: Long,
    ): CardNote? {
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
    fun mainExchange(marketKeys: List<String>): String {
        val counts = marketKeys.map { ALIASES[it] ?: it }.groupingBy { it }.eachCount()
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
    fun exchangeUrl(marketKeys: List<String>): String {
        val key = mainExchange(marketKeys)
        return EXCHANGE_URLS.firstOrNull { it.first == key }?.second
            ?: EXCHANGE_URLS.first { it.first == FALLBACK_EXCHANGE }.second
    }
}
