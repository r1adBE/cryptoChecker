// Posten, Warteschlangen und Teilstücke der FIFO-Rechnung (Teil von [PortfolioFifo], gespiegelt in `PortfolioFifo+Lots.swift`).
// Herkunftsteile: PortfolioFifoParts.kt.
package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.portfolio.PortfolioFifoParts.Entries
import com.cryptochecker.app.domain.portfolio.PortfolioFifoParts.Entry
import com.cryptochecker.app.domain.portfolio.PortfolioFifoParts.FeeStream
import java.math.BigDecimal

/** Bausteine von [PortfolioFifo.run]: Posten mit Einstand und Herkunft, Warteschlange je Wallet, verbrauchte Teile. */
object PortfolioFifoLots {

    /**
     * Posten: Menge und Einstand samt Herkunft. Einstand = eigene Teile ([own]: Erwerb, zugeschlagene und übernommene
     * Beträge) + Menge × Σ Anteile der Umbuchungen seit dem Eintritt in die Warteschlange ([FeeStream]).
     */
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
        entries: List<Entry>? = null,
        /** Einstand enthält einen geschätzten Wert ([PortfolioTrade.costEstimated]); bleibt bis zum Abgang des Postens. */
        var estimated: Boolean = false,
    ) {
        /** Kaufpreis bekannt (sonst kein Einstand und keine Teile). */
        val priced: Boolean = cost != null

        /** Eigene Teile (ohne die Umbuchungen seit [from]). */
        private var own = Entries()

        /** Summe von [own]. */
        private var ownCost: BigDecimal = BigDecimal.ZERO

        /** Gebühren-Strom der Warteschlange und erste Umbuchung, an der der Posten teilnimmt. */
        private var stream: FeeStream? = null
        private var from = 0

        init {
            if (cost != null) {
                if (entries != null) {
                    entries.forEach { own.add(it) }
                    ownCost = own.value
                } else {
                    own.add(Entry.Point(acquired, cost))
                    ownCost = cost
                }
            }
        }

        /** In eine Warteschlange aufgenommen: nimmt an deren folgenden Umbuchungen teil. */
        fun join(stream: FeeStream) {
            this.stream = stream
            from = stream.count
        }

        /** Σ Anteile der Umbuchungen seit dem Eintritt (0 = keine). */
        private val openSum: BigDecimal
            get() {
                val s = stream ?: return BigDecimal.ZERO
                return if (s.count > from) s.sum(from, s.count) else BigDecimal.ZERO
            }

        /** Erste Umbuchung, an der der Posten teilnimmt; null = keine seit dem Eintritt. */
        val openFrom: Int?
            get() {
                val s = stream ?: return null
                return if (s.count > from) from else null
            }

        /** Rest-Einstand; null = Kaufpreis unbekannt. */
        val cost: BigDecimal?
            get() {
                if (!priced) return null
                val open = openSum
                return if (open.signum() == 0) ownCost else ownCost + amount.multiply(open, PortfolioDecimal.MC)
            }

        /** Eigene Teile (Summe exakt = eigener Einstand). */
        val ownParts: List<PortfolioCostPart> get() = PortfolioFifoParts.parts(own.items, ownCost)

        /** Alle Teile (Summe exakt = Einstand); leer ohne Kaufpreis. */
        val parts: List<PortfolioCostPart> get() = PortfolioFifoParts.parts(allEntries(amount), cost ?: BigDecimal.ZERO)

        /** Eigene Teile und die Umbuchungen seit dem Eintritt mit Gewicht [weight]. */
        private fun allEntries(weight: BigDecimal): List<Entry> {
            val s = stream
            if (s == null || s.count <= from) return own.items.toList()
            return own.items + Entry.Range(s, from, s.count, weight)
        }

        /** Gebühr bzw. übernommener Einstand [usdt] zum Zeitpunkt [at] (nur mit Kaufpreis). */
        fun addCost(at: Long, usdt: BigDecimal) {
            if (!priced || usdt.signum() == 0) return
            own.add(Entry.Point(at, usdt))
            ownCost += usdt
        }

        /** Übernimmt Einstand samt Herkunft [entries] (nur mit Kaufpreis). */
        fun addEntries(entries: List<Entry>) {
            if (!priced || entries.isEmpty()) return
            // Summe mitführen statt alle eigenen Einträge neu zu addieren (der älteste Posten sammelt viele)
            for (e in entries) {
                own.add(e)
                ownCost += e.value
            }
        }

        /** Herausgenommener Einstand samt Herkunft. */
        class Taken(val cost: BigDecimal, val entries: List<Entry>)

        /**
         * Einstand der Teilmenge [qty] (ganze Menge: ganzer Einstand) samt Herkunft herausnehmen; null ohne Kaufpreis.
         * Die Umbuchungen seit dem Eintritt gehen mit Gewicht [qty] mit (ohne Teilung); nur feste Beträge: anteilig wie
         * eine Liste (letzter Teil = Rest, [PortfolioCostParts.take]).
         */
        fun take(qty: BigDecimal): Taken? {
            val c = cost ?: return null
            val whole = qty.compareTo(amount) == 0
            val removed = if (whole) c else c.multiply(qty).divide(amount, PortfolioDecimal.MC)
            if (whole) {
                val out = allEntries(amount)
                own = Entries()
                ownCost = BigDecimal.ZERO
                return Taken(removed, out)
            }
            val points = if (openFrom == null) own.pointParts else null
            if (points != null) {
                val taken = PortfolioCostParts.take(points, c, removed)
                own = Entries(points)
                ownCost = c - removed
                return Taken(removed, taken.map { Entry.Point(it.time, it.usdt) })
            }
            val out = ArrayList<Entry>()
            val rest = Entries()
            for (e in own.items) {
                when (e) {
                    is Entry.Point -> {
                        val part = if (c.signum() == 0) BigDecimal.ZERO else e.usdt.multiply(removed).divide(c, PortfolioDecimal.MC)
                        out += Entry.Point(e.time, part)
                        rest.add(Entry.Point(e.time, e.usdt - part))
                    }
                    is Entry.Range -> {
                        val part = e.weight.multiply(qty).divide(amount, PortfolioDecimal.MC)
                        out += Entry.Range(e.stream, e.from, e.to, part)
                        rest.add(Entry.Range(e.stream, e.from, e.to, e.weight - part))
                    }
                }
            }
            val s = stream
            if (s != null && s.count > from) out += Entry.Range(s, from, s.count, qty)
            own = rest
            ownCost = own.value
            return Taken(removed, out)
        }
    }

    /** Warteschlange einer Wallet (universell: eine einzige). */
    class Queue(val key: String) {
        val lots = ArrayList<Lot>()
        var held: BigDecimal = BigDecimal.ZERO // Σ Menge der Posten

        /** Σ Menge der Posten mit Kaufpreis. */
        var pricedHeld: BigDecimal = BigDecimal.ZERO
            private set

        /** Zahl der Posten ohne Kaufpreis. */
        private var unpricedCount = 0

        /** Gebühren der Umbuchungen in dieser Warteschlange (nach Menge auf alle Posten). */
        val stream = FeeStream()

        /** Netzwerkgebühr-Einstand ohne bezahlten Posten: wartet auf den nächsten Posten mit Kaufpreis. */
        val pendingLost = ArrayList<PortfolioLostFeeCost>()

        /** Herkunft von [pendingLost] (Summe gleich). */
        val pending = Entries()

        /** [pendingLost] enthält einen geschätzten Wert ([Lot.estimated]). */
        var pendingEstimated = false

        /** Alle Posten mit Kaufpreis. */
        val allPriced: Boolean get() = unpricedCount == 0

        /** Mindestens ein Posten mit Kaufpreis. */
        val hasPriced: Boolean get() = lots.size > unpricedCount

        /** [lot] an seinen Platz (meist hinten): hinter allen Posten, vor denen er nicht liegt ([before]). */
        fun add(lot: Lot) {
            lot.join(stream)
            var i = lots.size
            while (i > 0 && before(lot, lots[i - 1])) i--
            lots.add(i, lot)
            held += lot.amount
            if (lot.priced) pricedHeld += lot.amount else unpricedCount++
        }

        /** Menge [qty] von [lot] abgegangen (vor dem Entfernen). */
        fun reduce(lot: Lot, qty: BigDecimal) {
            held -= qty
            if (lot.priced) pricedHeld -= qty
        }

        /** Ältesten Posten entfernen. */
        fun removeFirst() {
            val lot = lots.removeAt(0)
            if (!lot.priced) unpricedCount--
        }

        /** Leer: Bestände auf 0 (kein Rest aus Rundungen). */
        fun clearHeldIfEmpty() {
            if (lots.isNotEmpty()) return
            held = BigDecimal.ZERO
            pricedHeld = BigDecimal.ZERO
        }
    }

    /** Verbrauchter Teil eines Postens: Menge, Einstand (null = ohne Kaufpreis) und dessen Herkunft. */
    class Slice(val lot: Lot, val amount: BigDecimal, val cost: BigDecimal?, val entries: List<Entry>)

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
        if (carried.any { it.cost != null && it.lot.estimated }) q.pendingEstimated = true
        q.pendingLost += PortfolioLostFeeCost(symbol, t.id, t.time, cost)
        carried.forEach { s -> s.entries.forEach { q.pending.add(it) } }
    }

    /** Netzwerkgebühr-Einstand [carried] auf [target] (Posten mit Kaufpreis), sonst wartend in [q] ([lose]). */
    fun carry(symbol: String, target: Lot?, q: Queue, t: PortfolioTrade, carried: List<Slice>) {
        if (carried.costSum().signum() == 0) return
        if (target == null) return lose(symbol, q, t, carried)
        target.addEntries(carried.flatMap { it.entries })
        if (carried.any { it.cost != null && it.lot.estimated }) target.estimated = true
    }

    /** Neuer Posten an seinem Platz (meist hinten); ein Posten mit Kaufpreis übernimmt wartenden Gebühr-Einstand. */
    fun insert(q: Queue, lot: Lot) {
        if (lot.priced && q.pendingLost.isNotEmpty()) {
            lot.addEntries(q.pending.items.toList())
            if (q.pendingEstimated) lot.estimated = true
            q.pendingLost.clear()
            q.pending.clear()
            q.pendingEstimated = false
        }
        q.add(lot)
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
            val taken = lot.take(qty)
            q.reduce(lot, qty)
            lot.amount -= qty
            if (lot.amount.signum() <= 0) q.removeFirst()
            left -= qty
            out += Slice(lot, qty, taken?.cost, taken?.entries.orEmpty())
        }
        q.clearHeldIfEmpty()
        return out
    }

    /**
     * Je Wallet: Gebühr USDT + Wert der Gebühr im dritten Coin ([extra]) einer Umbuchung zum Zeitpunkt [at] nach Menge
     * auf die verschobenen Posten [lots] (Summe [h], je Posten ein Teil); der letzte bekommt den Rest, wenn alle einen
     * Kaufpreis haben. Rückgabe = Anteil der Posten OHNE Kaufpreis (gehört zu deren unbekanntem Einstand, `unassign`);
     * 0 = alles zugeordnet. [estimated]: [extra] enthält einen geschätzten Wert — die Posten mit Kaufpreis tragen ihn.
     */
    fun spread(lots: List<Lot>, extra: BigDecimal, h: BigDecimal, at: Long, estimated: Boolean): BigDecimal {
        if (extra.signum() <= 0 || h.signum() <= 0) return BigDecimal.ZERO
        val allPriced = lots.all { it.priced }
        var left = extra
        var assigned = BigDecimal.ZERO
        lots.forEachIndexed { i, lot ->
            val part = if (allPriced && i == lots.size - 1) left else extra.multiply(lot.amount).divide(h, PortfolioDecimal.MC)
            left -= part
            if (lot.priced) {
                lot.addCost(at, part)
                assigned += part
                if (estimated) lot.estimated = true
            }
        }
        return extra - assigned
    }

    /**
     * Gebühr [extra] einer Umbuchung zum Zeitpunkt [at] nach Menge auf alle Posten von [q] (Bestand [h]): ein Eintrag im
     * Gebühren-Strom (Anteil je Coin), kein Teil je Posten. Haben alle Posten einen Kaufpreis, bekommt der letzte den
     * Rundungsrest (Summe exakt [extra]). Rückgabe = Anteil der Posten OHNE Kaufpreis; 0 = alles zugeordnet.
     * [estimated]: [extra] enthält einen geschätzten Wert — alle Posten mit Kaufpreis tragen ihn ([Lot.estimated]).
     */
    fun spreadQueue(q: Queue, extra: BigDecimal, h: BigDecimal, at: Long, estimated: Boolean): BigDecimal {
        if (extra.signum() <= 0 || h.signum() <= 0) return BigDecimal.ZERO
        if (estimated) q.lots.forEach { if (it.priced) it.estimated = true }
        val factor = extra.divide(h, PortfolioDecimal.MC)
        q.stream.append(at, factor)
        if (!q.allPriced) return extra - q.pricedHeld.multiply(factor, PortfolioDecimal.MC)
        val rest = extra - h.multiply(factor, PortfolioDecimal.MC)
        if (rest.signum() != 0) q.lots.lastOrNull()?.addCost(at, rest)
        return BigDecimal.ZERO
    }
}
