package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Herkunftsteile eines Durchschnitts-Einstands ([PortfolioCostPart]: Zeitpunkt und Betrag je Zugang/Gebühr) mit
 * anteiligen Abgängen in O(1): ein Abgang schreibt nicht jeden Teil neu, er merkt sich nur den verbleibenden Anteil
 * (Rest ÷ Einstand davor). Der Wert eines Zugangs ist sein Betrag × das Produkt der Anteile aller späteren Abgänge
 * ([PortfolioPartStore], Produkte über Bereiche in O(log n)). Die abgegebenen Teile eines Abgangs ([take]) sind eine
 * Ansicht ([PortfolioScaledParts]), gerechnet erst beim Auslesen; der Steuer-Export rechnet grosse Ansichten ohne
 * Auslesen um ([weightedSum], [anyTime], [timeBounds]).
 *
 * Wie die frühere Liste: Summe der abgegebenen Teile exakt = abgegebener Einstand (letzter Teil = Rest), Summe der
 * offenen Teile exakt = Einstand ([toList]); ohne Abgang dazwischen ist ein Teil exakt sein Betrag. Mit [zone] fasst
 * ein Teil die Zugänge desselben Kalendertags zusammen (gleicher Devisenkurs; der erste Zeitpunkt des Tags bleibt).
 * Gleich wie `PortfolioCostPartPool` (iOS).
 */
internal class PortfolioCostPartPool(private val zone: ZoneId? = null) {
    private var store = PortfolioPartStore()

    /** Mit [zone]: Tag → Teil (Index im Speicher). */
    private val dayIndex = HashMap<LocalDate, Int>()

    val isEmpty: Boolean get() = store.slotCount == 0

    /** Zugang [usdt] mit Herkunft [time]; 0 = kein Teil. */
    fun add(time: Long, usdt: BigDecimal) {
        if (usdt.signum() == 0) return
        val z = zone
        if (z == null) {
            store.newSlot(time, usdt)
            return
        }
        val day = Instant.ofEpochMilli(time).atZone(z).toLocalDate()
        val i = dayIndex[day]
        if (i != null) {
            store.contribute(i, usdt)
        } else {
            dayIndex[day] = store.slotCount
            store.newSlot(time, usdt)
        }
    }

    /**
     * Nimmt [removed] vom Einstand [total] (davor) anteilig aus allen Teilen; Summe exakt [removed]. Ab [total]: alle
     * Teile (Summe [total]), danach leer.
     */
    fun take(removed: BigDecimal, total: BigDecimal): List<PortfolioCostPart> {
        if (removed.signum() == 0 || isEmpty) return emptyList()
        val s = store
        if (removed >= total) {
            clear()
            return s.view(total, total)
        }
        val out = s.view(removed, total)
        s.scale(total - removed, total)
        return out
    }

    /** Offene Teile; Summe exakt [total] (= Einstand). */
    fun toList(total: BigDecimal): List<PortfolioCostPart> =
        if (isEmpty) emptyList() else ArrayList(store.view(total, total))

    /** Alle Teile verwerfen (abgegebene Ansichten bleiben gültig: sie lesen den bisherigen Speicher). */
    fun clear() {
        store = PortfolioPartStore()
        dayIndex.clear()
    }
}

/** Gewicht je Zeitpunkt eines Teils (z. B. Devisenkurs seines Tags); null = fehlt. Ein Objekt je Umrechnung. */
internal class PortfolioPartWeight(val of: (Long) -> BigDecimal?)

/** Prüfung je Zeitpunkt eines Teils (z. B. «USDT = 1 als Näherung»). Ein Objekt je Umrechnung. */
internal class PortfolioPartTest(val of: (Long) -> Boolean)

/**
 * Σ Teil × [weight] (Zeitpunkt des Teils; Produkte auf 34 Stellen); null, wenn ein Gewicht fehlt. Kleine Listen Teil
 * für Teil; eine grosse Ansicht ([PortfolioScaledParts]) aus der laufenden Summe ihres Speichers in O(1) — gleich bis
 * auf die Rundung der einzelnen Teile.
 */
internal fun List<PortfolioCostPart>.weightedSum(weight: PortfolioPartWeight): BigDecimal? {
    if (this is PortfolioScaledParts && size > PortfolioScaledParts.EXACT_MAX) return store.weightedSum(this, weight)
    var sum = BigDecimal.ZERO
    for (p in this) sum += p.usdt.multiply(weight.of(p.time) ?: return null, PortfolioDecimal.MC)
    return sum
}

/** Erfüllt ein Teil [test]? Grosse Ansichten ohne Auslesen. */
internal fun List<PortfolioCostPart>.anyTime(test: PortfolioPartTest): Boolean =
    if (this is PortfolioScaledParts && size > PortfolioScaledParts.EXACT_MAX) store.anyTime(this, test)
    else any { test.of(it.time) }

