package com.cryptochecker.app.domain.market

import kotlin.math.abs

/**
 * Eingaben je Coin für «Heute auffällig» (marktweit ungewöhnliche Bewegungen).
 *
 * @param change24h 24-h-Veränderung in % (Binance-Spiegel, …USDT)
 * @param quoteVolume 24-h-Umsatz in USDT (Binance-Spiegel)
 * @param marketCap Marktkapitalisierung in USD (CoinGecko-Rangliste, bis 24 h alt — ändert sich langsam)
 * @param fundingPercent letzte Funding Rate des USDT-M-Perpetuals in % je Periode (meist 8 h); null = kein Perpetual
 */
data class UnusualCoin(
    val symbol: String,
    val name: String,
    val change24h: Double,
    val quoteVolume: Double?,
    val marketCap: Double?,
    val fundingPercent: Double?,
)

/**
 * Alle Eingaben einer Abfrage.
 *
 * @param ownTypical je Coin der eigene übliche Umsatz-Anteil (Median der Vortage, siehe
 *   [MarketUnusual.ownTypical]); fehlt er, gilt der Median aller Coins dieser Abfrage
 * @param time Zeitpunkt der Abfrage (Epoch-ms)
 */
data class UnusualInput(
    val coins: List<UnusualCoin>,
    val ownTypical: Map<String, Double>,
    val time: Long,
)

enum class UnusualFactKind {
    /** Bewegt sich deutlich stärker als BTC (≥ 3 Prozentpunkte darüber). */
    STRONGER_THAN_BTC,

    /** Bewegt sich deutlich schwächer als BTC (≥ 3 Prozentpunkte darunter). */
    WEAKER_THAN_BTC,

    /** Gegen den Markt: andere Richtung als BTC, mindestens ±2 %. */
    AGAINST_MARKET,

    /** Umsatz mindestens doppelt so hoch wie üblich. */
    VOLUME,

    /** Funding ≥ 0.05 % je Periode. */
    FUNDING_HIGH,

    /** Funding ≤ −0.03 % je Periode. */
    FUNDING_NEGATIVE,
}

/**
 * Eine auffällige Tatsache.
 * @param strength Stärke relativ zur Schwelle (1.0 = genau an der Schwelle) — zum Sortieren
 * @param volumeRatio bei [UnusualFactKind.VOLUME]: Umsatz ÷ üblich (z. B. 2.4)
 * @param fundingPercent bei Funding-Tatsachen: die Funding Rate in %
 */
data class UnusualFact(
    val kind: UnusualFactKind,
    val strength: Double,
    val volumeRatio: Double? = null,
    val fundingPercent: Double? = null,
)

/** Eine Zeile der Karte: Coin, 24-h-Veränderung und die auffälligste Tatsache ([fact]). */
data class UnusualRow(
    val symbol: String,
    val name: String,
    val change24h: Double,
    val facts: List<UnusualFact>,
    val score: Double,
) {
    val fact: UnusualFact get() = facts.first()
}

/** Ergebnis: höchstens [MarketUnusual.MAX_ROWS] Zeilen; leer = «Heute nichts Auffälliges». */
data class UnusualReport(
    val rows: List<UnusualRow>,
    val btc24h: Double?,
    val time: Long,
)

/** Ein Tageswert des Umsatz-Anteils (24-h-Umsatz ÷ Marktkapitalisierung) eines Coins. */
data class TurnoverSample(val day: Long, val value: Double)

/**
 * «Heute auffällig» — reine Logik, testbar; wie `MarketUnusual.swift` (iOS).
 *
 * Datenwahl (günstig und robust, ohne Kerzen je Coin):
 *  - 24-h-Veränderung und Umsatz aller Coins mit EINER Abfrage (Binance-Spiegel, 24-h-Ticker)
 *  - Marktkapitalisierung aus der CoinGecko-Rangliste (24 h zwischengespeichert, wie die Start-Coins)
 *  - Funding aller USDT-M-Perpetuals mit EINER Abfrage (Binance Futures, premiumIndex)
 *
 * «Üblicher» Umsatz: Umsatz-Anteil = 24-h-Umsatz ÷ Marktkapitalisierung. Verglichen wird mit
 * dem eigenen Median der Vortage (sobald [MIN_OWN_DAYS] Tage gesammelt sind, siehe
 * [updateHistory]), sonst mit dem Median aller Coins dieser Abfrage — beides ohne
 * zusätzliche Abfragen.
 *
 * Keine Prognose, keine Empfehlung: nur Tatsachen, die heute vom Gewohnten abweichen.
 */
