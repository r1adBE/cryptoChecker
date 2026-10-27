// Herkunftsteile der FIFO-Posten (Teil von [PortfolioFifo]) ohne Teil je Posten und Umbuchung — gespiegelt in
// `PortfolioFifoParts.swift`. Die Gebühr USDT (+ Wert der Gebühr im dritten Coin) einer Umbuchung verteilt sich nach Menge
// auf alle Posten der Warteschlange: statt jedem Posten einen Teil zuzuschlagen, merkt sich die Warteschlange nur den
// Anteil je Coin ([PortfolioFifoParts.FeeStream]), und ein Posten mit Kaufpreis trägt aus jeder Umbuchung, während der er
// dort lag, seine Menge × Anteil — gerechnet erst beim Auslesen. Das gilt auch nach Abgängen: ein Abgang senkt Menge und
// Teile im selben Verhältnis. Abgegebene Teile sind Einträge ([PortfolioFifoParts.Entry]: fester Betrag oder Bereich des
// Stroms mit Gewicht), ausgelesen erst, wenn jemand die Teile einer Veräusserung braucht ([PortfolioFifoParts]).
package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal

/**
 * Herkunftsteile einer FIFO-Veräusserung ([PortfolioDisposal.costParts]): Einträge [entries] mit Summe [total],
 * ausgelesen beim ersten Zugriff ([parts]), dann behalten. Wie `PortfolioFifoParts` (iOS).
 */
