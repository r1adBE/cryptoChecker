// Posten, Warteschlangen und Teilstücke der FIFO-Rechnung (Teil von [PortfolioFifo], gespiegelt in `PortfolioFifo+Lots.swift`).
package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal

/** Bausteine von [PortfolioFifo.run]: Posten mit Einstand und Herkunft, Warteschlange je Wallet, verbrauchte Teile. */
internal object PortfolioFifoLots {

    class Lot(
        val txId: Long,
        /** Erwerb (Reihenfolge, Haltedauer). */
        val acquired: Long,
        /** Zeit der Transaktion (bei gleichem Erwerb). */
        val time: Long,
        val kind: PortfolioTxType,
        var amount: BigDecimal,
        cost: BigDecimal?,
        /** Herkunft von [cost]; null = ganzer Einstand zum Erwerb. */
        parts: List<PortfolioCostPart>? = null,
    ) {
        /** Rest-Einstand; null = Kaufpreis unbekannt. Ändert sich nur zusammen mit [parts]. */
        var cost: BigDecimal? = cost
            private set

        /** Herkunft von [cost] (Summe = [cost]); leer ohne Kaufpreis bzw. bei Einstand 0. */
        val parts = ArrayList<PortfolioCostPart>()

        init {
            if (cost != null) {
                if (parts != null) parts.forEach { PortfolioCostParts.add(this.parts, it.time, it.usdt) }
                else PortfolioCostParts.add(this.parts, acquired, cost)
            }
        }

        /** Gebühr bzw. übernommener Einstand [usdt] zum Zeitpunkt [at] (nur mit Kaufpreis). */
        fun addCost(at: Long, usdt: BigDecimal) {
            // Ohne Kaufpreis bleibt der Posten unbewertet (nie abbrechen)
            val current = cost ?: return
            cost = current + usdt
            PortfolioCostParts.add(parts, at, usdt)
        }

        /** Übernimmt Einstand samt Herkunft [from] (nur mit Kaufpreis). */
        fun addParts(from: List<PortfolioCostPart>) = from.forEach { addCost(it.time, it.usdt) }

        /** Einstand der Teilmenge [qty] (ganze Menge: ganzer Einstand) samt Herkunft herausnehmen; null ohne Kaufpreis. */
        fun takeCost(qty: BigDecimal): Pair<BigDecimal, List<PortfolioCostPart>>? {
            val c = cost ?: return null
            val removed = if (qty.compareTo(amount) == 0) c else c.multiply(qty).divide(amount, PortfolioDecimal.MC)
            val taken = PortfolioCostParts.take(parts, c, removed)
            cost = c - removed
            return removed to taken
        }
    }

    /** Warteschlange einer Wallet (universell: eine einzige). */
    class Queue(val key: String) {
        val lots = ArrayList<Lot>()
        var held: BigDecimal = BigDecimal.ZERO // Σ Menge der Posten
        /** Netzwerkgebühr-Einstand ohne bezahlten Posten: wartet auf den nächsten Posten mit Kaufpreis. */
        val pendingLost = ArrayList<PortfolioLostFeeCost>()
        /** Herkunft von [pendingLost] (Summe gleich). */
        val pendingParts = ArrayList<PortfolioCostPart>()
    }

    /** Verbrauchter Teil eines Postens: Menge, Einstand (null = ohne Kaufpreis) und dessen Herkunft. */
    class Slice(val lot: Lot, val amount: BigDecimal, val cost: BigDecimal?, val parts: List<PortfolioCostPart>)

    /** Summe der Einstände (ohne Kaufpreis = 0). */
    fun List<Slice>.costSum(): BigDecimal = fold(BigDecimal.ZERO) { acc, s -> acc + (s.cost ?: BigDecimal.ZERO) }

