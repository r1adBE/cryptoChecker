package com.cryptochecker.app.domain.portfolio

import kotlin.math.abs
import kotlin.math.max

/** Art einer Portfolio-Transaktion. Name wird so in Datenbank und Sicherung geschrieben. */
enum class PortfolioTxType { BUY, SELL }

/**
 * Eine Transaktion, unabhängig von der Datenbank (reines Kotlin, testbar).
 * Alle Preise in USDT; [priceUsdt] null = Preis unbekannt (z. B. übernommener Bestand).
 */
data class PortfolioTrade(
    val id: Long,
    val coin: String,
    val type: PortfolioTxType,
    val amount: Double,
    val priceUsdt: Double?,
    val time: Long,
)

/** Kennzahlen eines Coins. Beträge in USDT. */
data class CoinPosition(
    val coin: String,
    /** Gehaltene Menge nach allen Transaktionen (nie negativ). */
    val holdings: Double,
    /** Ø Kaufpreis über die Käufe mit bekanntem Preis; null ohne solche. */
    val avgCost: Double?,
    /** Einstand des Bestands («investiert»); null, wenn bei einem Teil der Kaufpreis fehlt. */
    val costBasis: Double?,
    val currentPrice: Double?,
    /** Bestand × aktueller Kurs; null ohne Kurs. */
    val value: Double?,
    /** Unrealisierter Gewinn/Verlust; nur mit vollständigem Einstand und Kurs. */
    val unrealized: Double?,
    val unrealizedPercent: Double?,
    /** Realisierter Gewinn/Verlust aus Verkäufen mit bekanntem Preis und Einstand. */
    val realized: Double,
    /** Ein Teil des Bestands hat keinen Kaufpreis («Preis fehlt»). */
    val priceMissing: Boolean,
    /** Mindestens ein Verkauf war grösser als der Bestand und wurde gekappt. */
    val oversold: Boolean,
    val tradeCount: Int,
) {
    val isOpen: Boolean get() = holdings > PortfolioCalculator.EPS
}

/** Gesamtsicht über alle Coins. */
data class PortfolioSummary(
    /** Coins mit Bestand, nach Wert absteigend. */
    val open: List<CoinPosition>,
    /** Coins ohne Bestand, aber mit realisiertem Gewinn/Verlust. */
    val closed: List<CoinPosition>,
    /** Summe der Werte (Coins ohne Kurs zählen nicht mit). */
    val totalValue: Double,
    /** Einstand aller offenen Positionen; null, wenn irgendwo der Kaufpreis fehlt. */
    val invested: Double?,
    /** Unrealisiert gesamt; null, wenn Einstand oder ein aktueller Kurs fehlt. */
    val unrealized: Double?,
    val unrealizedPercent: Double?,
    val realized: Double,
    /** Bei mindestens einem offenen Coin fehlt ein Kaufpreis. */
    val costMissing: Boolean,
    /** Offene Coins ohne aktuellen Kurs. */
    val missingCurrentPrices: List<String>,
) {
    val isEmpty: Boolean get() = open.isEmpty() && closed.isEmpty()
}

/**
 * Durchschnittskosten-Methode:
 *  - Transaktionen je Coin zeitlich (bei Gleichstand nach Id) abarbeiten.
 *  - Kauf mit Preis: Ø = (Ø × Menge_vorher + Menge × Preis) / Menge_nachher — nur über
 *    Käufe mit Preis. Kauf ohne Preis erhöht nur den Bestand (Teil «ohne Preis»).
 *  - Verkauf: Bestand sinkt (nie unter 0; zu grosse Verkäufe werden gekappt und markiert).
 *    Beide Teile (mit/ohne Preis) sinken anteilig, der Ø bleibt unverändert.
 *    Realisiert += (Verkaufspreis − Ø) × Menge, wenn der Verkaufspreis bekannt ist und
 *    der ganze Bestand einen Kaufpreis hat.
 *  - Gewinn/Verlust nur, wenn der ganze Restbestand einen Kaufpreis hat.
 *  - USDT selbst hat immer den Kurs 1.
 */
object PortfolioCalculator {

    /** Darunter gilt eine Menge als null. */
    const val EPS = 1e-12

    /** Relative Toleranz, damit «alles verkaufen» bei Rundung nicht als Überverkauf gilt. */
    private const val SELL_TOLERANCE = 1e-9

    private val ONE_DOLLAR = setOf("USDT")

    fun normalizeCoin(coin: String): String = coin.trim().uppercase()

