package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.time.ZoneId

// AT: Altbestand (FIFO) und Neubestand (gleitender Durchschnitt) je Coin und Wallet — Regeln in [TaxEngines].

/** Altbestand-Posten (FIFO). */
private class AtLot(val txId: Long, val acquired: Long, val kind: PortfolioTxType, var qty: BigDecimal, var cost: BigDecimal?)

/** Topf eines Coins in EINER Wallet ([wallet] = Anzeigename, leer = ohne Angabe). */
private class AtCoin(val wallet: String, zone: ZoneId) {
    val old = ArrayList<AtLot>()
    val neu = TaxPool(zone)
    val oldHeld: BigDecimal get() = old.fold(BigDecimal.ZERO) { a, l -> a + l.qty }

    /** Altbestand-Posten nach Erwerb (dann Id) einsortieren (Umbuchung). */
    fun insertOld(lot: AtLot) {
        val i = old.indexOfFirst { it.acquired > lot.acquired || (it.acquired == lot.acquired && it.txId > lot.txId) }
        if (i < 0) old += lot else old.add(i, lot)
    }
}

internal fun TaxEngines.atSplit(real: List<PortfolioTrade>, zone: ZoneId, lost: MutableList<PortfolioLostFeeCost>): List<PortfolioDisposal> {
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
            if (cost != null) lot.cost = lot.cost!! - cost
            if (lot.qty.signum() <= 0) c.old.removeAt(0)
            left -= q
            pieces += TaxPiece(q, cost, acquiredTime = lot.acquired, lotTxId = lot.txId, lotKind = lot.kind, oldHolding = true)
        }
        return pieces
    }

    /** Nimmt [qty] zuerst aus dem Altbestand (FIFO), dann aus dem Neubestand. */
    fun takeBoth(c: AtCoin, qty: BigDecimal): Pair<List<TaxPiece>, TaxTaken> {
        val pieces = takeOld(c, qty)
        val taken = c.neu.take(qty - pieces.fold(zero) { a, p -> a + p.amount })
        return pieces to taken
    }

    /**
     * Anschaffungskosten eines Zugangs im Neubestand: Menge × Preis ohne Gebühren (§ 27a Abs. 4 Z 2 EStG: ohne
     * Anschaffungsnebenkosten; [s] hat schon die erhaltene Menge, Gebühr USDT bzw. im dritten Coin kommt nicht dazu).
     * Belohnung: 0 (BMF: Staking, Airdrop — § 27b Abs. 2 EStG); Zins ([PortfolioTrade.interestIncome]) zum Wert beim
     * Zufluss, denn er ist dort schon Einkunft (§ 27b Abs. 2 Z 1 EStG).
     */
    fun newCost(s: TaxStep): BigDecimal? =
        if (s.trade.type == PortfolioTxType.REWARD && !s.trade.interestIncome) zero else s.trade.validPrice?.let { s.qty * it }

    /**
     * Veräusserung ohne Tausch (Verkauf, Zahlung, Gebühr im dritten Coin). Altbestand (§ 31 EStG a. F.) mit Gebühren:
     * Erlös nach Gebühren, die Gebühr im Coin geht mit ihrem Einstand ab. Neubestand ohne Gebühren (§ 20 Abs. 2 EStG,
     * BMF): Erlös vor Gebühren; die Gebühr im Coin (anteilig nach Menge) ist keine Veräusserung — Menge sinkt, der
     * Einstand bleibt im Rest wie bei der Netzwerkgebühr einer Umbuchung.
     */
    fun sale(coin: String, c: AtCoin, s: TaxStep): List<PortfolioDisposal> {
        val oldPieces = takeOld(c, s.qty)
        val oldQty = oldPieces.fold(zero) { a, p -> a + p.amount }
        val newQty = s.qty - oldQty
        // Gebühr im dritten Coin: der ganze Schritt ist die Gebühr, darin steckt keine Gebühr im Coin
        val fee = if (s.feeLeg) zero else s.trade.coinFee
        val newFee = if (fee.signum() == 0 || newQty.signum() <= 0) zero else newQty.multiply(fee).divide(s.qty, mc)
        val taken = c.neu.take(newQty - newFee)
        lost.note(coin, s, c.neu.dropQty(newFee))
        fun oldShare(v: BigDecimal?): BigDecimal? = v?.let { if (oldQty.compareTo(s.qty) == 0) it else it.multiply(oldQty).divide(s.qty, mc) }
        val newPieces = ArrayList(piecesOf(taken, oldHolding = false))
        val rest = newQty - newFee - taken.removed
        if (rest.signum() > 0) newPieces += TaxPiece(rest, null, uncovered = true)
        val inWallet: (TaxPiece, PortfolioDisposal) -> PortfolioDisposal = { _, d -> d.copy(wallet = c.wallet) }
        return emit(coin, s, oldPieces, oldShare(s.proceeds), inWallet) +
            emit(coin, s, newPieces, s.gross?.let { it - oldShare(it)!! }, inWallet)
    }

    /** Netzwerkgebühr: zuerst Altbestand (Einstand auf den nächsten Altbestand-Posten mit Kaufpreis), dann Neubestand. */
    fun networkFee(coin: String, c: AtCoin, s: TaxStep) {
        val fromOld = s.qty.min(c.oldHeld)
        if (fromOld.signum() > 0) {
            val carried = takeBoth(c, fromOld).first.fold(zero) { a, p -> a + (p.cost ?: zero) }
            val target = c.old.firstOrNull { it.cost != null }
            if (target != null) target.cost = target.cost!! + carried else lost.note(coin, s, carried)
        }
        lost.note(coin, s, c.neu.dropQty(s.qty - fromOld))
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
                val (oldPieces, taken) = takeBoth(src, t.amount)
                for (p in oldPieces) dst.insertOld(AtLot(p.lotTxId!!, p.acquiredTime!!, p.lotKind!!, p.amount, p.cost))
                dst.neu.addCarried(taken.removedPriced, taken.parts)
                dst.neu.add(taken.removedUnpriced, null, t.time)
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
                    if (s.time < newFrom) {
                        c.insertOld(AtLot(s.id, s.time, s.trade.type, s.qty, carry.value))
                    } else {
                        c.neu.add(s.qty, carry.value, s.time)
                    }
                } else if (carry == null) {
                    if (s.acquired < newFrom) c.old += AtLot(s.id, s.acquired, s.trade.type, s.qty, s.cost)
                    else c.neu.add(s.qty, newCost(s), s.acquired)
                } else if (carry.total.signum() > 0) {
                    // Erhaltene Menge anteilig: aus Altbestand (Veräusserung, Einstand = Wert), aus Neubestand mit
                    // fortgeführtem Einstand, ohne Kaufpreis bzw. ungedeckt ohne Kaufpreis
                    fun part(q: BigDecimal): BigDecimal = if (q.compareTo(carry.total) == 0) s.qty else s.qty.multiply(q).divide(carry.total, mc)
                    val fromOld = part(carry.oldQty)
                    if (fromOld.signum() > 0) {
                        c.neu.add(fromOld, carry.value?.let { v -> if (carry.oldQty.compareTo(carry.total) == 0) v else v.multiply(carry.oldQty).divide(carry.total, mc) }, s.time)
                    }
                    val fromPriced = part(carry.taken.removedPriced)
                    if (fromPriced.signum() > 0) c.neu.addCarried(fromPriced, carry.taken.parts)
                    val restQty = s.qty - fromOld - fromPriced
                    if (restQty.signum() > 0) c.neu.add(restQty, null, s.time)
                } else {
                    c.neu.add(s.qty, null, s.time)
                }
            }
            TaxStepKind.DISPOSE -> if (!s.swap) out += sale(coin, c, s) else {
                val (oldPieces, taken) = takeBoth(c, s.qty)
                val oldQty = oldPieces.fold(zero) { a, p -> a + p.amount }
                val inWallet: (TaxPiece, PortfolioDisposal) -> PortfolioDisposal = { _, d -> d.copy(wallet = c.wallet) }
                if (s.time < neutralFrom) {
                    // Tausch vor dem 1.3.2022: alte Rechtslage (§ 31 EStG a. F.) — Tausch = Veräusserung des ganzen
                    // abgegebenen Coins (Alt- und Neubestand, EStR Rz 6103k), der erhaltene Coin ist neu angeschafft
                    val list = ArrayList<TaxPiece>(oldPieces)
                    list += piecesOf(taken, oldHolding = false)
                    val rest = s.qty - oldQty - taken.removed
                    if (rest.signum() > 0) list += TaxPiece(rest, null, uncovered = true)
                    out += emit(coin, s, list, s.proceeds, inWallet)
                    carries[s.id] = Carry(s.qty, oldQty, taken, s.gross, true)
                } else {
                    // Tausch ab 1.3.2022: nur der Altbestand-Teil ist eine Veräusserung (§ 31 EStG a. F., Rz 6103k); Neubestand
                    // geht steuerneutral über (§ 27b Abs. 3 Z 2 EStG) — auch in einen Stablecoin (USDT, USDC …): der
                    // Einstand geht mit, steuerbar erst der Verkauf gegen Euro
                    val proceedsOld = s.proceeds?.let { p -> if (oldQty.compareTo(s.qty) == 0) p else p.multiply(oldQty).divide(s.qty, mc) }
                    if (oldQty.signum() > 0) out += emit(coin, s, oldPieces, proceedsOld, inWallet)
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
