package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

// CA: Adjusted Cost Base mit Superficial Loss — Regeln in [TaxEngines].

/**
 * CA: Verlust einer Zeile [row] in der Steuerwährung (CAD) — Erlös zum Kurs des Verkaufs, Einstand je Herkunftsteil
 * zum Kurs seines Datums (wie der Export); Rückgabe = Verlust als USDT-Betrag zum Kurs des Verkaufs (der Export
 * rechnet ihn so exakt zurück), 0 = kein Verlust in CAD; null = ein Kurs fehlt.
 */
private fun caLossAtSaleRate(row: PortfolioDisposal, fx: (Long) -> BigDecimal?): BigDecimal? {
    val mc = PortfolioDecimal.MC
    val proceeds = row.proceedsUsdt ?: return null
    val cost = row.costUsdt ?: return null
    val rate = fx(row.sellTime)?.takeIf { it.signum() > 0 } ?: return null
    var costT = BigDecimal.ZERO
    if (row.costParts.isNotEmpty()) {
        for (p in row.costParts) costT += p.usdt.multiply(fx(p.time) ?: return null, mc)
    } else if (cost.signum() != 0) {
        costT = cost.multiply(rate, mc)
    }
    val gainT = proceeds.multiply(rate, mc) - costT
    return if (gainT.signum() >= 0) BigDecimal.ZERO else gainT.negate().divide(rate, mc)
}

