package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioFifoLots.Lot
import com.cryptochecker.app.domain.portfolio.PortfolioFifoLots.Queue
import com.cryptochecker.app.domain.portfolio.PortfolioFifoLots.Slice
import com.cryptochecker.app.domain.portfolio.PortfolioFifoLots.carry
import com.cryptochecker.app.domain.portfolio.PortfolioFifoLots.consume
import com.cryptochecker.app.domain.portfolio.PortfolioFifoLots.insert
import com.cryptochecker.app.domain.portfolio.PortfolioFifoLots.spread
import com.cryptochecker.app.domain.portfolio.PortfolioFifoLots.spreadQueue

/**
 * Ein Lauf von [PortfolioFifo.run]: Warteschlangen je Wallet und die laufenden Summen eines Coins; ein Ereignis nach dem
 * anderen ([apply]), am Ende [result]. Regeln der Buchung bei [PortfolioFifo].
 */
internal class PortfolioFifoBook(private val symbol: String, private val perWallet: Boolean) {
    val zero = BigDecimal.ZERO
    val mc = PortfolioDecimal.MC
    val method = if (perWallet) CostMethod.FIFO_WALLET else CostMethod.FIFO
    val queues = LinkedHashMap<String, Queue>()
    val names = HashMap<String, String>()
    var realized = zero
    var unknownSold = zero
    var soldTotal = zero
    var withdrawn = zero
    var oversold = false
    var feeValueMissing = false
    var realizedFeeMissing = false
    val disposals = ArrayList<PortfolioDisposal>()
    val withdrawals = ArrayList<PortfolioWithdrawalCost>()
    val shortfalls = ArrayList<PortfolioWalletShortfall>()
    val lostFeeCosts = ArrayList<PortfolioLostFeeCost>()
    val unassignedFees = ArrayList<PortfolioUnassignedFee>()

    /** Umbuchungsgebühr-Anteil [usdt] auf Bestand ohne Kaufpreis: vermerkt ([PortfolioUnassignedFee]). */
    fun unassign(t: PortfolioTrade, usdt: BigDecimal) {
        if (usdt.signum() > 0) unassignedFees += PortfolioUnassignedFee(symbol, t.id, t.time, usdt)
    }

    /** Warteschlange der Wallet [raw] (universell immer dieselbe). */
    fun queue(raw: String?): Queue {
        val key = if (perWallet) PortfolioWallets.key(raw) else PortfolioWallets.UNSPECIFIED
        if (perWallet && key !in names) names[key] = raw?.trim().orEmpty()
        return queues.getOrPut(key) { Queue(key) }
    }

    /** Anzeigename der Wallet von [q] (nur je Wallet, sonst null). */
    fun walletOf(q: Queue): String? = if (perWallet) names[q.key] ?: PortfolioWallets.UNSPECIFIED else null

    /** Abgang [requested] grösser als der Bestand von [q]: markiert, je Wallet mit Fehlmenge. */
    fun check(q: Queue, requested: BigDecimal, t: PortfolioTrade, feeLeg: Boolean) {
        if (requested <= q.held) return
        oversold = true
        if (perWallet) {
            shortfalls += PortfolioWalletShortfall(symbol, walletOf(q) ?: PortfolioWallets.UNSPECIFIED, t.id, t.time, t.type, feeLeg, requested - q.held.max(zero))
        }
    }

