package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

// UK: Share Pooling (gleicher Tag, 30 Tage, Section-104-Pool) — Regeln in [TaxEngines].

internal fun TaxEngines.ukPool(
    coin: String,
    steps: List<TaxStep>,
    zone: ZoneId,
    lost: MutableList<PortfolioLostFeeCost>,
    unassigned: MutableList<PortfolioUnassignedFee>,
): List<PortfolioDisposal> {
    // Netzwerkgebühren sind hier schon eigene Verkäufe ([applyRules], HMRC CRYPTO22280) — sie zählen so in der
    // Same-Day-Gruppe ihres Tags und in der 30-Tage-Regel wie jeder andere Abgang
    // Einzahlung ohne Kaufdatum: eigene Coins von anderswo sind kein Erwerb an diesem Tag (CRYPTO22200 ordnet nur
    // Erwerbe zu) — direkt in den Pool, nicht «gleicher Tag»/«30 Tage»; sie dürfen keinen früheren Verkauf umleiten
    fun late(s: TaxStep): Boolean = s.trade.type == PortfolioTxType.DEPOSIT && s.trade.acquiredTime == null
    // Einzahlung mit früherem ursprünglichem Kaufdatum: Umbuchung zwischen eigenen Wallets ist kein Erwerb — erworben
    // wurde am Kaufdatum, also «gleicher Tag»/«30 Tage» nach dem Kaufdatum (Teil des Tages-Zugangs dieses Tags); den
    // Rest übernimmt der Pool erst am BUCHUNGSTAG (die App kennt die Coins erst ab da, siehe Schritt 3)
    fun moved(s: TaxStep): Boolean = day(s.acquired, zone) != day(s.time, zone)
    // Zugänge je Tag (Erwerbsdatum) als ein Zugang zum Durchschnitt
    val dayPools = sortedMapOf<LocalDate, TaxPool>()
    val dayFirst = HashMap<LocalDate, Long>()
    // Menge je Tag noch nicht im Pool (alle Zugänge) bzw. am Tag selbst gebucht (ohne nachgebuchte Einzahlungen)
    val dayLeft = HashMap<LocalDate, BigDecimal>()
    val dayOwn = HashMap<LocalDate, BigDecimal>()
    for (s in steps) if (s.kind == TaxStepKind.ACQUIRE && !late(s)) {
        val d = day(s.acquired, zone)
        dayPools.getOrPut(d) { TaxPool() }.add(s.qty, s.cost, s.acquired)
        dayFirst[d] = minOf(dayFirst[d] ?: Long.MAX_VALUE, s.acquired)
        dayLeft[d] = (dayLeft[d] ?: BigDecimal.ZERO) + s.qty
        if (!moved(s)) dayOwn[d] = (dayOwn[d] ?: BigDecimal.ZERO) + s.qty
    }
    val disposes = steps.filter { it.kind == TaxStepKind.DISPOSE }
    val remaining = HashMap<TaxStep, BigDecimal>()
    val pieces = HashMap<TaxStep, ArrayList<TaxPiece>>()
    disposes.forEach { remaining[it] = it.qty; pieces[it] = ArrayList() }
    // 1. Gleicher Tag: alle Abgänge des Tags gemeinsam gegen die Zugänge des Tags, anteilig nach Menge
    for ((d, group) in disposes.groupBy { day(it.time, zone) }) {
        val pool = dayPools[d] ?: continue
        val total = group.fold(BigDecimal.ZERO) { a, s -> a + s.qty }
        val matched = total.min(pool.held)
        if (matched.signum() <= 0) continue
        var left = matched
        group.forEachIndexed { i, s ->
            val want = if (i == group.size - 1) left else matched.multiply(s.qty).divide(total, PortfolioDecimal.MC)
            left -= want
            val taken = pool.take(want)
            remaining[s] = remaining.getValue(s) - taken.removed
            pieces.getValue(s) += piecesOf(taken, TaxMatch.SAME_DAY, dayFirst[d])
        }
    }
    // 2. 30 Tage: früheste Veräusserung zuerst, Zugänge der 30 Tage danach früheste zuerst
    for (s in disposes) {
        val d = day(s.time, zone)
        for ((ad, pool) in dayPools.subMap(d.plusDays(1), d.plusDays(TaxTemplate.UK_BB_DAYS + 1))) {
            val r = remaining.getValue(s)
            if (r.signum() <= 0) break
            if (pool.held.signum() <= 0) continue
            val taken = pool.take(r)
            remaining[s] = r - taken.removed
            pieces.getValue(s) += piecesOf(taken, TaxMatch.THIRTY_DAY, dayFirst[ad])
        }
    }
    // 3. Section-104-Pool: Rest der Tages-Zugänge am Tag des Zugangs (Anteil nachgebuchter Einzahlungen erst an ihrem
    // Buchungstag), Rest der Abgänge aus dem Pool
    val pool = TaxPool(zone)
    val added = HashSet<LocalDate>()
    val out = ArrayList<PortfolioDisposal>()
    // Anteil [q] der noch nicht übernommenen Menge des Tages [d] vom Rest seines Tages-Zugangs in den Pool (zuletzt: alles)
    fun enter(d: LocalDate, q: BigDecimal) {
        val dp = dayPools[d] ?: return
        val left = dayLeft.getValue(d)
        dayLeft[d] = left - q
        if (q >= left) {
            pool.absorb(dp)
            return
        }
        val taken = dp.take(dp.held.multiply(q).divide(left, PortfolioDecimal.MC))
        pool.addCarried(taken.removedPriced, taken.parts)
        pool.add(taken.removedUnpriced, null, dayFirst.getValue(d))
    }
    for (s in steps) {
        when (s.kind) {
            TaxStepKind.ACQUIRE -> {
                val d = day(s.acquired, zone)
                when {
                    late(s) -> pool.add(s.qty, s.cost, s.acquired)
                    moved(s) -> enter(d, s.qty)
                    added.add(d) -> enter(d, dayOwn.getValue(d))
                }
            }
            TaxStepKind.DISPOSE -> {
                val list = pieces.getValue(s)
                val r = remaining.getValue(s)
                if (r.signum() > 0) {
                    val taken = pool.take(r)
                    list += piecesOf(taken, TaxMatch.POOL)
                    val rest = r - taken.removed
                    if (rest.signum() > 0) list += TaxPiece(rest, null, uncovered = true)
                }
                // Erlös vor Gebühren mitführen: die Meldegrenze an HMRC prüft die Erlöse vor Abzug der Kosten
                out += emit(coin, s, list, s.proceeds, gross = s.gross)
            }
            TaxStepKind.REMOVE -> pool.take(s.qty)
            TaxStepKind.NETWORK_FEE -> lost.note(coin, s, pool.dropQty(s.qty))
            TaxStepKind.ADD_FEE -> unassigned.noteUnassigned(coin, s, pool.addFee(s.time, s.cost ?: BigDecimal.ZERO))
        }
    }
    return out
}
