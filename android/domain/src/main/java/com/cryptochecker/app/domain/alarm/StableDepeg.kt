package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioExchangeRoute
import com.cryptochecker.app.domain.portfolio.PortfolioStables
import kotlin.math.abs

/**
 * Depeg-Alarm für weitere Stablecoins neben USDT ([DepegAlarm]): derselbe Schalter, dieselbe Schwelle und Hysterese
 * ([DepegAlarm.evaluate]), je Stablecoin ein eigener Zustand. Reine Logik — wie `StableDepeg.swift`.
 *  - USDC wird mit eingeschaltetem Alarm immer geprüft: Kraken USDC/USD (direkt) und Binance USDC/USDT × USDT/USD
 *    (Kreuzkurs aus dem schon abgefragten USDC/USDT der USDT-Messung). Ein Kreuzkurs allein löst nie aus.
 *    Coinbase führt kein Paar USDC-USD (USDC zählt dort 1:1 als USD) — darum keine Coinbase-Quelle für USDC.
 *  - Die übrigen Stablecoins ([COINS]) nur, wenn sie in der Merkliste oder im Portfolio stehen ([watched]), über ein
 *    Spot-Paar der Paarliste von Kraken, Coinbase oder Binance ([EXCHANGES], [pickPair]) gegen USD, USDC oder USDT;
 *    gemeldet wird nur mit mindestens zwei unabhängigen Quellen (verschiedene Börsen, [measure]).
 */
object StableDepeg {

    const val USDC = "USDC"

    /**
     * Weitere Stablecoins in fester Reihenfolge ([com.cryptochecker.app.domain.convert.CurrencyConversion.USD_STABLES]
     * ohne USD und USDT): Reihenfolge der Einstellungs-Zeile und Nummer der Mitteilung ([notificationIndex]).
     */
    val COINS = listOf("USDC", "FDUSD", "BUSD", "DAI", "TUSD", "USDE", "USD1", "RLUSD", "PYUSD", "USDP", "GUSD")

    /** Börsen für die übrigen Stablecoins (bestehende Ticker-Abrufe, Reihenfolge = Reihenfolge der Quellen). */
    val EXCHANGES = listOf(DepegAlarm.KRAKEN_KEY, DepegAlarm.COINBASE_KEY, DepegAlarm.BINANCE_KEY)

    /** Gegenwährungen in Vorrang-Reihenfolge ([pickPair]). */
    val QUOTES = listOf("USD", "USDC", "USDT")

    /** Kraken USDC/USD (Paar-Kennung wie in der Paarliste der Börse). */
    const val KRAKEN_USDC_PAIR_ID = "USDCUSD"

    /**
     * Geprüfte Stablecoins: USDC immer, die übrigen nur, wenn ihr Kürzel als Basis einer Merklisten-Zeile
     * ([watchBases]) oder als Coin des Portfolios ([holdings]) vorkommt (Gross-/Kleinschreibung egal). Reihenfolge [COINS].
     */
    fun watched(watchBases: Collection<String>, holdings: Collection<String>): List<String> {
        val held = (watchBases + holdings).map { PortfolioCalculator.normalizeCoin(it) }.toSet()
        return COINS.filter { it == USDC || it in held }
    }

    /** Spot-Paar von [coin] in [pairs] mit Gegenwährung nach [QUOTES] (nie gegen sich selbst); null = keines. */
    fun pickPair(coin: String, pairs: List<PortfolioExchangeRoute.Pair3>): PortfolioExchangeRoute.Pair3? {
        val base = PortfolioCalculator.normalizeCoin(coin)
        val own = pairs.filter { PortfolioCalculator.normalizeCoin(it.base) == base }
        for (quote in QUOTES) {
            if (quote == base) continue
            own.firstOrNull { it.quote.uppercase() == quote }?.let { return it }
        }
        return null
    }