    /**
     * Veräusserung: Erlös [proceeds] (null = unbekannt) des ganzen Abgangs nach Menge auf die Teile [slices] und
     * den ungedeckten Rest [uncovered] verteilt; [sold] = verkaufte Menge ohne Gebühr im Coin, gedeckter Teil
     * (für «unbekannt verkauft»).
     */
    fun dispose(
        t: PortfolioTrade,
        q: Queue,
        slices: List<Slice>,
        uncovered: BigDecimal,
        proceeds: BigDecimal?,
        sold: BigDecimal,
        feeLeg: Boolean,
        feeMissing: Boolean,
        swap: Boolean = false,
        /** Je Teil die VERKAUFTE Menge (ohne Gebühr-Coins), Erlös nur danach; null = nach Menge (alles verkauft). */
        soldParts: List<BigDecimal>? = null,
        /** Verkaufte Menge im ungedeckten Rest (mit [soldParts]). */
        uncoveredSold: BigDecimal = uncovered,
    ) {
        val removed = slices.fold(zero) { acc, s -> acc + s.amount }
        val total = removed + uncovered
        if (total.signum() <= 0) return
        val wallet = walletOf(q)
        // Schenkung, Zahlung mit Krypto, geschätzter Wert bzw. Gebühr-Preis (auch am Erlös, den sie mindert): Vermerke
        val gift = !feeLeg && t.fromGift
        val payment = !feeLeg && t.type == PortfolioTxType.PAYMENT && !t.fromGift
        val estimated = if (feeLeg) t.feePriceEstimated else t.valueEstimated || (t.feePriceEstimated && proceeds != null)
        val weights = soldParts ?: slices.map { it.amount }
        val weightTotal = weights.fold(zero) { acc, w -> acc + w } + (if (soldParts != null) uncoveredSold else uncovered)
        var proceedsLeft = proceeds
        // Letzter Teil MIT Gewicht bekommt den Rest: Summe der Anteile = Erlös, exakt
        val lastWeighted = when {
            (if (soldParts != null) uncoveredSold else uncovered).signum() > 0 -> slices.size
            else -> weights.indexOfLast { it.signum() > 0 }
        }
        slices.forEachIndexed { i, s ->
            val w = weights[i]
            val share = proceeds?.let {
                when {
                    w.signum() <= 0 -> zero
                    i == lastWeighted -> proceedsLeft ?: it.multiply(w).divide(weightTotal, mc)
                    else -> it.multiply(w).divide(weightTotal, mc)
                }
            }
            if (share != null) proceedsLeft = proceedsLeft?.minus(share)
            val gain = if (share != null && s.cost != null) share - s.cost else null
            if (gain != null) {
                realized += gain
            } else if (soldParts != null) {
                unknownSold += w
            } else {
                unknownSold += if (s.amount.compareTo(removed) == 0) sold else sold.multiply(s.amount).divide(removed, mc)
            }
            disposals += PortfolioDisposal(
                coin = symbol, sellTxId = t.id, sellTime = t.time,
                lotTxId = s.lot.txId, acquiredTime = s.lot.acquired, lotKind = s.lot.kind,
                amount = s.amount, costUsdt = s.cost, proceedsUsdt = share, gainUsdt = gain,
                holdingDays = PortfolioFifo.holdingDays(s.lot.acquired, t.time),
                feeLeg = feeLeg, feeValueMissing = feeMissing, method = method,
                costParts = if (s.cost != null && s.entries.isNotEmpty()) PortfolioFifoParts(s.entries, s.cost) else emptyList(),
                wallet = wallet, swap = swap, gift = gift, payment = payment, valueEstimated = estimated || (s.cost != null && s.lot.estimated),
            )
        }
        if (uncovered.signum() > 0) {
            // Mehr abgegeben als erfasst: Erlös bekannt, Kaufpreis fehlt (nicht im realisierten Gewinn)
            disposals += PortfolioDisposal(
                coin = symbol, sellTxId = t.id, sellTime = t.time,
                lotTxId = null, acquiredTime = null, lotKind = null,
                amount = uncovered, costUsdt = null, proceedsUsdt = proceedsLeft, gainUsdt = null,
                holdingDays = null, feeLeg = feeLeg, feeValueMissing = feeMissing, method = method,
                uncovered = true, wallet = wallet, swap = swap, gift = gift, payment = payment, valueEstimated = estimated,
            )
        }
    }

    /** Gebühr als Aufwand ([PortfolioFeeExpenses]): realisiert −Aufwand, eine Veräusserung mit Erlös 0. */
    fun expense(e: PortfolioLedgerEvent, q: Queue, kind: PortfolioFeeExpense, x: PortfolioFeeExpenses.Expense?) {
        if (x == null) return
        realized -= x.usdt
        if (x.feeValueMissing) feeValueMissing = true
        disposals += PortfolioFeeExpenses.disposal(symbol, e, kind, x, method, walletOf(q))
    }