/** Frühester und spätester Zeitpunkt der Teile (leer = keine). Ansichten ohne Auslesen. */
internal fun List<PortfolioCostPart>.timeBounds(): List<Long> = when {
    isEmpty() -> emptyList()
    this is PortfolioScaledParts -> store.timeBounds(len)
    else -> listOf(minOf { it.time }, maxOf { it.time })
}

/**
 * Abgegebene Teile eines [PortfolioCostPartPool.take]: die ersten [len] Teile von [store] nach [k] Abgängen mit den
 * Zugängen vor [seq], davon der Anteil [removed] ÷ [total] (gleich: alle). Gerechnet beim ersten Auslesen, dann
 * behalten.
 */
internal class PortfolioScaledParts(
    val store: PortfolioPartStore,
    val len: Int,
    val k: Int,
    val seq: Int,
    val removed: BigDecimal,
    val total: BigDecimal,
) : AbstractList<PortfolioCostPart>(), RandomAccess {
    private val items: List<PortfolioCostPart> by lazy { store.read(len, k, seq, removed, total) }

    override val size: Int get() = len

    override fun get(index: Int): PortfolioCostPart = items[index]

    companion object {
        /** Bis zu so vielen Teilen rechnen [weightedSum]/[anyTime] Teil für Teil (exakt wie die Teile selbst). */
        const val EXACT_MAX = 64
    }
}

/**
 * Speicher eines [PortfolioCostPartPool] (nur Anhängen, damit Ansichten gültig bleiben): Teile (Zeitpunkt, Zugänge
 * verkettet), Zugänge (Betrag, Zahl der Abgänge davor) und die Anteile der Abgänge als Baum — Ebene 0 je Abgang, Ebene
 * h das Produkt von 2^h aufeinanderfolgenden (auf 34 Stellen).
 */
internal class PortfolioPartStore {
    private val slotTime = ArrayList<Long>()
    private val slotMin = ArrayList<Long>()
    private val slotMax = ArrayList<Long>()
    private val slotHead = ArrayList<Int>()
    private val slotTail = ArrayList<Int>()
    private val cUsdt = ArrayList<BigDecimal>()
    private val cTakes = ArrayList<Int>()
    private val cSlot = ArrayList<Int>()
    private val cNext = ArrayList<Int>()
    private val levels = ArrayList<ArrayList<BigDecimal>>()

    val slotCount: Int get() = slotTime.size
    private val takeCount: Int get() = if (levels.isEmpty()) 0 else levels[0].size

    fun newSlot(time: Long, usdt: BigDecimal) {
        val n = slotTime.size
        slotTime += time
        slotMin += if (n == 0) time else minOf(slotMin[n - 1], time)
        slotMax += if (n == 0) time else maxOf(slotMax[n - 1], time)
        slotHead += cUsdt.size
        slotTail += cUsdt.size
        append(n, usdt)
    }

    fun contribute(slot: Int, usdt: BigDecimal) {
        cNext[slotTail[slot]] = cUsdt.size
        slotTail[slot] = cUsdt.size
        append(slot, usdt)
    }

    private fun append(slot: Int, usdt: BigDecimal) {
        cUsdt += usdt
        cTakes += takeCount
        cSlot += slot
        cNext += -1
    }

    /** Abgang: es bleibt [remaining] von [total]. */
    fun scale(remaining: BigDecimal, total: BigDecimal) {
        var v = remaining.divide(total, PortfolioDecimal.MC)
        var h = 0
        while (true) {
            if (levels.size == h) levels += ArrayList<BigDecimal>()
            val level = levels[h]
            level += v
            val i = level.size - 1
            if ((i and 1) == 0) return
            v = level[i - 1].multiply(v, PortfolioDecimal.MC)
            h++
        }
    }

    fun view(removed: BigDecimal, total: BigDecimal): List<PortfolioCostPart> =
        PortfolioScaledParts(this, slotCount, takeCount, cUsdt.size, removed, total)

    /** Produkt der Anteile der Abgänge [from] bis vor [to] (Knoten des Baums); null = keiner (Faktor 1). */
    private fun range(from: Int, to: Int): BigDecimal? {
        val mc = PortfolioDecimal.MC
        var l = from
        var r = to
        var h = 0
        var left: BigDecimal? = null
        var right: BigDecimal? = null
        while (l < r) {
            val level = levels[h]
            if ((l and 1) == 1) {
                left = left?.multiply(level[l], mc) ?: level[l]
                l++
            }
            if ((r and 1) == 1) {
                r--
                right = right?.let { level[r].multiply(it, mc) } ?: level[r]
            }
            l = l shr 1
            r = r shr 1
            h++
        }
        return if (left == null) right else if (right == null) left else left.multiply(right, mc)
    }

