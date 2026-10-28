package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

// UK: Share Pooling (gleicher Tag, 30 Tage, Section-104-Pool) — Regeln in [TaxEngines].

fun TaxEngines.ukPool(
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
        dayPools.getOrPut(d) { TaxPool() }.add(s.qty, s.cost, s.acquired, s.costEstimated)
        dayFirst[d] = minOf(dayFirst[d] ?: Long.MAX_VALUE, s.acquired)
        dayLeft[d] = (dayLeft[d] ?: BigDecimal.ZERO) + s.qty
        if (!moved(s)) dayOwn[d] = (dayOwn[d] ?: BigDecimal.ZERO) + s.qty
    }
    val disposes = steps.filter { it.kind == TaxStepKind.DISPOSE }
    val remaining = HashMap<TaxStep, BigDecimal>()
    val pieces = HashMap<TaxStep, ArrayList<TaxPiece>>()
    disposes.forEach { remaining[it] = it.qty; pieces[it] = ArrayList() }
    // Alle Abgänge eines Tags sind EINE Veräusserung (CRYPTO22200): jede Zuordnung (gleicher Tag, 30 Tage, Pool) gilt der
    // Gruppe und wird anteilig nach dem offenen Rest ihrer Abgänge verteilt — Menge [q], letzter Abgang mit Rest bekommt
    // den Rest (Summe exakt [q]); [take] nimmt je Abgang seinen Anteil
    fun split(group: List<TaxStep>, q: BigDecimal, take: (TaxStep, BigDecimal) -> Unit) {
        val total = group.fold(BigDecimal.ZERO) { a, s -> a + remaining.getValue(s) }
        if (q.signum() <= 0 || total.signum() <= 0) return
        val last = group.indexOfLast { remaining.getValue(it).signum() > 0 }
        var left = q
        group.forEachIndexed { i, s ->
            val r = remaining.getValue(s)
            if (r.signum() <= 0) return@forEachIndexed
            val want = if (i == last) left else q.multiply(r).divide(total, PortfolioDecimal.MC)
            left -= want
            take(s, want)
        }
    }
    fun openOf(group: List<TaxStep>): BigDecimal = group.fold(BigDecimal.ZERO) { a, s -> a + remaining.getValue(s) }
    // Gruppen je Tag, zeitlich (Abgänge sind zeitlich sortiert)
    val groups = disposes.groupBy { day(it.time, zone) }
    // 1. Gleicher Tag: die Gruppe gegen die Zugänge des Tags
    for ((d, group) in groups) {
        val pool = dayPools[d] ?: continue
        split(group, openOf(group).min(pool.held)) { s, want ->
            val taken = pool.take(want)
            remaining[s] = remaining.getValue(s) - taken.removed
            pieces.getValue(s) += piecesOf(taken, TaxMatch.SAME_DAY, dayFirst[d])
        }
    }
    // 2. 30 Tage: früheste Gruppe zuerst, Zugänge der 30 Tage danach früheste zuerst
    for ((d, group) in groups) {
        for ((ad, pool) in dayPools.subMap(d.plusDays(1), d.plusDays(TaxTemplate.UK_BB_DAYS + 1))) {
            val r = openOf(group)
            if (r.signum() <= 0) break
            if (pool.held.signum() <= 0) continue
            split(group, r.min(pool.held)) { s, want ->
                val taken = pool.take(want)
                remaining[s] = remaining.getValue(s) - taken.removed
                pieces.getValue(s) += piecesOf(taken, TaxMatch.THIRTY_DAY, dayFirst[ad])
            }
        }
    }
    // Der Pool-Teil einer Gruppe beim letzten Abgang des Tags (alle Buchungen bis dahin im Pool)
    val groupAt = groups.values.associateBy { it.last() }
    // Auszahlung zwischen zwei Abgängen derselben Gruppe: erst nach deren Pool-Teil gebucht (die Abgänge des Tags sind
    // EINE Veräusserung und werden zuerst zugeordnet) — sonst leerte sie den Pool vor dem früheren Abgang
    val index = steps.withIndex().associate { (i, s) -> s to i }
    val deferred = HashMap<TaxStep, ArrayList<TaxStep>>()
    for (s in steps) if (s.kind == TaxStepKind.REMOVE) {
        val group = groups[day(s.time, zone)] ?: continue
        val i = index.getValue(s)
        if (index.getValue(group.first()) < i && i < index.getValue(group.last())) deferred.getOrPut(group.last()) { ArrayList() } += s
    }
    val deferredSteps = deferred.values.flatten().toHashSet()
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
        pool.addCarried(taken.removedPriced, taken.parts, taken.estimated)
        pool.add(taken.removedUnpriced, null, dayFirst.getValue(d))
    }
    for (s in steps) {
        when (s.kind) {
            TaxStepKind.ACQUIRE -> {
                val d = day(s.acquired, zone)
                when {
                    late(s) -> pool.add(s.qty, s.cost, s.acquired, s.costEstimated)
                    moved(s) -> enter(d, s.qty)
                    added.add(d) -> enter(d, dayOwn.getValue(d))
                }
            }
            TaxStepKind.DISPOSE -> groupAt[s]?.let { group ->
                split(group, openOf(group).min(pool.held)) { m, want ->
                    val taken = pool.take(want)
                    remaining[m] = remaining.getValue(m) - taken.removed
                    pieces.getValue(m) += piecesOf(taken, TaxMatch.POOL)
                }
                deferred[s]?.forEach { pool.take(it.qty) }
                for (m in group) {
                    val list = pieces.getValue(m)
                    val rest = remaining.getValue(m)
                    if (rest.signum() > 0) list += TaxPiece(rest, null, uncovered = true)
                    // Erlös vor Gebühren mitführen: die Meldegrenze an HMRC prüft die Erlöse vor Abzug der Kosten
                    out += emit(coin, m, list, m.proceeds, gross = m.gross)
                }
            }
            TaxStepKind.REMOVE -> if (s !in deferredSteps) pool.take(s.qty)
            TaxStepKind.NETWORK_FEE -> lost.note(coin, s, pool.dropQty(s.qty))
            TaxStepKind.ADD_FEE -> unassigned.noteUnassigned(
                coin, s, pool.addFee(s.time, s.cost ?: BigDecimal.ZERO, s.trade.amount, s.trade.coinFee, s.costEstimated),
            )
        }
    }
    return out
}
