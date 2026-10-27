package com.cryptochecker.app.domain.portfolio

import kotlin.math.abs

/**
 * Einstand eines Coins für «Seit Kauf» des Widgets: [usdt] = Einstand des Teils mit Kaufpreis in USDT (erkennt, ob der
 * Coin seit dem Merken gekauft/verkauft wurde), [target] = derselbe Einstand in der Anzeigewährung zum Kurs der
 * Erwerbstage ([PortfolioCostFx]).
 */
data class PortfolioDisplayCost(val usdt: Double, val target: Double)

/**
 * Von der App gemerkte Kurse, mit denen jede Aufnahme des Portfolio-Widgets (auch im Hintergrund, ohne geladene
 * Devisen-Tageskurse) «Seit Kauf» und «7 T» in der Anzeigewährung wie die Gesamtkarte rechnet — statt USDT × heutiger
 * Kurs. Gespiegelt in `PortfolioDisplayFx.swift`.
 */
data class PortfolioDisplayFx(
    /** Anzeigewährung, für die die Werte gelten. */
    val currency: String,
    /** Einstand je Coin ([PortfolioCostFxFigures.coinCosts]); Coins ohne Devisenkurs eines Erwerbstags fehlen. */
    val costs: Map<String, PortfolioDisplayCost>,
    /** Faktor USDT → [currency] je UTC-Tag (Devisenkurs × USDT/USD-Tagesschluss, [PortfolioHistoryFx.rateFunction]). */
    val dayFactors: Map<Long, Double>,
    /** UTC-Tag, an dem [dayFactors] gemerkt wurden (sie reichen bis zum Vortag). */
    val day: Long,
)

/**
 * Reine Regeln für «Seit Kauf» und «7 T» des Widgets in der Anzeigewährung (getestet in PortfolioDisplayFxTest;
 * gespiegelt in `PortfolioDisplayFigures`, iOS).
 */
object PortfolioDisplayFigures {

    /** So viele Tage vor dem Merk-Tag stehen in [PortfolioDisplayFx.dayFactors] — genug für «7 T» samt Vortag. */
    const val MEMO_DAYS = 14

    /** Tage von «7 T». */
    const val WEEK_DAYS = PortfolioPeriodChange.DAYS

    private fun same(a: String, b: String): Boolean = a.trim().equals(b.trim(), ignoreCase = true)

    /**
     * Merkzettel aus den Zahlen der App: Einstand je Coin aus [figures] (mit dem USDT-Einstand aus [open] zur
     * Erkennung) und die Faktoren [dayFactor] der [MEMO_DAYS] Tage vor [todayEpochDay]; ohne [dayFactor] keine Faktoren
     * («7 T» dann in USDT). null ohne [figures].
     */
    fun memo(
        currency: String,
        figures: PortfolioCostFxFigures?,
        open: List<CoinPosition>,
        dayFactor: ((Long) -> Double)?,
        todayEpochDay: Long,
    ): PortfolioDisplayFx? {
        if (figures == null) return null
        val costs = LinkedHashMap<String, PortfolioDisplayCost>()
        for (p in open) {
            val target = figures.coinCosts[p.coin] ?: continue
            val usdt = p.pricedCostExact ?: continue
            costs[PortfolioCalculator.normalizeCoin(p.coin)] = PortfolioDisplayCost(usdt.toDouble(), target.toDouble())
        }
        val factors = HashMap<Long, Double>()
        if (dayFactor != null) {
            for (d in todayEpochDay - MEMO_DAYS until todayEpochDay) {
                dayFactor(d).takeIf { it > 0.0 && it.isFinite() }?.let { factors[d] = it }
            }
        }
        return PortfolioDisplayFx(currency.trim().uppercase(), costs, factors, todayEpochDay)
    }

    /**
     * «Seit Kauf» in [currency] wie [PortfolioCostFx.figures]: Σ (Wert des Teils mit Kaufpreis × [currentRate] −
     * gemerkter Einstand) über die Coins mit Kaufpreis und Kurs; Prozent auf den Einstand. Ein Coin fehlt (steht in
     * [PortfolioChange.skipped] — «unvollständig»), wenn sein Einstand nicht gemerkt ist oder sich seither geändert hat
     * (Kauf/Verkauf). null ohne passenden Merkzettel oder wenn kein Coin übrig bleibt.
     */
    fun unrealized(open: List<CoinPosition>, currency: String, currentRate: Double, memo: PortfolioDisplayFx?): PortfolioChange? {
        if (memo == null || !same(memo.currency, currency) || !(currentRate > 0.0) || !currentRate.isFinite()) return null
        var amount: Double? = null
        var cost = 0.0
        val skipped = sortedSetOf<String>()
        for (p in open) {
            val unrealized = p.unrealizedExact ?: continue
            val priced = p.pricedCostExact ?: continue
            val coin = PortfolioCalculator.normalizeCoin(p.coin)
            val m = memo.costs[coin]
            val pricedUsdt = priced.toDouble()
            if (m == null || abs(m.usdt - pricedUsdt) > 1e-9 * maxOf(1.0, abs(pricedUsdt))) {
                skipped += coin
                continue
            }
            val value = (unrealized + priced).toDouble()
            amount = (amount ?: 0.0) + value * currentRate - m.target
            cost += m.target
        }
        val a = amount?.takeIf { it.isFinite() } ?: return null
        return PortfolioChange(a, if (cost > 0.0) a / cost * 100.0 else null, skipped.toList())
    }

    /**
     * Faktor je Tag für «7 T» in [currency] ([PortfolioPeriodChange.gain] `fx`): die gemerkten Faktoren, ab heute
     * [currentRate] — nur, wenn der Merkzettel heute ([todayEpochDay]) gemerkt wurde und jeden Tag von heute − [days]
     * bis gestern hat; sonst null («7 T» dann in USDT).
     */
    fun periodFx(memo: PortfolioDisplayFx?, currency: String, currentRate: Double, todayEpochDay: Long, days: Int = WEEK_DAYS): ((Long) -> Double)? {
        if (memo == null || !same(memo.currency, currency) || memo.day != todayEpochDay) return null
        if (!(currentRate > 0.0) || !currentRate.isFinite()) return null
        if ((todayEpochDay - days until todayEpochDay).any { it !in memo.dayFactors }) return null
        val factors = memo.dayFactors
        return { day -> if (day >= todayEpochDay) currentRate else factors[day] ?: currentRate }
    }

    /** [change] aus [PortfolioPeriodChange.gain] mit `fx` (Betrag schon in der Anzeigewährung) für die Aufnahme. */
    fun periodChange(change: TodayChange?): PortfolioChange? =
        change?.takeIf { it.amountUsd.isFinite() }?.let { PortfolioChange(it.amountUsd, it.percent?.takeIf { p -> p.isFinite() }, it.skipped) }
}