    /** Anfangsposten (Übergang): mit Erwerb und Rest-Einstand in ihre Wallet. */
    fun seed(lots: List<PortfolioLot>) {
        for (l in lots) {
            if (l.amount.signum() > 0) insert(queue(l.wallet), Lot(l.txId, l.acquiredTime, l.acquiredTime, l.kind, l.amount, l.costUsdt, estimated = l.estimated))
        }
    }

    /** Bucht ein Ereignis des Coins. */
    fun apply(e: PortfolioLedgerEvent) {
        val t = e.trade
        if (t.amount.signum() <= 0) return
        if (e.feeLeg) return feeLeg(e)
        if (e.swapIn) return swapIn(e)
        when (t.type) {
            PortfolioTxType.BUY, PortfolioTxType.REWARD, PortfolioTxType.DEPOSIT -> acquire(e)
            PortfolioTxType.SELL, PortfolioTxType.PAYMENT, PortfolioTxType.SWAP -> sell(t)
            // Verschenkt: wie eine Auszahlung (beim Schenkenden keine Veräusserung)
            PortfolioTxType.WITHDRAWAL, PortfolioTxType.GIFT -> withdraw(e)
            PortfolioTxType.TRANSFER -> transfer(t)
        }
    }

    private fun feeLeg(e: PortfolioLedgerEvent) {
        val t = e.trade
        // Gebühr einer anderen Transaktion in diesem Coin bezahlt: Veräusserung zum Gebühr-Preis
        val q = queue(PortfolioWallets.of(e))
        val fee = t.thirdFeeAmount
        check(q, fee, t, feeLeg = true)
        val slices = consume(q, fee)
        val removed = slices.fold(zero) { acc, s -> acc + s.amount }
        dispose(t, q, slices, fee - removed, t.thirdFeePrice?.let { fee.multiply(it, PortfolioDecimal.MC) }, removed, feeLeg = true, feeMissing = false)
        soldTotal += removed
    }

    private fun swapIn(e: PortfolioLedgerEvent) {
        val t = e.trade
        // Zugang aus einem Tausch: Einstand = Wert des Tauschs (Gebühren zählen beim Abgang); die Gebühr im
        // erhaltenen Coin senkt nur die Menge (Einstand je Coin steigt)
        val received = t.swapReceived
        if (received.signum() <= 0) {
            // Gebühr verbraucht den ganzen Zugang: Wert des Tauschs als Aufwand (kein Schein-Gewinn)
            expense(e, queue(t.exchange), PortfolioFeeExpense.CONSUMED, PortfolioFeeExpenses.consumed(e))
            return
        }
        insert(queue(t.exchange), Lot(t.id, t.time, t.time, PortfolioTxType.SWAP, received, t.swapValue, estimated = t.costEstimated(swapIn = true)))
    }

    private fun acquire(e: PortfolioLedgerEvent) {
        val t = e.trade
        val amount = t.amount
        // Gebühr-Zeile ohne Tageskurs: Erlös unbekannt ([PortfolioLedger.salePrice])
        val price = PortfolioLedger.salePrice(t)
        val feeValue = t.thirdFeeValue
        val received = t.received
        if (received.signum() <= 0) {
            // Gebühr im Coin verbraucht den ganzen Zugang: Einstand als Aufwand
            expense(e, queue(t.exchange), PortfolioFeeExpense.CONSUMED, PortfolioFeeExpenses.consumed(e))
            return
        }
        val cost = price?.let { amount.multiply(it, PortfolioDecimal.MC) + t.quoteFee + (feeValue ?: zero) }
        if (price != null && feeValue == null) feeValueMissing = true
        insert(queue(t.exchange), Lot(t.id, t.acquisitionTime, t.time, t.type, received, cost, estimated = t.costEstimated()))
    }

