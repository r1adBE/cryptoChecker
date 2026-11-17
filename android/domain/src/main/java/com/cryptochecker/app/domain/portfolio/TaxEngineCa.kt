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
private fun caLossAtSaleRate(row: PortfolioDisposal, fx: PortfolioPartWeight): BigDecimal? {
    val mc = PortfolioDecimal.MC
    val proceeds = row.proceedsUsdt ?: return null
    val cost = row.costUsdt ?: return null
    val rate = fx.of(row.sellTime)?.takeIf { it.signum() > 0 } ?: return null
    var costT = BigDecimal.ZERO
    if (row.costParts.isNotEmpty()) {
        // Σ Teil × Kurs seines Datums (grosse Ansichten des Pools ohne Auslesen, [weightedSum])
        costT = row.costParts.weightedSum(fx) ?: return null
    } else if (cost.signum() != 0) {
        costT = cost.multiply(rate, mc)
    }
    val gainT = proceeds.multiply(rate, mc) - costT
    return if (gainT.signum() >= 0) BigDecimal.ZERO else gainT.negate().divide(rate, mc)
}

/** Bestand nach jedem Schritt (nur Mengen) für «Bestand am Fensterende»; Teil von [caAcb]. */
private fun heldAfterSteps(steps: List<TaxStep>): List<BigDecimal> {
    val heldAfter = ArrayList<BigDecimal>(steps.size)
    var h = BigDecimal.ZERO
    for (s in steps) {
        h = when (s.kind) {
            TaxStepKind.ACQUIRE -> h + s.qty
            TaxStepKind.DISPOSE, TaxStepKind.REMOVE, TaxStepKind.NETWORK_FEE -> (h - s.qty).max(BigDecimal.ZERO)
            TaxStepKind.ADD_FEE -> h
        }
        heldAfter += h
    }
    return heldAfter
}