object MarketUnusual {
    /** Abstand zu BTC in Prozentpunkten für «deutlich stärker/schwächer». */
    const val RELATIVE_PP = 3.0

    /** Mindestbewegung für «gegen den Markt». */
    const val AGAINST_MIN_PERCENT = 2.0

    /** BTC muss sich mindestens so stark bewegen, damit «andere Richtung» etwas bedeutet. */
    const val BTC_DIRECTION_MIN_PERCENT = 0.5

    /** Umsatz ÷ üblich ab hier «ungewöhnlich». */
    const val VOLUME_RATIO = 2.0

    const val FUNDING_HIGH_PERCENT = 0.05
    const val FUNDING_NEGATIVE_PERCENT = -0.03

    const val MAX_ROWS = 5

    /** Mindestens so viele Coins für einen Median aller Coins. */
    const val MIN_UNIVERSE = 5

    /** Eigener Median erst ab so vielen Vortagen. */
    const val MIN_OWN_DAYS = 5

    /** So viele Tage je Coin aufbewahren. */
    const val HISTORY_DAYS = 14

    /** Weitere Tatsachen heben eine Zeile leicht an (bei gleicher Haupttatsache). */
    private const val EXTRA_FACT_BONUS = 0.25

    /** «Gegen den Markt» wiegt etwas mehr als derselbe Abstand zu BTC. */
    private const val AGAINST_WEIGHT = 1.25

    /** Umsatz-Anteil (24-h-Umsatz ÷ Marktkapitalisierung); null ohne gültige Werte. */
    fun turnover(coin: UnusualCoin): Double? {
        val volume = coin.quoteVolume ?: return null
        val cap = coin.marketCap ?: return null
        if (!volume.isFinite() || !cap.isFinite() || volume <= 0.0 || cap <= 0.0) return null
        return volume / cap
    }