    private fun sell(t: PortfolioTrade) {
        val amount = t.amount
        // Gebühr-Zeile ohne Tageskurs: Erlös unbekannt ([PortfolioLedger.salePrice])
        val price = PortfolioLedger.salePrice(t)
        val feeValue = t.thirdFeeValue
        // Damit bezahlt: wie ein Verkauf zum Marktwert (Preis = Marktwert je Coin)
        val swap = t.type == PortfolioTxType.SWAP
        val q = queue(t.exchange)
        val requested = amount + t.coinFee
        check(q, requested, t, feeLeg = false)
        val slices = consume(q, requested)
        val removed = slices.fold(zero) { acc, s -> acc + s.amount }
        // Zuerst die verkaufte Menge, danach die Gebühr-Coins (FIFO in einem Zug): verkaufter Teil je Posten
        var before = zero
        val soldParts = slices.map { s ->
            val part = (amount - before).max(zero).min(s.amount)
            before += s.amount
            part
        }
        val sold = soldParts.fold(zero) { acc, p -> acc + p }
        // Erlös des ganzen Abgangs (Tausch: sein Wert) nur nach verkaufter Menge verteilt (Gebühr-Coins: Erlös 0)
        // Tausch ohne gültigen Zugang (erhaltener Coin fehlt): Erlös unbekannt → «realisiert unvollständig»
        val gross = if (swap) t.swapValue?.takeIf { t.swapToCoin != null } else price?.let { amount.multiply(it, PortfolioDecimal.MC) }
        val proceeds = gross?.let { it - t.quoteFee - (feeValue ?: zero) }
        val feeMissing = gross != null && feeValue == null
        if (feeMissing && removed.signum() > 0) realizedFeeMissing = true
        if (feeMissing) feeValueMissing = true
        dispose(
            t, q, slices, requested - removed, proceeds, sold, feeLeg = false, feeMissing = feeMissing, swap = swap,
            soldParts = soldParts, uncoveredSold = amount - sold,
        )
        soldTotal += sold
    }

    private fun withdraw(e: PortfolioLedgerEvent) {
        val t = e.trade
        val amount = t.amount
        // Coins verlassen das Portfolio: älteste Posten zuerst, kein Gewinn, keine Veräusserung
        val q = queue(t.exchange)
        val requested = amount + t.coinFee
        check(q, requested, t, feeLeg = false)
        // Gebühr auf den nicht erfassten Teil (auch aus leerer Warteschlange): vermerkt statt still verloren
        unassign(t, PortfolioFeeExpenses.withdrawalUncovered(t, q.held))
        if (q.held.signum() <= 0) return
        // Gebühr USDT + Wert der Gebühr im dritten Coin: Aufwand (realisiert), wie im Zeitraum
        expense(e, q, PortfolioFeeExpense.WITHDRAWAL, PortfolioFeeExpenses.withdrawal(t, q.held))
        val slices = consume(q, requested)
        val removed = slices.fold(zero) { acc, s -> acc + s.amount }
        val sold = if (removed.compareTo(requested) == 0) amount else removed.multiply(amount).divide(requested, mc)
        withdrawn += sold
        withdrawals += PortfolioWithdrawalCost(
            trade = t, sold = sold, removed = removed,
            pricedRemoved = slices.filter { it.cost != null }.fold(zero) { acc, s -> acc + s.amount },
            cost = slices.fold(zero) { acc, s -> acc + (s.cost ?: zero) },
        )
    }

