package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.time.LocalDate

/**
 * «Seit Kauf» und Einstand der Gesamtkarte in der Anzeigewährung ([PortfolioCostFx.figures]). Beträge in der
 * Anzeigewährung, exakt; null = unbekannt.
 */
data class PortfolioCostFxFigures(
    /**
     * Gewinn/Verlust seit Kauf: Wert des Teils mit Kaufpreis × heutiger Faktor − dessen Einstand zu den Kursen der
     * Erwerbstage, über dieselben Coins wie [PortfolioSummary.unrealized] — ohne die Coins in [fxMissingCoins]. null,
     * wenn kein solcher Coin umgerechnet werden kann.
     */
    val unrealized: BigDecimal?,
    /** [unrealized] ÷ Einstand derselben Coins in der Anzeigewährung × 100; null ohne Einstand > 0. */
    val unrealizedPercent: Double?,
    /** Einstand wie [PortfolioSummary.invested] (Coins ganz mit Kaufpreis) zu den Kursen der Erwerbstage; null = keiner. */
    val invested: BigDecimal?,
    /** In [unrealized] fehlt ein Coin, weil für einen Erwerbstag kein Devisenkurs da ist («unvollständig»). */
    val unrealizedFxMissing: Boolean,
    /** In [invested] fehlt ein Coin aus demselben Grund. */
    val investedFxMissing: Boolean,
    /** Coins ohne Devisenkurs eines Erwerbstags, alphabetisch (Hinweis). */
    val fxMissingCoins: List<String>,
    /** An mindestens einem Erwerbstag ohne USDT/USD-Tagesschluss mit USDT = 1 gerechnet ([TaxExport.usdtApproximateOn]). */
    val usdtApproximate: Boolean,
    /**
     * Einstand je Coin in [unrealized] (Teil mit Kaufpreis) in der Anzeigewährung — für das Widget gemerkt
     * ([PortfolioDisplayFx]), das «Seit Kauf» damit zum aktuellen Kurs nachrechnet. Ohne die Coins in [fxMissingCoins].
     */
    val coinCosts: Map<String, BigDecimal> = emptyMap(),
)

/**
 * Einstand der offenen Bestände in der Anzeigewährung zum Kurs des Erwerbstags (Owner-Entscheid Okt. 2026) — reines
 * Kotlin, gespiegelt in `PortfolioCostFx.swift`:
 *  - Bisher rechnete die Karte auch den Einstand mit dem heutigen Faktor um; «Seit Kauf (CHF)» zeigte so nur die
 *    Bewegung in USDT. Jetzt: Wert heute × heutiger Faktor ([PortfolioStables.usdtTo], wie der Gesamtwert) − Σ Teil
 *    des Einstands × Faktor SEINES Tags. Der Faktor eines Tags folgt genau der Regel des Steuer-Exports
 *    ([TaxExport.rateOn]): Devisenkurs des Tags bzw. des letzten Tags davor (höchstens [TaxExport.FX_MAX_GAP_DAYS]
 *    Tage) × USDT/USD-Tagesschluss DES Tags (ohne Schluss × 1, [PortfolioCostFxFigures.usdtApproximate]). Ab heute
 *    gilt der heutige Faktor der Karte (wie der Wertverlauf, [PortfolioHistoryFx.rateFunction]).
 *  - Tage in UTC wie Wertverlauf und «7 T»/«1 J» ([PortfolioHistory]); die Steuer-CSV nimmt die Zeitzone der Vorlage.
 *  - Die Teile ([CoinPosition.pricedCostParts]) kommen aus der gewählten Einstandsmethode: FIFO (auch je Wallet) die
 *    offenen Posten mit Erwerb und zugeschlagenen Gebühren je zu ihrem Datum. Ø: jeder Zugang zum Kurs seines Tags,
 *    jeder Abgang nimmt alle Teile anteilig mit — gleich, als würde man den Ø-Einstand von Anfang an in der
 *    Anzeigewährung führen (dieselbe Rechnung wie der Ø-Einstand im Steuer-Export).
 *  - Fehlt für einen Teil der Devisenkurs, fehlt der ganze Coin (nie still der heutige Kurs) und die Zahl ist
 *    «unvollständig»; fehlt er bei allen, bleibt sie leer.
 *  - Prozent auf den Einstand in der Anzeigewährung derselben Coins.
 */
object PortfolioCostFx {

    private const val DAY_MILLIS = 86_400_000L

    /** UTC-Tag (Tage seit 1970) eines Zeitpunkts. */
    fun dayOf(time: Long): Long = Math.floorDiv(time, DAY_MILLIS)

    /** Frühester Erwerbstag (UTC) der offenen Teile mit Kaufpreis — ab hier braucht [figures] Kurse; null = keiner. */
    fun firstDay(summary: PortfolioSummary): Long? =
        summary.open.flatMap { it.pricedCostParts }.minOfOrNull { dayOf(it.time) }