    /** Teile einer Ansicht ([PortfolioScaledParts]); die Produkte von hinten nach vorn, je Bereich einmal. */
    fun read(len: Int, k: Int, seq: Int, removed: BigDecimal, total: BigDecimal): List<PortfolioCostPart> {
        val mc = PortfolioDecimal.MC
        val values = arrayOfNulls<BigDecimal>(len)
        var acc: BigDecimal? = null // Produkt der Abgänge [low, k)
        var low = k
        val chain = ArrayList<Int>()
        // Der letzte Teil ist der Rest: nur die übrigen brauchen ihren Wert
        for (i in len - 2 downTo 0) {
            chain.clear()
            var c = slotHead[i]
            while (c in 0 until seq) {
                chain += c
                c = cNext[c]
            }
            var sum: BigDecimal? = null
            for (j in chain.indices.reversed()) {
                val x = chain[j]
                val from = cTakes[x]
                val usdt = cUsdt[x]
                val v = when {
                    from >= k -> usdt
                    from < low -> {
                        val p = range(from, low)!!
                        val a = acc?.let { p.multiply(it, mc) } ?: p
                        acc = a
                        low = from
                        usdt.multiply(a, mc)
                    }
                    from == low -> usdt.multiply(acc!!, mc)
                    else -> usdt.multiply(range(from, k)!!, mc)
                }
                sum = sum?.let { v.add(it) } ?: v
            }
            values[i] = sum
        }
        val all = removed.compareTo(total) == 0
        val out = ArrayList<PortfolioCostPart>(len)
        var left = removed
        for (i in 0 until len) {
            val p = values[i]
            val r = when {
                i == len - 1 || p == null -> left
                all -> p
                else -> p.multiply(removed).divide(total, mc)
            }
            left -= r
            out += PortfolioCostPart(slotTime[i], r)
        }
        return out
    }

    fun timeBounds(len: Int): List<Long> = listOf(slotMin[len - 1], slotMax[len - 1])

    // ---- Laufende Summe je Gewicht ([weightedSum]) und erster Treffer je Prüfung ([anyTime]), beim Lesen nachgeführt

    private var weightFor: PortfolioPartWeight? = null
    private val slotWeight = ArrayList<BigDecimal?>()
    private var running = BigDecimal.ZERO
    private var runningContribs = 0

    /** Laufende Summe vor Abgang t (nach allen Zugängen davor). */
    private val beforeTake = ArrayList<BigDecimal>()

    /** Erster Zugang mit fehlendem Gewicht (Index); [Int.MAX_VALUE] = keiner bisher. */
    private var missingFrom = Int.MAX_VALUE
    private var testFor: PortfolioPartTest? = null
    private var testScanned = 0
    private var testFirst = Int.MAX_VALUE

    /**
     * Σ Teil × Gewicht einer Ansicht: laufende Summe Σ Zugang × Gewicht seines Teils, bei jedem Abgang mit dessen
     * Anteil multipliziert, zum Zeitpunkt der Ansicht × [PortfolioScaledParts.removed] ÷ [PortfolioScaledParts.total].
     */
    @Synchronized
    fun weightedSum(view: PortfolioScaledParts, weight: PortfolioPartWeight): BigDecimal? {
        val mc = PortfolioDecimal.MC
        if (weightFor !== weight) {
            weightFor = weight
            slotWeight.clear()
            running = BigDecimal.ZERO
            runningContribs = 0
            beforeTake.clear()
            missingFrom = Int.MAX_VALUE
        }
        while (beforeTake.size <= view.k && beforeTake.size < takeCount) {
            val t = beforeTake.size
            while (runningContribs < cUsdt.size && cTakes[runningContribs] <= t) addRunning(runningContribs++, weight)
            beforeTake += running
            running = running.multiply(levels[0][t], mc)
        }
        val at = if (view.k < beforeTake.size) {
            beforeTake[view.k]
        } else {
            while (runningContribs < cUsdt.size) addRunning(runningContribs++, weight)
            running
        }
        if (missingFrom < view.seq) return null
        return if (view.removed.compareTo(view.total) == 0) at else at.multiply(view.removed).divide(view.total, mc)
    }

    private fun addRunning(c: Int, weight: PortfolioPartWeight) {
        val slot = cSlot[c]
        while (slotWeight.size <= slot) slotWeight += weight.of(slotTime[slotWeight.size])
        val w = slotWeight[slot]
        if (w == null) {
            if (c < missingFrom) missingFrom = c
        } else {
            running += cUsdt[c].multiply(w, PortfolioDecimal.MC)
        }
    }

    @Synchronized
    fun anyTime(view: PortfolioScaledParts, test: PortfolioPartTest): Boolean {
        if (testFor !== test) {
            testFor = test
            testScanned = 0
            testFirst = Int.MAX_VALUE
        }
        while (testFirst == Int.MAX_VALUE && testScanned < view.len) {
            if (test.of(slotTime[testScanned])) testFirst = testScanned
            testScanned++
        }
        return testFirst < view.len
    }
}