class PortfolioFifoParts(
    private val entries: List<Entry>,
    private val total: BigDecimal,
) : AbstractList<PortfolioCostPart>(), RandomAccess {
    private val items: List<PortfolioCostPart> by lazy { parts(entries, total) }

    override val size: Int get() = items.size

    override fun get(index: Int): PortfolioCostPart = items[index]

    /**
     * Gebühren der Umbuchungen einer Warteschlange: je Umbuchung Zeitpunkt, Anteil je Coin (Gebühr ÷ Bestand, 34
     * Stellen) und laufende Summe der Anteile. Nur Anhängen (während [PortfolioFifo.run], ein Faden; danach nur gelesen).
     */
    class FeeStream {
        private val times = ArrayList<Long>()
        private val factors = ArrayList<BigDecimal>()
        private val prefix = arrayListOf(BigDecimal.ZERO)

        val count: Int get() = times.size

        fun append(time: Long, factor: BigDecimal) {
            times += time
            factors += factor
            prefix += prefix[prefix.size - 1] + factor
        }

        /** Σ Anteile der Umbuchungen [from] bis vor [to]. */
        fun sum(from: Int, to: Int): BigDecimal = prefix[to] - prefix[from]

        fun time(k: Int): Long = times[k]

        fun factor(k: Int): BigDecimal = factors[k]
    }

    /**
     * Teil eines Einstands: fester Betrag zu einem Zeitpunkt ([Point]) oder die Umbuchungen `from until to` eines
     * Stroms mit Gewicht (Menge, [Range]) — je Umbuchung k ein Teil Gewicht × Anteil k zu ihrem Zeitpunkt.
     */
    sealed class Entry {
        /** Betrag (Bereich: Gewicht × Σ Anteile, 34 Stellen). */
        abstract val value: BigDecimal

        /** Die Teile einzeln (Bereich: je Umbuchung einer). */
        abstract fun forEachPart(body: (Long, BigDecimal) -> Unit)

        class Point(val time: Long, val usdt: BigDecimal) : Entry() {
            override val value: BigDecimal get() = usdt
            override fun forEachPart(body: (Long, BigDecimal) -> Unit) = body(time, usdt)
        }

        class Range(val stream: FeeStream, val from: Int, val to: Int, val weight: BigDecimal) : Entry() {
            override val value: BigDecimal get() = weight.multiply(stream.sum(from, to), PortfolioDecimal.MC)
            override fun forEachPart(body: (Long, BigDecimal) -> Unit) {
                for (k in from until to) body(stream.time(k), weight.multiply(stream.factor(k), PortfolioDecimal.MC))
            }
        }
    }

    /**
     * Eigene Teile eines Postens (bzw. wartender Gebühr-Einstand): feste Beträge je Zeitpunkt zusammengefasst (gleicher
     * Devisenkurs), Bereiche je Strom und Grenzen.
     */
    class Entries() {
        private data class RangeKey(val stream: FeeStream, val from: Int, val to: Int)

        private val list = ArrayList<Entry>()
        private val points = HashMap<Long, Int>()
        private val ranges = HashMap<RangeKey, Int>()

        val items: List<Entry> get() = list

        /** Feste Beträge in dieser Reihenfolge, auch 0 (wie eine Liste nach dem anteiligen Herausnehmen). */
        constructor(parts: List<PortfolioCostPart>) : this() {
            for (p in parts) {
                points[p.time] = list.size
                list += Entry.Point(p.time, p.usdt)
            }
        }

        val isEmpty: Boolean get() = list.isEmpty()

        /** Summe der Beträge. */
        val value: BigDecimal get() = list.fold(BigDecimal.ZERO) { acc, e -> acc + e.value }

        /** Nur feste Beträge als Liste; null, wenn ein Bereich dabei ist. */
        val pointParts: MutableList<PortfolioCostPart>?
            get() {
                val out = ArrayList<PortfolioCostPart>(list.size)
                for (e in list) {
                    if (e !is Entry.Point) return null
                    out += PortfolioCostPart(e.time, e.usdt)
                }
                return out
            }

        /** Fügt [entry] hinzu (0 = nichts): gleicher Zeitpunkt bzw. gleicher Bereich wird addiert. */
        fun add(entry: Entry) {
            when (entry) {
                is Entry.Point -> {
                    if (entry.usdt.signum() == 0) return
                    val i = points[entry.time]
                    val old = i?.let { list[it] as? Entry.Point }
                    if (i != null && old != null) {
                        list[i] = Entry.Point(entry.time, old.usdt + entry.usdt)
                    } else {
                        points[entry.time] = list.size
                        list += entry
                    }
                }
                is Entry.Range -> {
                    if (entry.to <= entry.from || entry.weight.signum() == 0) return
                    val key = RangeKey(entry.stream, entry.from, entry.to)
                    val i = ranges[key]
                    val old = i?.let { list[it] as? Entry.Range }
                    if (i != null && old != null) {
                        list[i] = Entry.Range(entry.stream, entry.from, entry.to, old.weight + entry.weight)
                    } else {
                        ranges[key] = list.size
                        list += entry
                    }
                }
            }
        }

        fun clear() {
            list.clear()
            points.clear()
            ranges.clear()
        }
    }

    companion object {
        /**
         * Teile von [entries] mit Summe exakt [total]: gleiche Zeitpunkte zusammengefasst (Reihenfolge des ersten
         * Auftretens), der letzte Teil ist der Rest.
         */
        fun parts(entries: List<Entry>, total: BigDecimal): List<PortfolioCostPart> {
            val index = HashMap<Long, Int>()
            val out = ArrayList<PortfolioCostPart>()
            for (e in entries) {
                e.forEachPart { time, usdt ->
                    val i = index[time]
                    if (i != null) {
                        out[i] = PortfolioCostPart(time, out[i].usdt + usdt)
                    } else {
                        index[time] = out.size
                        out += PortfolioCostPart(time, usdt)
                    }
                }
            }
            return withRest(out, total)
        }

        /**
         * Offene Herkunftsteile aller Posten mit Kaufpreis (Summe exakt [total]): die eigenen Teile je Posten, dazu je
         * Umbuchung EIN Teil aus der Menge aller Posten, die da schon in der Warteschlange lagen (Menge × Anteil).
         */
        fun openParts(queues: Collection<PortfolioFifoLots.Queue>, total: BigDecimal): List<PortfolioCostPart> {
            val out = ArrayList<PortfolioCostPart>()
            for (q in queues) {
                val since = ArrayList<Pair<Int, BigDecimal>>()
                for (lot in q.lots) {
                    if (!lot.priced) continue
                    out += lot.ownParts
                    lot.openFrom?.let { since += it to lot.amount }
                }
                if (since.isEmpty()) continue
                since.sortBy { it.first }
                var amount = BigDecimal.ZERO
                var next = 0
                for (k in since[0].first until q.stream.count) {
                    while (next < since.size && since[next].first <= k) {
                        amount += since[next].second
                        next++
                    }
                    out += PortfolioCostPart(q.stream.time(k), amount.multiply(q.stream.factor(k), PortfolioDecimal.MC))
                }
            }
            return withRest(out, total)
        }

        /** Letzter Teil = [total] − Summe der übrigen (leer bleibt leer). */
        private fun withRest(out: ArrayList<PortfolioCostPart>, total: BigDecimal): List<PortfolioCostPart> {
            if (out.isEmpty()) return out
            var others = BigDecimal.ZERO
            for (i in 0 until out.size - 1) others += out[i].usdt
            out[out.size - 1] = PortfolioCostPart(out[out.size - 1].time, total - others)
            return out
        }
    }
}