    /** Kennzahlen eines Coins aus seinen Transaktionen (fremde Coins werden ignoriert). */
    fun position(coin: String, trades: List<PortfolioTrade>, currentPrice: Double?): CoinPosition {
        val symbol = normalizeCoin(coin)
        val own = trades.filter { normalizeCoin(it.coin) == symbol }
            .sortedWith(compareBy<PortfolioTrade> { it.time }.thenBy { it.id })

        var priced = 0.0      // Menge mit Kaufpreis
        var unpriced = 0.0    // Menge ohne Kaufpreis
        var avg = 0.0         // Ø Kaufpreis des Teils mit Preis
        var realized = 0.0
        var oversold = false

        for (t in own) {
            val amount = t.amount
            if (!(amount > 0.0) || amount.isInfinite()) continue
            val price = t.priceUsdt?.takeIf { it >= 0.0 && !it.isInfinite() && !it.isNaN() }
            when (t.type) {
                PortfolioTxType.BUY -> {
                    if (price != null) {
                        avg = (avg * priced + amount * price) / (priced + amount)
                        priced += amount
                    } else {
                        unpriced += amount
                    }
                }
                PortfolioTxType.SELL -> {
                    val holdings = priced + unpriced
                    var sold = amount
                    if (sold > holdings + max(EPS, holdings * SELL_TOLERANCE)) oversold = true
                    if (sold > holdings) sold = holdings
                    if (holdings <= EPS || sold <= 0.0) continue

                    if (price != null && unpriced <= EPS && priced > EPS) {
                        realized += (price - avg) * sold
                    }
                    val keep = (holdings - sold) / holdings
                    priced *= keep
                    unpriced *= keep
                    if (priced <= EPS) { priced = 0.0; avg = 0.0 }
                    if (unpriced <= EPS) unpriced = 0.0
                }
            }
        }

        val holdings = (priced + unpriced).let { if (it <= EPS) 0.0 else it }
        val price = currentPrice?.takeIf { it > 0.0 && !it.isInfinite() }
            ?: if (symbol in ONE_DOLLAR) 1.0 else null
        val priceMissing = holdings > 0.0 && unpriced > EPS
        val avgCost = if (priced > EPS) avg else null
        val costBasis = if (holdings > 0.0 && !priceMissing) priced * avg else null
        val value = price?.let { holdings * it }
        val unrealized = if (costBasis != null && value != null) value - costBasis else null
        val unrealizedPercent = if (unrealized != null && costBasis != null && costBasis > 0.0) {
            unrealized / costBasis * 100.0
        } else null

        return CoinPosition(
            coin = symbol,
            holdings = holdings,
            avgCost = avgCost,
            costBasis = costBasis,
            currentPrice = price,
            value = value,
            unrealized = unrealized,
            unrealizedPercent = unrealizedPercent,
            realized = realized,
            priceMissing = priceMissing,
            oversold = oversold,
            tradeCount = own.size,
        )
    }

    /** Gesamtsicht; [prices] = aktueller USDT-Kurs je Coin (Grossschreibung). */
    fun summarize(trades: List<PortfolioTrade>, prices: Map<String, Double>): PortfolioSummary {
        val positions = trades.map { normalizeCoin(it.coin) }.distinct()
            .map { coin -> position(coin, trades, prices[coin]) }

        val open = positions.filter { it.isOpen }
            .sortedWith(
                compareByDescending<CoinPosition> { it.value ?: -1.0 }.thenBy { it.coin }
            )
        val closed = positions.filter { !it.isOpen && abs(it.realized) > 1e-9 }
            .sortedBy { it.coin }

        val totalValue = open.sumOf { it.value ?: 0.0 }
        val missingCurrent = open.filter { it.value == null }.map { it.coin }
        val costMissing = open.any { it.costBasis == null }
        val invested = if (open.isEmpty() || costMissing) null else open.sumOf { it.costBasis ?: 0.0 }
        val unrealized = if (invested != null && missingCurrent.isEmpty()) totalValue - invested else null
        val unrealizedPercent = if (unrealized != null && invested != null && invested > 0.0) {
            unrealized / invested * 100.0
        } else null

        return PortfolioSummary(
            open = open,
            closed = closed,
            totalValue = totalValue,
            invested = invested,
            unrealized = unrealized,
            unrealizedPercent = unrealizedPercent,
            realized = positions.sumOf { it.realized },
            costMissing = costMissing,
            missingCurrentPrices = missingCurrent,
        )
    }
}