    /**
     * Kurs [price] in der Gegenwährung [quote] in USD: USD direkt; USDT × [usdtUsd]; USDC × [usdcUsd] (die Messungen
     * dieses Laufs). Fehlt der nötige Kurs (null) oder ist [price] ungültig: null — nie still 1 angenommen.
     */
    fun toUsd(price: Double?, quote: String, usdtUsd: Double?, usdcUsd: Double?): Double? {
        val p = price?.takeIf { it.isFinite() && it > 0 } ?: return null
        val factor = when (quote.uppercase()) {
            "USD" -> 1.0
            "USDT" -> usdtUsd
            "USDC" -> usdcUsd
            else -> null
        }?.takeIf { it.isFinite() && it > 0 } ?: return null
        return p * factor
    }

    /** Eine Quelle in USD ([usd] null = fehlt); [cross] = über einen anderen Stablecoin umgerechnet. */
    data class Source(val usd: Double?, val cross: Boolean)

    /** Messung eines Stablecoins in USD; [alarmable] = darf den Alarm auslösen ([measure]). */
    data class Measurement(val price: Double, val alarmable: Boolean)

    /** Zwei unabhängige Quellen nötig? USDC nicht (wie USDT: eine direkte genügt), alle übrigen ja. */
    fun requiresTwo(coin: String): Boolean = PortfolioCalculator.normalizeCoin(coin) != USDC

    /**
     * Messung aus [sources] (je Börse höchstens eine) nach den Regeln von [DepegAlarm.measure]:
     *  1. Jeder Kurs ausserhalb 0.05…3 ist ein Börsenfehler.
     *  2. Im Normalbereich (0.5…1.5) zählt ein Kurs; im Einbruch-Bereich nur, wenn eine andere Quelle innerhalb von
     *     [DepegAlarm.BREAK_AGREEMENT] übereinstimmt.
     *  3. Gilt der Wert näher bei 1 (gemeldet wird nur, wenn alle gültigen Quellen abweichen).
     * [alarmable]: mit [requireTwo] mindestens zwei gültige Quellen, sonst mindestens eine direkte (nie ein Kreuzkurs
     * allein). Keine gültige Quelle: null.
     */
    fun measure(sources: List<Source>, requireTwo: Boolean): Measurement? {
        val raw = sources.map { s -> s.usd?.takeIf { inBreakRange(it) } }
        val accepted = raw.indices.filter { i ->
            val v = raw[i] ?: return@filter false
            normal(v) || raw.withIndex().any { (j, o) -> j != i && o != null && agree(v, o) }
        }
        if (accepted.isEmpty()) return null
        val price = accepted.mapNotNull { raw[it] }.minBy { abs(it - 1.0) }
        val alarmable = if (requireTwo) accepted.size >= 2 else accepted.any { !sources[it].cross }
        return Measurement(price, alarmable)
    }

    private fun normal(p: Double) = p.isFinite() && p >= PortfolioStables.USDT_USD_MIN && p <= PortfolioStables.USDT_USD_MAX

    private fun inBreakRange(p: Double) =
        p.isFinite() && p >= PortfolioStables.USDT_USD_BREAK_MIN && p <= PortfolioStables.USDT_USD_BREAK_MAX

    private fun agree(a: Double, b: Double) = abs(a - b) <= DepegAlarm.BREAK_AGREEMENT * maxOf(a, b)

    /** Ordnungszahl für Mitteilungs-Nummer und Knopf (je Stablecoin eine eigene Meldung); -1 = unbekannt. */
    fun notificationIndex(coin: String): Int = COINS.indexOf(PortfolioCalculator.normalizeCoin(coin))

    /**
     * Einträge der Zeile «Überwacht» unter dem Schalter: USDT, dann [watched] (Reihenfolge [COINS]), je mit dem letzten
     * Kurs, solange er höchstens [DepegAlarm.LIVE_MAX_AGE_MILLIS] alt ist ([DepegAlarm.liveUsdtUsd]); sonst ohne Kurs (null).
     */
    fun lineItems(
        watched: List<String>,
        usdt: DepegAlarm.Reading?,
        readings: Map<String, DepegAlarm.Reading>,
        now: Long,
    ): List<Pair<String, Double?>> =
        listOf(PortfolioStables.VALUATION to DepegAlarm.liveUsdtUsd(usdt, now)) +
            COINS.filter { it in watched }.map { it to DepegAlarm.liveUsdtUsd(readings[it], now) }
}
