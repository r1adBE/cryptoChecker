package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.time.ZoneId

// AT: Altbestand (FIFO) und Neubestand (gleitender Durchschnitt) je Coin und Wallet — Regeln in [TaxEngines].

/** Altbestand-Posten (FIFO); [estimated] = Einstand enthält einen geschätzten Wert ([PortfolioTrade.costEstimated]). */
private class AtLot(
    val txId: Long,
    val acquired: Long,
    val kind: PortfolioTxType,
    var qty: BigDecimal,
    var cost: BigDecimal?,
    var estimated: Boolean = false,
)

/** Topf eines Coins in EINER Wallet ([wallet] = Anzeigename, leer = ohne Angabe). */
private class AtCoin(val wallet: String, zone: ZoneId) {
    val old = ArrayList<AtLot>()
    val neu = TaxPool(zone)
    /**
     * Anschaffungsnebenkosten (Kaufgebühren) des Neubestands, Topf mit genau denselben Mengen wie [neu]: der gleitende
     * Durchschnitt bleibt ohne Gebühren (§ 27a Abs. 4 Z 2 EStG), ein Verkauf vor dem 1.3.2022 (alte Rechtslage, § 31 EStG
     * a. F.) nimmt den anteiligen Gebührenteil zum Einstand dazu. Gleiche Mengen in beiden Töpfen = gleiche Anteile bei
     * jedem Abgang (die Mengenrechnung von [TaxPool] hängt nicht vom Einstand ab).
     */
    val fees = TaxPool(zone)
    val oldHeld: BigDecimal get() = old.fold(BigDecimal.ZERO) { a, l -> a + l.qty }

    /** Altbestand-Posten nach Erwerb (dann Id) einsortieren (Umbuchung). */
    fun insertOld(lot: AtLot) {
        val i = old.indexOfFirst { it.acquired > lot.acquired || (it.acquired == lot.acquired && it.txId > lot.txId) }
        if (i < 0) old += lot else old.add(i, lot)
    }

    /**
     * Zugang im Neubestand mit Einstand ohne Gebühren [cost] und Kaufgebühr [fee] (nur mit Kaufpreis); [est] bzw. [feeEst]
     * = Einstand bzw. Kaufgebühr enthält einen geschätzten Wert.
     */
    fun addNew(qty: BigDecimal, cost: BigDecimal?, time: Long, fee: BigDecimal = BigDecimal.ZERO, est: Boolean = false, feeEst: Boolean = false) {
        neu.add(qty, cost, time, est)
        fees.add(qty, cost?.let { fee }, time, feeEst)
    }

    /** Zugang mit fortgeführtem Einstand [parts] samt Gebührenteil [feeParts] und deren Merkern «geschätzt». */
    fun addNewCarried(
        qty: BigDecimal,
        parts: List<PortfolioCostPart>,
        feeParts: List<PortfolioCostPart> = emptyList(),
        est: Boolean = false,
        feeEst: Boolean = false,
    ) {
        neu.addCarried(qty, parts, est)
        fees.addCarried(qty, feeParts, feeEst)
    }

    /** Abgang aus dem Neubestand: Einstand ohne Gebühren und der zugehörige Gebührenteil. */
    fun takeNew(qty: BigDecimal): Pair<TaxTaken, TaxTaken> = neu.take(qty) to fees.take(qty)

    /**
     * Menge sinkt, Einstand bleibt im Rest (Rückgabe wie [TaxPool.dropQty]). Ein Gebührenteil ohne Rest geht still verloren:
     * er zählt nur bei einem Verkauf vor dem 1.3.2022 und ist kein Einstand der Übersicht.
     */
    fun dropNew(qty: BigDecimal): BigDecimal {
        fees.dropQty(qty)
        return neu.dropQty(qty)
    }
}