internal fun TaxEngines.caAcb(
    coin: String,
    steps: List<TaxStep>,
    zone: ZoneId,
    lost: MutableList<PortfolioLostFeeCost>,
    unassigned: MutableList<PortfolioUnassignedFee>,
    fx: ((Long) -> BigDecimal?)? = null,
): List<PortfolioDisposal> {
    val zero = BigDecimal.ZERO
    val days = steps.map { day(it.time, zone) }
    // Bestand nach jedem Schritt (nur Mengen) für «Bestand am Fensterende»
    val heldAfter = ArrayList<BigDecimal>(steps.size)
    var h = zero
    for (s in steps) {
        h = when (s.kind) {
            TaxStepKind.ACQUIRE -> h + s.qty
            TaxStepKind.DISPOSE, TaxStepKind.REMOVE, TaxStepKind.NETWORK_FEE -> (h - s.qty).max(zero)
            TaxStepKind.ADD_FEE -> h
        }
        heldAfter += h
    }
    val pool = TaxPool(zone)
    val pending = ArrayList<PortfolioCostPart>()
    // Ersatz-Menge je Zugang: deckt nur einen nicht abziehbaren Verlust (wird verbraucht)
    val replaceLeft = steps.map { if (it.kind == TaxStepKind.ACQUIRE) it.qty else zero }.toMutableList()
    // Nicht abziehbarer Verlust (Herkunft: Verkauf) für einen Ersatz NACH dem Verkauf: kommt bei diesem Zugang zum ACB
    val pendingAt = HashMap<Int, ArrayList<PortfolioCostPart>>()
    val out = ArrayList<PortfolioDisposal>()
    // Zugänge (Indizes, zeitlich): das Fenster per Binärsuche statt über alle Schritte je Verkauf
    val acquires = steps.indices.filter { steps[it].kind == TaxStepKind.ACQUIRE }
    /** Erster Index in [days] mit Tag ≥ [d] (die Schritte sind zeitlich, also die Tage aufsteigend). */
    fun firstDayAtLeast(d: LocalDate): Int {
        var lo = 0
        var hi = days.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (days[mid].isBefore(d)) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Ersatzkauf (superficial loss) nur ein Erwerb im Fenster: Einzahlung ohne Kaufdatum = eigene Coins von anderswo, nie
     * (wie UK `late`); sonst zählt das Erwerbsdatum (Einzahlung mit früherem Kaufdatum ausserhalb des Fensters: keiner).
     */
    fun replaces(s: TaxStep, from: LocalDate, to: LocalDate): Boolean {
        if (s.trade.type == PortfolioTxType.DEPOSIT && s.trade.acquiredTime == null) return false
        val a = day(s.acquired, zone)
        return !a.isBefore(from) && !a.isAfter(to)
    }

    /** Nicht abziehbarer Betrag zum ACB des Bestands (ohne Teil mit Kaufpreis: zum nächsten Zugang mit Kaufpreis). */
    fun toAcb(time: Long, amount: BigDecimal) {
        if (amount.signum() <= 0) return
        if (pool.priced.signum() > 0) pool.addCost(time, amount) else pending += PortfolioCostPart(time, amount)
    }
    steps.forEachIndexed { i, s ->
        when (s.kind) {
            TaxStepKind.ACQUIRE -> {
                pool.add(s.qty, s.cost, s.acquired)
                // Ersatz dieses Zugangs für einen früheren Verkauf: dessen nicht abziehbarer Verlust zum ACB
                pendingAt.remove(i)?.forEach { toAcb(it.time, it.usdt) }
                // Nicht abziehbarer Verlust ohne Bestand: zum nächsten Zugang mit Kaufpreis
                if (s.cost != null && pending.isNotEmpty()) {
                    pending.forEach { pool.addCost(it.time, it.usdt) }
                    pending.clear()
                }
            }
            TaxStepKind.DISPOSE -> {
                val taken = pool.take(s.qty)
                val list = ArrayList(piecesOf(taken))
                val rest = s.qty - taken.removed
                if (rest.signum() > 0) list += TaxPiece(rest, null, uncovered = true)
                val d = days[i]
                val from = d.minusDays(TaxTemplate.CA_WINDOW_DAYS)
                val to = d.plusDays(TaxTemplate.CA_WINDOW_DAYS)
                // Noch nicht verbrauchte Ersatz-Zugänge im Fenster und Bestand am Ende des Fensters (Binärsuche über die
                // aufsteigenden Tage: gleiche Indizes wie der frühere Durchlauf über alle Schritte)
                val lo = firstDayAtLeast(from)
                val afterTo = firstDayAtLeast(to.plusDays(1))
                val endIndex = maxOf(i, afterTo - 1)
                val window = ArrayList<Int>()
                var k = acquires.binarySearch(lo).let { if (it < 0) -it - 1 else it }
                while (k < acquires.size && acquires[k] < afterTo) {
                    if (replaces(steps[acquires[k]], from, to)) window += acquires[k]
                    k++
                }
                val bought = window.fold(zero) { a, j -> a + replaceLeft[j] }
                val heldEnd = heldAfter[endIndex]
                val n = s.qty.min(bought).min(heldEnd).max(zero)
                var denied = zero
                val rows = emit(coin, s, list, s.proceeds) { _, row ->
                    val g = row.gainUsdt.takeUnless { s.spouseGift }
                    // Verlust in der Steuerwährung entscheiden (Schenkung an Partner: kein Verlust, ITA 73(1)) (CAD: gemeldet wird in CAD — ein USDT-Verlust kann in CAD
                    // ein Gewinn sein); ohne Kurse in USDT
                    val loss = if (g == null) null else fx?.let { caLossAtSaleRate(row, it) } ?: g.negate().max(zero)
                    if (loss != null && loss.signum() > 0 && row.costUsdt != null && s.qty.signum() > 0) {
                        val part = if (n.compareTo(s.qty) == 0) loss else loss.multiply(n).divide(s.qty, PortfolioDecimal.MC)
                        if (part.signum() > 0) {
                            denied += part
                            return@emit row.copy(adjustmentUsdt = part)
                        }
                    }
                    row
                }
                out += rows
                if (denied.signum() > 0 && n.signum() > 0) {
                    // Ersatz verbrauchen (früheste zuerst); Betrag anteilig zum ACB dieses Ersatzes
                    var need = n
                    val matched = ArrayList<Pair<Int, BigDecimal>>()
                    for (j in window) {
                        if (need.signum() <= 0) break
                        val q = replaceLeft[j].min(need)
                        if (q.signum() <= 0) continue
                        replaceLeft[j] = replaceLeft[j] - q
                        need -= q
                        matched += j to q
                    }
                    var left = denied
                    matched.forEachIndexed { k, (j, q) ->
                        val part = if (k == matched.size - 1) left else denied.multiply(q).divide(n, PortfolioDecimal.MC)
                        left -= part
                        if (j > i) pendingAt.getOrPut(j) { ArrayList() } += PortfolioCostPart(s.time, part) else toAcb(s.time, part)
                    }
                }
            }
            TaxStepKind.REMOVE -> pool.take(s.qty)
            TaxStepKind.NETWORK_FEE -> lost.note(coin, s, pool.dropQty(s.qty))
            TaxStepKind.ADD_FEE -> unassigned.noteUnassigned(coin, s, pool.addFee(s.time, s.cost ?: zero))
        }
    }
    return out
}