    private fun transfer(t: PortfolioTrade) {
        val amount = t.amount
        val feeValue = t.thirdFeeValue
        val src = queue(t.transferFrom)
        val dst = queue(t.transferTo)
        val coinFee = t.coinFee
        if (src !== dst) {
            // Je Wallet: Posten wandern mit Kaufdatum und Einstand von der Quelle zum Ziel
            check(src, amount + coinFee, t, feeLeg = false)
            // Leere Quelle → die ganze Gebühr vermerkt statt still verworfen
            if (src.held.signum() <= 0) { unassign(t, PortfolioFeeExpenses.transferUncovered(t, zero)); return }
            // Netzwerkgebühr zuerst (älteste Posten der Quelle); ihr Einstand wandert mit
            val carried = consume(src, coinFee)
            val moved = consume(src, amount).map { s ->
                Lot(s.lot.txId, s.lot.acquired, s.lot.time, s.lot.kind, s.amount, s.cost, s.entries, s.lot.estimated)
            }
            // Gebühr USDT + Wert der Gebühr im dritten Coin nach Menge auf die verschobenen Teile; der Anteil
            // ohne Kaufpreis und der Anteil der nicht angekommenen Menge werden vermerkt
            if (feeValue == null && moved.any { it.priced }) feeValueMissing = true
            val movedAmount = moved.fold(zero) { acc, l -> acc + l.amount }
            val lost = PortfolioFeeExpenses.transferUncovered(t, movedAmount)
            unassign(t, lost + spread(moved, t.quoteFee + (feeValue ?: zero) - lost, movedAmount, t.time, t.feePriceEstimated))
            // Nächster Posten mit Kaufpreis: verschoben, Rest der Quelle, sonst im Ziel; erst ohne jeden
            // wartet der Einstand (bei den verschobenen Coins im Ziel, sonst in der Quelle)
            val target = moved.firstOrNull { it.priced } ?: src.lots.firstOrNull { it.priced }
                ?: dst.lots.firstOrNull { it.priced }
            carry(symbol, target, if (moved.isEmpty()) src else dst, t, carried)
            moved.forEach { insert(dst, it) }
            return
        }
        val q = src
        val h = q.held
        // Wie je Wallet: Menge + Netzwerkgebühr über dem Bestand → markiert; der Gebühr-Anteil der nicht
        // gedeckten Menge (gedeckt = Bestand − Netzwerkgebühr) wird vermerkt (leerer Bestand: die ganze Gebühr)
        check(q, amount + coinFee, t, feeLeg = false)
        val lost = PortfolioFeeExpenses.transferUncovered(t, (h - coinFee).max(zero))
        if (h.signum() <= 0) { unassign(t, lost); return }
        // Rest der Gebühr USDT + Wert der Gebühr im dritten Coin: nach Menge auf die Posten verteilt; der
        // Anteil der Posten ohne Kaufpreis wird vermerkt
        if (feeValue == null && q.hasPriced) feeValueMissing = true
        unassign(t, lost + spreadQueue(q, t.quoteFee + (feeValue ?: zero) - lost, h, t.time, t.feePriceEstimated))
        // Netzwerkgebühr im Coin: Menge sinkt (älteste zuerst), ihr Einstand geht auf den nächsten
        // Posten mit Kaufpreis über (Begründung oben: Kosten des Haltens, keine Veräusserung)
        if (coinFee.signum() > 0) {
            val carried = consume(q, coinFee)
            carry(symbol, q.lots.firstOrNull { it.priced }, q, t, carried)
        }
    }

    /** Offene Posten und Summen nach dem letzten Ereignis. */
    fun result(): PortfolioBookResult {
    var priced = zero
    var unpriced = zero
    var cost = zero
    val outLots = ArrayList<PortfolioLot>()
    // Gebühr-Einstand, für den bis zum Ende kein Posten mit Kaufpreis kam: vermerkt (zeitlich)
    queues.values.flatMapTo(lostFeeCosts) { it.pendingLost }
    lostFeeCosts.sortWith(compareBy<PortfolioLostFeeCost> { it.time }.thenBy { it.txId })
    for (q in queues.values) {
        for (l in q.lots) {
            val lotCost = l.cost
            if (lotCost != null) { priced += l.amount; cost += lotCost } else unpriced += l.amount
            outLots += PortfolioLot(symbol, l.txId, l.acquired, l.kind, l.amount, l.cost, walletOf(q), l.estimated)
        }
    }
    return PortfolioBookResult(
        priced = priced, unpriced = unpriced, cost = cost, realized = realized,
        unknownSold = unknownSold, soldTotal = soldTotal, withdrawn = withdrawn, oversold = oversold,
        feeValueMissing = feeValueMissing, realizedFeeMissing = realizedFeeMissing,
        lots = outLots,
        disposals = disposals,
        withdrawals = withdrawals,
        shortfalls = shortfalls,
        lostFeeCosts = lostFeeCosts,
        unassignedFees = unassignedFees,
        openParts = PortfolioFifoParts.openParts(queues.values, cost),
    )
    }
}