    /**
     * Faktor USDT → [currency] am Tag [day]: ab [todayEpochDay] [currentRate], sonst [TaxExport.rateOn] mit [rates]
     * (Devisen-Tageskurse, Tag → Kurs) und [usdtUsd] (USDT/USD-Tagesschlüsse); null = kein Devisenkurs.
     */
    fun rateOn(
        day: Long,
        currency: String,
        currentRate: BigDecimal,
        todayEpochDay: Long,
        rates: Map<Long, BigDecimal>?,
        usdtUsd: Map<Long, BigDecimal>?,
    ): BigDecimal? =
        if (day >= todayEpochDay) currentRate
        else TaxExport.rateOn(rates, LocalDate.ofEpochDay(day), currency, usdtUsd)

    /**
     * Kennzahlen der Karte in [currency] ([PortfolioCostFxFigures]); [currentRate] = heutiger Faktor USDT → Währung der
     * Karte ([PortfolioStables.usdtTo]). Für USDT nicht gedacht (dort bleibt [PortfolioSummary] unverändert).
     */
    fun figures(
        summary: PortfolioSummary,
        currency: String,
        currentRate: BigDecimal,
        todayEpochDay: Long,
        rates: Map<Long, BigDecimal>?,
        usdtUsd: Map<Long, BigDecimal>?,
    ): PortfolioCostFxFigures {
        val mc = PortfolioDecimal.MC
        var usdtApproximate = false
        val missing = sortedSetOf<String>()
        /** Einstand des Teils mit Kaufpreis in der Währung; null = ein Kurs fehlt. */
        fun costTarget(p: CoinPosition): BigDecimal? {
            val cost = p.pricedCostExact ?: return null
            if (cost.signum() == 0) return BigDecimal.ZERO
            // Einstand ohne Herkunft (sollte nicht vorkommen): Datum unbekannt — nicht mit heute raten
            if (p.pricedCostParts.isEmpty()) return null
            var sum = BigDecimal.ZERO
            var partsSum = BigDecimal.ZERO
            var approximate = false
            for (part in p.pricedCostParts) {
                val day = dayOf(part.time)
                val r = rateOn(day, currency, currentRate, todayEpochDay, rates, usdtUsd) ?: return null
                if (day < todayEpochDay && TaxExport.usdtApproximateOn(usdtUsd, LocalDate.ofEpochDay(day))) approximate = true
                sum += part.usdt.multiply(r, mc)
                partsSum += part.usdt
            }
            if (approximate) usdtApproximate = true
            // Teile und Einstand gehen exakt auf; ein Rest aus Teilungen (34 Stellen) zählt anteilig mit
            return if (partsSum.signum() != 0 && partsSum.compareTo(cost) != 0) sum.multiply(cost).divide(partsSum, mc) else sum
        }
        val targets = HashMap<String, BigDecimal?>()
        fun target(p: CoinPosition): BigDecimal? = targets.getOrPut(p.coin) { costTarget(p).also { if (it == null) missing += p.coin } }

        // «Seit Kauf»: dieselben Coins wie die Summe (Teil mit Kaufpreis und Kurs)
        val included = summary.open.filter { it.unrealizedExact != null && it.pricedCostExact != null }
        var unrealized: BigDecimal? = null
        var includedCost = BigDecimal.ZERO
        var unrealizedFxMissing = false
        val coinCosts = LinkedHashMap<String, BigDecimal>()
        for (p in included) {
            val cost = target(p)
            if (cost == null) { unrealizedFxMissing = true; continue }
            coinCosts[p.coin] = cost
            val pricedValue = p.unrealizedExact!! + p.pricedCostExact!!
            unrealized = (unrealized ?: BigDecimal.ZERO) + pricedValue.multiply(currentRate, mc) - cost
            includedCost += cost
        }
        val percent = unrealized?.takeIf { includedCost.signum() > 0 }?.divide(includedCost, mc)?.toDouble()?.times(100.0)

        // Einstand: Coins ganz mit Kaufpreis, wie [PortfolioSummary.invested]
        var invested: BigDecimal? = null
        var investedFxMissing = false
        for (p in summary.open.filter { it.costBasis != null }) {
            val cost = target(p)
            if (cost == null) { investedFxMissing = true; continue }
            invested = (invested ?: BigDecimal.ZERO) + cost
        }
        return PortfolioCostFxFigures(
            unrealized = unrealized,
            unrealizedPercent = percent,
            invested = invested,
            unrealizedFxMissing = unrealizedFxMissing,
            investedFxMissing = investedFxMissing,
            fxMissingCoins = missing.toList(),
            usdtApproximate = usdtApproximate,
            coinCosts = coinCosts,
        )
    }
}