    /** Posten [a] liegt vor [b]: Erwerb, dann Zeit, dann Id. */
    fun before(a: Lot, b: Lot): Boolean = when {
        a.acquired != b.acquired -> a.acquired < b.acquired
        a.time != b.time -> a.time < b.time
        else -> a.txId < b.txId
    }

    /**
     * Einstand der Netzwerkgebühr ohne bezahlten Posten in [q] ([carried], Summe von [carried] je Teil): nicht still
     * verlieren — wartet auf den nächsten Posten mit Kaufpreis ([insert]), sonst am Ende vermerkt.
     */
    fun lose(symbol: String, q: Queue, t: PortfolioTrade, carried: List<Slice>) {
        val cost = carried.costSum()
        if (cost.signum() == 0) return
        q.pendingLost += PortfolioLostFeeCost(symbol, t.id, t.time, cost)
        carried.forEach { s -> s.parts.forEach { PortfolioCostParts.add(q.pendingParts, it.time, it.usdt) } }
    }

    /** Netzwerkgebühr-Einstand [carried] auf [target] (Posten mit Kaufpreis), sonst wartend in [q] ([lose]). */
    fun carry(symbol: String, target: Lot?, q: Queue, t: PortfolioTrade, carried: List<Slice>) {
        if (carried.costSum().signum() == 0) return
        if (target != null) carried.forEach { target.addParts(it.parts) } else lose(symbol, q, t, carried)
    }

    /** Neuer Posten an seinem Platz (meist hinten); ein Posten mit Kaufpreis übernimmt wartenden Gebühr-Einstand. */
    fun insert(q: Queue, lot: Lot) {
        if (lot.cost != null && q.pendingLost.isNotEmpty()) {
            lot.addParts(q.pendingParts)
            q.pendingLost.clear()
            q.pendingParts.clear()
        }
        var i = q.lots.size
        while (i > 0 && before(lot, q.lots[i - 1])) i--
        q.lots.add(i, lot)
        q.held += lot.amount
    }

    /** Verbraucht [requested] (gekappt auf den Bestand von [q]), älteste Posten zuerst. */
    fun consume(q: Queue, requested: BigDecimal): List<Slice> {
        val out = ArrayList<Slice>()
        val lots = q.lots
        var left = requested.min(q.held)
        while (left.signum() > 0 && lots.isNotEmpty()) {
            val lot = lots[0]
            val qty = left.min(lot.amount)
            // Einstand anteilig samt Herkunft (vor dem Kürzen der Menge)
            val taken = lot.takeCost(qty)
            lot.amount -= qty
            q.held -= qty
            if (lot.amount.signum() <= 0) lots.removeAt(0)
            left -= qty
            out += Slice(lot, qty, taken?.first, taken?.second.orEmpty())
        }
        if (lots.isEmpty()) q.held = BigDecimal.ZERO
        return out
    }

    /**
     * Gebühr USDT + Wert der Gebühr im dritten Coin ([extra]) einer Umbuchung zum Zeitpunkt [at] nach Menge auf [lots]
     * (Summe [h]); der letzte Posten bekommt den Rest, wenn alle einen Kaufpreis haben. Rückgabe = Anteil der Posten
     * OHNE Kaufpreis (gehört zu deren unbekanntem Einstand, [unassign]); 0 = alles zugeordnet.
     */
    fun spread(lots: List<Lot>, extra: BigDecimal, h: BigDecimal, at: Long): BigDecimal {
        if (extra.signum() <= 0 || h.signum() <= 0) return BigDecimal.ZERO
        val allPriced = lots.all { it.cost != null }
        var left = extra
        var assigned = BigDecimal.ZERO
        lots.forEachIndexed { i, lot ->
            val part = if (allPriced && i == lots.size - 1) left else extra.multiply(lot.amount).divide(h, PortfolioDecimal.MC)
            left -= part
            if (lot.cost != null) {
                lot.addCost(at, part)
                assigned += part
            }
        }
        return extra - assigned
    }
}