    fun median(values: List<Double>): Double? {
        val sorted = values.filter { it.isFinite() }.sorted()
        if (sorted.isEmpty()) return null
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /**
     * Tatsachen eines Coins, die auffälligste zuerst.
     * @param btc 24-h-Veränderung von BTC (null = unbekannt, dann keine Vergleiche mit BTC)
     * @param typical üblicher Umsatz-Anteil (null = kein Vergleich möglich)
     */
    fun facts(coin: UnusualCoin, btc: Double?, typical: Double?): List<UnusualFact> {
        val out = ArrayList<UnusualFact>(3)
        val change = coin.change24h
        val isBtc = coin.symbol.equals("BTC", ignoreCase = true)

        if (btc != null && !isBtc && change.isFinite() && btc.isFinite()) {
            val against = abs(btc) >= BTC_DIRECTION_MIN_PERCENT &&
                abs(change) >= AGAINST_MIN_PERCENT &&
                (change > 0) != (btc > 0)
            val diff = change - btc
            if (against) {
                // Schliesst «stärker/schwächer» ein — nicht doppelt nennen
                out += UnusualFact(UnusualFactKind.AGAINST_MARKET, abs(change) / AGAINST_MIN_PERCENT * AGAINST_WEIGHT)
            } else if (abs(diff) >= RELATIVE_PP) {
                val kind = if (diff > 0) UnusualFactKind.STRONGER_THAN_BTC else UnusualFactKind.WEAKER_THAN_BTC
                out += UnusualFact(kind, abs(diff) / RELATIVE_PP)
            }
        }

        val turnover = turnover(coin)
        if (turnover != null && typical != null && typical > 0.0 && typical.isFinite()) {
            val ratio = turnover / typical
            if (ratio >= VOLUME_RATIO) out += UnusualFact(UnusualFactKind.VOLUME, ratio / VOLUME_RATIO, volumeRatio = ratio)
        }

        val funding = coin.fundingPercent
        if (funding != null && funding.isFinite()) {
            if (funding >= FUNDING_HIGH_PERCENT) {
                out += UnusualFact(UnusualFactKind.FUNDING_HIGH, funding / FUNDING_HIGH_PERCENT, fundingPercent = funding)
            } else if (funding <= FUNDING_NEGATIVE_PERCENT) {
                out += UnusualFact(
                    UnusualFactKind.FUNDING_NEGATIVE, funding / FUNDING_NEGATIVE_PERCENT, fundingPercent = funding
                )
            }
        }
        return out.sortedWith(compareByDescending<UnusualFact> { it.strength }.thenBy { it.kind.ordinal })
    }

    /**
     * Auswertung: je Coin die Tatsachen, Zeilen nach Auffälligkeit (stärkste Tatsache, dazu
     * ein kleiner Zuschlag je weitere), höchstens [MAX_ROWS]. null ohne Coins.
     */
    fun evaluate(input: UnusualInput): UnusualReport? {
        val coins = input.coins
            .filter { it.change24h.isFinite() && it.symbol.isNotBlank() }
            .distinctBy { it.symbol.uppercase() }
        if (coins.isEmpty()) return null
        val btc = coins.firstOrNull { it.symbol.equals("BTC", ignoreCase = true) }?.change24h
        val turnovers = coins.mapNotNull { turnover(it) }
        val universeMedian = if (turnovers.size >= MIN_UNIVERSE) median(turnovers) else null

        val rows = coins.mapNotNull { coin ->
            val typical = input.ownTypical[coin.symbol.uppercase()]?.takeIf { it > 0.0 && it.isFinite() }
                ?: universeMedian
            val facts = facts(coin, btc, typical)
            if (facts.isEmpty()) return@mapNotNull null
            UnusualRow(
                symbol = coin.symbol.uppercase(),
                name = coin.name,
                change24h = coin.change24h,
                facts = facts,
                score = facts.first().strength + EXTRA_FACT_BONUS * (facts.size - 1),
            )
        }
            .sortedWith(
                compareByDescending<UnusualRow> { it.score }
                    .thenByDescending { abs(it.change24h) }
                    .thenBy { it.symbol }
            )
            .take(MAX_ROWS)
        return UnusualReport(rows, btc, input.time)
    }

    // ---- Eigener «üblicher» Umsatz: ein Wert je Tag und Coin, ohne zusätzliche Abfragen

    /**
     * Neue Tageswerte eintragen (ein Wert je [day], der jüngste des Tages gilt), nur Coins
     * dieser Abfrage behalten, je Coin die letzten [HISTORY_DAYS] Tage.
     * @param day Tag (z. B. Epoch-Tag in Ortszeit)
     */
    fun updateHistory(
        history: Map<String, List<TurnoverSample>>,
        coins: List<UnusualCoin>,
        day: Long,
    ): Map<String, List<TurnoverSample>> {
        val out = HashMap<String, List<TurnoverSample>>()
        for (coin in coins) {
            val symbol = coin.symbol.uppercase()
            val old = history[symbol].orEmpty().filter { it.day < day && it.value.isFinite() }
            val today = turnover(coin)?.let { TurnoverSample(day, it) }
            val merged = (old + listOfNotNull(today))
                .sortedBy { it.day }
                .filter { it.day > day - HISTORY_DAYS }
            if (merged.isNotEmpty()) out[symbol] = merged
        }
        return out
    }

    /** Eigener üblicher Umsatz-Anteil: Median der Vortage (ohne [today]); null unter [MIN_OWN_DAYS] Tagen. */
    fun ownTypical(samples: List<TurnoverSample>, today: Long): Double? {
        val before = samples.filter { it.day < today && it.value > 0.0 }.map { it.value }
        return if (before.size >= MIN_OWN_DAYS) median(before) else null
    }
}
