package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Referenz für [PortfolioCostPartPoolTest]: [TaxPool] mit der früheren Führung der Herkunftsteile — eine Liste, die jeder
 * Abgang Teil für Teil neu schreibt (Zugänge × Abgänge). Sonst Zeile für Zeile wie [TaxPool].
 */
internal class TaxPoolReference(
    /**
     * Zeitzone der Kalendertage: Herkunftsteile desselben Tags werden zusammengefasst. null = jeder Teil einzeln.
     */
    private val zone: ZoneId? = null,
) {
    var priced: BigDecimal = BigDecimal.ZERO
        private set
    var unpriced: BigDecimal = BigDecimal.ZERO
        private set
    var cost: BigDecimal = BigDecimal.ZERO
        private set
    val parts = ArrayList<PortfolioCostPart>()
    /** Mit [zone]: Tag → Index in [parts] (erster Zeitpunkt des Tags bleibt). */
    private val dayIndex = HashMap<LocalDate, Int>()
    val held: BigDecimal get() = priced + unpriced

    /** Zugang [qty] mit Einstand [cost] (null = ohne Kaufpreis), Herkunft [time]. */
    fun add(qty: BigDecimal, cost: BigDecimal?, time: Long) {
        if (qty.signum() <= 0) return
        if (cost == null) {
            unpriced += qty
            return
        }
        priced += qty
        addCost(time, cost)
    }

    /** Zugang mit fortgeführtem Einstand (Herkunftsteile [from]). */
    fun addCarried(qty: BigDecimal, from: List<PortfolioCostPart>) {
        if (qty.signum() <= 0) return
        priced += qty
        from.forEach { addCost(it.time, it.usdt) }
    }

    /** Einstand direkt zum Teil mit Kaufpreis (z. B. nicht abziehbarer Verlust). */
    fun addCost(time: Long, usdt: BigDecimal) {
        cost += usdt
        if (usdt.signum() == 0) return
        val z = zone
        if (z == null) {
            parts += PortfolioCostPart(time, usdt)
            return
        }
        val day = Instant.ofEpochMilli(time).atZone(z).toLocalDate()
        val i = dayIndex[day]
        if (i != null) {
            parts[i] = PortfolioCostPart(parts[i].time, parts[i].usdt + usdt)
        } else {
            dayIndex[day] = parts.size
            parts += PortfolioCostPart(time, usdt)
        }
    }

    /** Alle Herkunftsteile entfernen (samt Tag-Index). */
    private fun clearParts() {
        parts.clear()
        dayIndex.clear()
    }

    /**
     * Gebühr [extra] einer Umbuchung der Menge [amount] mit Netzwerkgebühr [coinFee] (vor dem Schritt
     * [TaxStepKind.NETWORK_FEE]), wie der Ø des Portfolios: zuerst der Anteil der nicht gedeckten Menge (gedeckt =
     * Bestand − Netzwerkgebühr, wie FIFO je Wallet, [PortfolioFeeExpenses.uncoveredShare]; leerer Pool: die ganze Gebühr),
     * der Rest anteilig (Menge mit Kaufpreis / Bestand) zum Einstand. Rückgabe = nicht gedeckter Anteil + Anteil des Teils
     * ohne Kaufpreis (ohne Teil mit Kaufpreis: der ganze Rest) — der Aufrufer vermerkt ihn als [PortfolioUnassignedFee];
     * 0 = alles zugeordnet.
     */
    fun addFee(time: Long, extra: BigDecimal, amount: BigDecimal, coinFee: BigDecimal): BigDecimal {
        if (extra.signum() <= 0) return BigDecimal.ZERO
        val h = held
        val rest = extra - PortfolioFeeExpenses.uncoveredShare(extra, amount, (h - coinFee).max(BigDecimal.ZERO))
        if (rest.signum() <= 0 || priced.signum() <= 0) return extra
        val assigned = if (unpriced.signum() == 0) rest else rest.multiply(priced).divide(h, PortfolioDecimal.MC)
        addCost(time, assigned)
        return extra - assigned
    }

    /** Übernimmt den ganzen Inhalt von [other] (danach leer). */
    fun absorb(other: TaxPoolReference) {
        priced += other.priced
        unpriced += other.unpriced
        // Einstand unverändert, Herkunft je Teil (mit [zone] nach Tag zusammengefasst)
        val c = cost
        other.parts.forEach { addCost(it.time, it.usdt) }
        cost = c + other.cost
        other.priced = BigDecimal.ZERO
        other.unpriced = BigDecimal.ZERO
        other.cost = BigDecimal.ZERO
        other.clearParts()
    }

    /** Gibt [requested] ab (gekappt auf den Bestand), beide Teile anteilig samt Einstand. */
    fun take(requested: BigDecimal): TaxTaken {
        val zero = BigDecimal.ZERO
        val mc = PortfolioDecimal.MC
        val h = held
        val removed = requested.min(h).max(zero)
        if (removed.signum() <= 0) return TaxTaken(zero, zero, zero, emptyList())
        val allPriced = unpriced.signum() == 0
        val removedPriced = if (allPriced) removed else PortfolioDecimal.amount(removed.multiply(priced).divide(h, mc)).min(priced)
        val costRemoved = when {
            priced.signum() == 0 -> zero
            removedPriced >= priced -> cost
            else -> cost.multiply(removedPriced).divide(priced, mc)
        }
        val partsRemoved = takeParts(costRemoved)
        val rest = h - removed
        priced -= removedPriced
        cost -= costRemoved
        if (PortfolioDecimal.isDust(priced) || priced.signum() < 0) { priced = zero; cost = zero; clearParts() }
        unpriced = (rest - priced).max(zero)
        if (PortfolioDecimal.isDust(unpriced)) unpriced = zero
        return TaxTaken(removed, removedPriced, costRemoved, partsRemoved)
    }

    /**
     * Netzwerkgebühr: Menge sinkt anteilig, der Einstand bleibt im Rest. Bleibt kein Teil mit Kaufpreis, geht der
     * Einstand nicht still verloren: Rückgabe = dieser Einstand (Aufrufer vermerkt [PortfolioLostFeeCost]), sonst 0.
     */
    fun dropQty(qty: BigDecimal): BigDecimal {
        val h = held
        if (qty.signum() <= 0 || h.signum() <= 0) return BigDecimal.ZERO
        val removed = qty.min(h)
        val removedPriced = if (unpriced.signum() == 0) removed
            else PortfolioDecimal.amount(removed.multiply(priced).divide(h, PortfolioDecimal.MC)).min(priced)
        priced -= removedPriced
        var lost = BigDecimal.ZERO
        if (PortfolioDecimal.isDust(priced) || priced.signum() < 0) {
            lost = cost
            priced = BigDecimal.ZERO; cost = BigDecimal.ZERO; clearParts()
        }
        unpriced = (h - removed - priced).max(BigDecimal.ZERO)
        if (PortfolioDecimal.isDust(unpriced)) unpriced = BigDecimal.ZERO
        return lost
    }

    /** Nimmt [removed] Einstand anteilig aus allen Herkunftsteilen; Summe exakt [removed]. */
    private fun takeParts(removed: BigDecimal): List<PortfolioCostPart> {
        if (removed.signum() == 0 || parts.isEmpty()) return emptyList()
        if (removed >= cost) {
            val all = parts.toList()
            clearParts()
            return all
        }
        val out = ArrayList<PortfolioCostPart>(parts.size)
        var left = removed
        for (i in parts.indices) {
            val p = parts[i]
            val r = if (i == parts.size - 1) left else p.usdt.multiply(removed).divide(cost, PortfolioDecimal.MC)
            left -= r
            out += PortfolioCostPart(p.time, r)
            parts[i] = PortfolioCostPart(p.time, p.usdt - r)
        }
        return out
    }
}