/** Erster Index in [days] mit Tag ≥ [d] (die Schritte sind zeitlich, also die Tage aufsteigend). */
private fun firstDayAtLeast(days: List<LocalDate>, d: LocalDate): Int {
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
private fun TaxEngines.replaces(s: TaxStep, from: LocalDate, to: LocalDate, zone: ZoneId): Boolean {
    if (s.trade.type == PortfolioTxType.DEPOSIT && s.trade.acquiredTime == null) return false
    val a = day(s.acquired, zone)
    return !a.isBefore(from) && !a.isAfter(to)
}

/** Ersatz-Zugänge (Indizes in [steps]) zwischen den Schritten [lo] und vor [afterTo], per Binärsuche in [acquires]. */
private fun TaxEngines.caWindow(
    steps: List<TaxStep>,
    acquires: List<Int>,
    lo: Int,
    afterTo: Int,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId,
): List<Int> {
    val window = ArrayList<Int>()
    var k = acquires.binarySearch(lo).let { if (it < 0) -it - 1 else it }
    while (k < acquires.size && acquires[k] < afterTo) {
        if (replaces(steps[acquires[k]], from, to, zone)) window += acquires[k]
        k++
    }
    return window
}

/** Ersatz verbrauchen (früheste zuerst) bis zur Menge [n]: (Zugang, Menge) je getroffenem Ersatz. */
private fun consumeReplacements(window: List<Int>, replaceLeft: MutableList<BigDecimal>, n: BigDecimal): List<Pair<Int, BigDecimal>> {
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
    return matched
}

/**
 * Nicht abziehbarer Teil (superficial loss) des Verlusts einer Zeile [row] des Verkaufs [s], anteilig zur Ersatz-Menge
 * [n]; null = keiner. Verlust in der Steuerwährung entscheiden (Schenkung an Partner: kein Verlust, ITA 73(1)) (CAD:
 * gemeldet wird in CAD — ein USDT-Verlust kann in CAD ein Gewinn sein); ohne Kurse in USDT. Teil von [caAcb].
 */
private fun caDeniedPart(row: PortfolioDisposal, s: TaxStep, n: BigDecimal, fxWeight: PortfolioPartWeight?): BigDecimal? {
    val g = row.gainUsdt.takeUnless { s.spouseGift } ?: return null
    val loss = fxWeight?.let { caLossAtSaleRate(row, it) } ?: g.negate().max(BigDecimal.ZERO)
    if (loss.signum() <= 0 || row.costUsdt == null || s.qty.signum() <= 0) return null
    val part = if (n.compareTo(s.qty) == 0) loss else loss.multiply(n).divide(s.qty, PortfolioDecimal.MC)
    return part.takeIf { it.signum() > 0 }
}

fun TaxEngines.caAcb(
    coin: String,
    steps: List<TaxStep>,
    zone: ZoneId,
    lost: MutableList<PortfolioLostFeeCost>,
    unassigned: MutableList<PortfolioUnassignedFee>,
    fx: ((Long) -> BigDecimal?)? = null,
): List<PortfolioDisposal> {
    val zero = BigDecimal.ZERO
    // Ein Gewicht für alle Zeilen (die laufende Summe je Pool wird so nur einmal gerechnet)
    val fxWeight = fx?.let(::PortfolioPartWeight)
    val days = steps.map { day(it.time, zone) }
    // Bestand nach jedem Schritt (nur Mengen) für «Bestand am Fensterende»
    val heldAfter = heldAfterSteps(steps)
    val pool = TaxPool(zone)
    val pending = ArrayList<PortfolioCostPart>()
    // Nicht abziehbarer Verlust aus einer Zeile mit geschätztem Wert: der ACB enthält dann einen geschätzten Teil
    var pendingEstimated = false
    val pendingAtEstimated = HashSet<Int>()
    // Ersatz-Menge je Zugang: deckt nur einen nicht abziehbaren Verlust (wird verbraucht)
    val replaceLeft = steps.map { if (it.kind == TaxStepKind.ACQUIRE) it.qty else zero }.toMutableList()
    // Nicht abziehbarer Verlust (Herkunft: Verkauf) für einen Ersatz NACH dem Verkauf: kommt bei diesem Zugang zum ACB
    val pendingAt = HashMap<Int, ArrayList<PortfolioCostPart>>()
    val out = ArrayList<PortfolioDisposal>()
    // Zugänge (Indizes, zeitlich): das Fenster per Binärsuche statt über alle Schritte je Verkauf
    val acquires = steps.indices.filter { steps[it].kind == TaxStepKind.ACQUIRE }

    /**
     * Nicht abziehbarer Betrag zum ACB des Bestands (ohne Teil mit Kaufpreis: zum nächsten Zugang mit Kaufpreis); [est] =
     * aus einer Zeile mit geschätztem Wert.
     */
    fun toAcb(time: Long, amount: BigDecimal, est: Boolean) {
        if (amount.signum() <= 0) return
        if (pool.priced.signum() > 0) return pool.addCost(time, amount, est)
        pending += PortfolioCostPart(time, amount)
        if (est) pendingEstimated = true
    }
    steps.forEachIndexed { i, s ->
        when (s.kind) {
            TaxStepKind.ACQUIRE -> {
                pool.add(s.qty, s.cost, s.acquired, s.costEstimated)
                // Ersatz dieses Zugangs für einen früheren Verkauf: dessen nicht abziehbarer Verlust zum ACB
                val atEstimated = pendingAtEstimated.remove(i)
                pendingAt.remove(i)?.forEach { toAcb(it.time, it.usdt, atEstimated) }
                // Nicht abziehbarer Verlust ohne Bestand: zum nächsten Zugang mit Kaufpreis
                if (s.cost != null && pending.isNotEmpty()) {
                    pending.forEach { pool.addCost(it.time, it.usdt, pendingEstimated) }
                    pending.clear()
                    pendingEstimated = false
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
                // Unverbrauchte Ersatz-Zugänge im Fenster und Bestand am Fensterende (Binärsuche über aufsteigende Tage)
                val lo = firstDayAtLeast(days, from)
                val afterTo = firstDayAtLeast(days, to.plusDays(1))
                val endIndex = maxOf(i, afterTo - 1)
                val window = caWindow(steps, acquires, lo, afterTo, from, to, zone)
                val bought = window.fold(zero) { a, j -> a + replaceLeft[j] }
                val heldEnd = heldAfter[endIndex]
                val n = s.qty.min(bought).min(heldEnd).max(zero)
                var denied = zero
                var deniedEstimated = false
                val rows = emit(coin, s, list, s.proceeds) { _, row ->
                    val part = caDeniedPart(row, s, n, fxWeight) ?: return@emit row
                    denied += part
                    if (row.valueEstimated) deniedEstimated = true
                    row.copy(adjustmentUsdt = part)
                }
                out += rows
                if (denied.signum() > 0 && n.signum() > 0) {
                    // Ersatz verbrauchen (früheste zuerst); Betrag anteilig zum ACB dieses Ersatzes
                    val matched = consumeReplacements(window, replaceLeft, n)
                    var left = denied
                    matched.forEachIndexed { k, (j, q) ->
                        val part = if (k == matched.size - 1) left else denied.multiply(q).divide(n, PortfolioDecimal.MC)
                        left -= part
                        if (j <= i) return@forEachIndexed toAcb(s.time, part, deniedEstimated)
                        pendingAt.getOrPut(j) { ArrayList() } += PortfolioCostPart(s.time, part)
                        if (deniedEstimated) pendingAtEstimated += j
                    }
                }
            }
            TaxStepKind.REMOVE -> pool.take(s.qty)
            TaxStepKind.NETWORK_FEE -> lost.note(coin, s, pool.dropQty(s.qty))
            TaxStepKind.ADD_FEE -> unassigned.noteUnassigned(
                coin,
                s,
                pool.addFee(s.time, s.cost ?: zero, s.trade.amount, s.trade.coinFee, s.costEstimated)
            )
        }
    }
    return out
}