/** Einstand nach alter Rechtslage (§ 31 EStG a. F.): Einstand ohne Gebühren [taken] plus Anschaffungsnebenkosten [fees]. */
private fun withFees(taken: TaxTaken, fees: TaxTaken): TaxTaken =
    if (fees.cost.signum() == 0) taken
    else TaxTaken(taken.removed, taken.removedPriced, taken.cost + fees.cost, taken.parts + fees.parts, taken.estimated || fees.estimated)

fun TaxEngines.atSplit(real: List<PortfolioTrade>, zone: ZoneId, lost: MutableList<PortfolioLostFeeCost>): List<PortfolioDisposal> {
    val zero = BigDecimal.ZERO
    val mc = PortfolioDecimal.MC
    val newFrom = TaxTemplate.AT_NEW_FROM.atStartOfDay(zone).toInstant().toEpochMilli()
    val neutralFrom = TaxTemplate.AT_SWAP_NEUTRAL_FROM.atStartOfDay(zone).toInstant().toEpochMilli()
    // Topf je (Coin, Wallet-Schlüssel wie FIFO je Wallet); Anzeigename = erste Schreibweise
    val coins = HashMap<Pair<String, String>, AtCoin>()
    val names = HashMap<String, String>()
    val out = ArrayList<PortfolioDisposal>()
    // Neubestand-Teil des letzten Tauschs (Trade-Id → was auf den erhaltenen Coin übergeht); [realized] = Tausch vor dem
    // 1.3.2022: ganz eine Veräusserung, der erhaltene Coin angeschafft zum Wert am Tag des Tauschs
    class Carry(val total: BigDecimal, val oldQty: BigDecimal, val taken: TaxTaken, val value: BigDecimal?, val realized: Boolean)
    val carries = HashMap<Long, Carry>()

    fun pot(coin: String, raw: String?): AtCoin {
        val key = PortfolioWallets.key(raw)
        val name = names.getOrPut(key) { raw?.trim().orEmpty() }
        return coins.getOrPut(coin to key) { AtCoin(name, zone) }
    }

    /** Nimmt höchstens [qty] aus dem Altbestand (FIFO, früher erworben zuerst). */
    fun takeOld(c: AtCoin, qty: BigDecimal): List<TaxPiece> {
        val pieces = ArrayList<TaxPiece>()
        var left = qty
        while (left.signum() > 0 && c.old.isNotEmpty()) {
            val lot = c.old[0]
            val q = left.min(lot.qty)
            val cost = lot.cost?.let { if (q.compareTo(lot.qty) == 0) it else it.multiply(q).divide(lot.qty, mc) }
            lot.qty -= q
            val lotCost = lot.cost
            if (cost != null && lotCost != null) lot.cost = lotCost - cost
            if (lot.qty.signum() <= 0) c.old.removeAt(0)
            left -= q
            pieces += TaxPiece(
                q, cost, acquiredTime = lot.acquired, lotTxId = lot.txId, lotKind = lot.kind, oldHolding = true, estimated = lot.estimated,
            )
        }
        return pieces
    }

    /** Nimmt [qty] zuerst aus dem Altbestand (FIFO), dann aus dem Neubestand (dritter Teil: Gebührenteil, [AtCoin.fees]). */
    fun takeBoth(c: AtCoin, qty: BigDecimal): Triple<List<TaxPiece>, TaxTaken, TaxTaken> {
        val pieces = takeOld(c, qty)
        val (taken, fees) = c.takeNew(qty - pieces.fold(zero) { a, p -> a + p.amount })
        return Triple(pieces, taken, fees)
    }

    /**
     * Anschaffungskosten eines Zugangs im Neubestand: Menge × Preis ohne Gebühren (§ 27a Abs. 4 Z 2 EStG: ohne
     * Anschaffungsnebenkosten; [s] hat schon die erhaltene Menge, Gebühr USDT bzw. im dritten Coin kommt nicht dazu).
     * Belohnung: 0 (BMF: Staking, Airdrop — § 27b Abs. 2 EStG); Zins ([PortfolioTrade.interestIncome]) zum Wert beim
     * Zufluss, denn er ist dort schon Einkunft (§ 27b Abs. 2 Z 1 EStG).
     */
    fun newCost(s: TaxStep): BigDecimal? =
        if (s.trade.type == PortfolioTxType.REWARD && !s.trade.interestIncome) zero else s.trade.validPrice?.let { s.qty.multiply(it, PortfolioDecimal.MC) }

    /**
     * Anschaffungsnebenkosten eines Zugangs im Neubestand: Einstand mit Gebühren ([TaxStep.cost]: Gebühr USDT, im Coin und
     * im dritten Coin) minus [newCost]. Nur für einen Verkauf vor dem 1.3.2022 (§ 31 EStG a. F. kennt sie); Belohnung
     * ohne Anschaffungskosten: keine.
     */
    fun newFee(s: TaxStep, cost: BigDecimal?): BigDecimal {
        val full = s.cost
        if (cost == null || cost.signum() == 0 || full == null) return zero
        return (full - cost).max(zero)
    }

    /** Zugang im Neubestand: Einstand ([newCost]) und Anschaffungsnebenkosten ([newFee]) mit ihren Merkern «geschätzt». */
    fun acquireNew(c: AtCoin, s: TaxStep) {
        val cost = newCost(s)
        val fee = newFee(s, cost)
        c.addNew(s.qty, cost, s.acquired, fee, s.trade.valueEstimated, s.trade.feePriceEstimated && fee.signum() != 0)
    }

    /**
     * Veräusserung ohne Tausch (Verkauf, Zahlung, Gebühr im dritten Coin). Altbestand (§ 31 EStG a. F.) mit Gebühren:
     * Erlös nach Gebühren, die Gebühr im Coin geht mit ihrem Einstand ab. Neubestand ohne Gebühren (§ 20 Abs. 2 EStG,
     * BMF): Erlös vor Gebühren; die Gebühr im Coin (anteilig nach Menge) ist keine Veräusserung — Menge sinkt, der
     * Einstand bleibt im Rest wie bei der Netzwerkgebühr einer Umbuchung. Verkauf von Neubestand vor dem 1.3.2022: noch
     * alte Rechtslage (§ 31 EStG a. F., Spekulationsgeschäft) wie der Tausch in dieser Zeit — Gebühren mindern den
     * Erlös, die Gebühr im Coin geht mit ihrem Einstand ab wie beim Altbestand, und der Einstand enthält die
     * Anschaffungsnebenkosten ([AtCoin.fees]); ab dem 1.3.2022 bleiben sie draussen (§ 27a Abs. 4 Z 2 EStG).
     */
    fun sale(coin: String, c: AtCoin, s: TaxStep): List<PortfolioDisposal> {
        val oldPieces = takeOld(c, s.qty)
        val oldQty = oldPieces.fold(zero) { a, p -> a + p.amount }
        val newQty = s.qty - oldQty
        val oldLaw = s.time < neutralFrom
        // Gebühr im dritten Coin: der ganze Schritt ist die Gebühr, darin steckt keine Gebühr im Coin
        val fee = if (s.feeLeg || oldLaw) zero else s.trade.coinFee
        val newFee = if (fee.signum() == 0 || newQty.signum() <= 0) zero else newQty.multiply(fee).divide(s.qty, mc)
        val (taken, fees) = c.takeNew(newQty - newFee)
        lost.note(coin, s, c.dropNew(newFee))
        fun oldShare(v: BigDecimal?): BigDecimal? = v?.let { if (oldQty.compareTo(s.qty) == 0) it else it.multiply(oldQty).divide(s.qty, mc) }
        val newPieces = ArrayList(piecesOf(if (oldLaw) withFees(taken, fees) else taken, oldHolding = false))
        val rest = newQty - newFee - taken.removed
        if (rest.signum() > 0) newPieces += TaxPiece(rest, null, uncovered = true)
        val inWallet: (TaxPiece, PortfolioDisposal) -> PortfolioDisposal = { _, d -> d.copy(wallet = c.wallet) }
        val newBase = if (oldLaw) s.proceeds else s.gross
        return emit(coin, s, oldPieces, oldShare(s.proceeds), extra = inWallet) +
            emit(coin, s, newPieces, newBase?.let { it - (oldShare(it) ?: zero) }, extra = inWallet)
    }

    /** Netzwerkgebühr: zuerst Altbestand (Einstand auf den nächsten Altbestand-Posten mit Kaufpreis), dann Neubestand. */
    fun networkFee(coin: String, c: AtCoin, s: TaxStep) {
        val fromOld = s.qty.min(c.oldHeld)
        if (fromOld.signum() > 0) {
            val carriedPieces = takeBoth(c, fromOld).first
            val carried = carriedPieces.fold(zero) { a, p -> a + (p.cost ?: zero) }
            val target = c.old.firstOrNull { it.cost != null }
            val targetCost = target?.cost
            if (target != null && targetCost != null) {
                target.cost = targetCost + carried
                if (carriedPieces.any { it.estimated && it.cost != null }) target.estimated = true
            } else {
                lost.note(coin, s, carried)
            }
        }
        lost.note(coin, s, c.dropNew(s.qty - fromOld))
    }

    for ((coin, e) in mergedEvents(real)) {
        val t = e.trade
        // Umbuchung: Gebühr in der Quelle, dann wandern Altbestand-Posten (FIFO) und Neubestand-Anteil zum Ziel
        if (!e.feeLeg && !e.swapIn && t.type == PortfolioTxType.TRANSFER) {
            val src = pot(coin, t.transferFrom)
            val dst = pot(coin, t.transferTo)
            for (s in steps(listOf(e))) when (s.kind) {
                TaxStepKind.NETWORK_FEE -> networkFee(coin, src, s)
                // Gebühren einer Umbuchung erhöhen den Einstand nicht
                TaxStepKind.ADD_FEE, TaxStepKind.ACQUIRE, TaxStepKind.DISPOSE, TaxStepKind.REMOVE -> Unit
            }
            if (src !== dst && t.amount.signum() > 0) {
                val (oldPieces, taken, fees) = takeBoth(src, t.amount)
                for (p in oldPieces) {
                    val lotTxId = p.lotTxId
                    val acquired = p.acquiredTime
                    val lotKind = p.lotKind
                    // Altbestand-Teile tragen immer ihren Posten; fehlt er doch, wandert die Menge ohne Kaufpreis
                    // (im Export unbewertet) statt den ganzen Bericht abzubrechen
                    dst.insertOld(
                        if (lotTxId != null && acquired != null && lotKind != null) AtLot(lotTxId, acquired, lotKind, p.amount, p.cost, p.estimated)
                        else AtLot(t.id, t.time, t.type, p.amount, null)
                    )
                }
                dst.addNewCarried(taken.removedPriced, taken.parts, fees.parts, taken.estimated, fees.estimated)
                dst.addNew(taken.removedUnpriced, null, t.time)
            }
            continue
        }
        val c = pot(coin, PortfolioWallets.of(e))
        for (s in steps(listOf(e))) when (s.kind) {
            TaxStepKind.ACQUIRE -> {
                val carry = if (s.swap) carries.remove(s.id) else null
                if (carry != null && carry.realized) {
                    // Tausch vor dem 1.3.2022 = Anschaffung zum Wert des Tauschs: vor dem 1.3.2021 Altbestand (Frist 1 Jahr ab
                    // dem Tausch), danach Neubestand
                    // Wert des Tauschs zum Tagesschluss: Einstand geschätzt
                    val est = s.trade.valueEstimated
                    if (s.time < newFrom) {
                        c.insertOld(AtLot(s.id, s.time, s.trade.type, s.qty, carry.value, est))
                    } else {
                        c.addNew(s.qty, carry.value, s.time, est = est)
                    }
                } else if (carry == null) {
                    // Nach Erwerb einsortieren: eine nachdatierte Einzahlung (Kaufdatum vor einem früher gebuchten Kauf)
                    // ist der ältere Posten und geht beim Verkauf zuerst ab
                    if (s.acquired < newFrom) c.insertOld(AtLot(s.id, s.acquired, s.trade.type, s.qty, s.cost, s.costEstimated))
                    else acquireNew(c, s)
                } else if (carry.total.signum() > 0) {
                    // Erhaltene Menge anteilig: aus Altbestand (Veräusserung, Einstand = Wert), aus Neubestand mit
                    // fortgeführtem Einstand, ohne Kaufpreis bzw. ungedeckt ohne Kaufpreis — Tausch ab dem 1.3.2022, also kein
                    // späterer Verkauf nach alter Rechtslage: ohne Gebührenteil
                    fun part(q: BigDecimal): BigDecimal = if (q.compareTo(carry.total) == 0) s.qty else s.qty.multiply(q).divide(carry.total, mc)
                    val fromOld = part(carry.oldQty)
                    if (fromOld.signum() > 0) {
                        val oldValue = carry.value?.let { v -> if (carry.oldQty.compareTo(carry.total) == 0) v else v.multiply(carry.oldQty).divide(carry.total, mc) }
                        c.addNew(fromOld, oldValue, s.time, est = s.trade.valueEstimated)
                    }
                    val fromPriced = part(carry.taken.removedPriced)
                    if (fromPriced.signum() > 0) c.addNewCarried(fromPriced, carry.taken.parts, est = carry.taken.estimated)
                    val restQty = s.qty - fromOld - fromPriced
                    if (restQty.signum() > 0) c.addNew(restQty, null, s.time)
                } else {
                    c.addNew(s.qty, null, s.time)
                }
            }
            TaxStepKind.DISPOSE -> if (!s.swap) out += sale(coin, c, s) else {
                val (oldPieces, taken, fees) = takeBoth(c, s.qty)
                val oldQty = oldPieces.fold(zero) { a, p -> a + p.amount }
                val inWallet: (TaxPiece, PortfolioDisposal) -> PortfolioDisposal = { _, d -> d.copy(wallet = c.wallet) }
                if (s.time < neutralFrom) {
                    // Tausch vor dem 1.3.2022: alte Rechtslage (§ 31 EStG a. F.) — Tausch = Veräusserung des ganzen
                    // abgegebenen Coins (Alt- und Neubestand, EStR Rz 6103k), der erhaltene Coin ist neu angeschafft;
                    // Einstand des Neubestands mit Anschaffungsnebenkosten wie beim Verkauf in dieser Zeit
                    val list = ArrayList<TaxPiece>(oldPieces)
                    list += piecesOf(withFees(taken, fees), oldHolding = false)
                    val rest = s.qty - oldQty - taken.removed
                    if (rest.signum() > 0) list += TaxPiece(rest, null, uncovered = true)
                    out += emit(coin, s, list, s.proceeds, extra = inWallet)
                    carries[s.id] = Carry(s.qty, oldQty, taken, s.gross, true)
                } else {
                    // Tausch ab 1.3.2022: nur der Altbestand-Teil ist eine Veräusserung (§ 31 EStG a. F., Rz 6103k); Neubestand
                    // geht steuerneutral über (§ 27b Abs. 3 Z 2 EStG) — auch in einen Stablecoin (USDT, USDC …): der
                    // Einstand geht mit, steuerbar erst der Verkauf gegen Euro
                    val proceedsOld = s.proceeds?.let { p -> if (oldQty.compareTo(s.qty) == 0) p else p.multiply(oldQty).divide(s.qty, mc) }
                    if (oldQty.signum() > 0) out += emit(coin, s, oldPieces, proceedsOld, extra = inWallet)
                    carries[s.id] = Carry(s.qty, oldQty, taken, s.gross, false)
                }
            }
            TaxStepKind.REMOVE -> takeBoth(c, s.qty)
            TaxStepKind.NETWORK_FEE -> networkFee(coin, c, s)
            TaxStepKind.ADD_FEE -> Unit
        }
    }
    return out
}
