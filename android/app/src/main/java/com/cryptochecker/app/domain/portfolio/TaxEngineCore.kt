package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Art eines Schritts der Steuer-Rechnung ([TaxEngines]). */
internal enum class TaxStepKind {
    /** Zugang (Kauf, Belohnung, Einzahlung, Zugang aus Tausch). */
    ACQUIRE,
    /** Veräusserung (Verkauf, Tausch, Gebühr im dritten Coin). */
    DISPOSE,
    /** Auszahlung: Menge und Einstand verlassen den Topf, keine Veräusserung. */
    REMOVE,
    /** Netzwerkgebühr im Coin (Umbuchung): Menge sinkt, Einstand bleibt. */
    NETWORK_FEE,
    /** Gebühr USDT + Wert der Gebühr im dritten Coin einer Umbuchung: erhöht den Einstand. */
    ADD_FEE,
}

/**
 * Ein Schritt eines Coins, abgeleitet aus [PortfolioLedgerEvent] mit denselben Regeln wie der Ø in [PortfolioCalculator]:
 * Zugang = erhaltene Menge mit Einstand (Menge × Preis + Gebühr USDT + Wert der Gebühr im dritten Coin; Tausch: Wert des
 * Tauschs), Abgang = Menge + Gebühr im Coin mit Erlös (Menge × Preis bzw. Wert des Tauschs − Gebühr USDT − Wert der Gebühr
 * im dritten Coin), Gebühr-Abgang zum Gebühr-Preis.
 */
internal class TaxStep(
    val kind: TaxStepKind,
    val trade: PortfolioTrade,
    val qty: BigDecimal,
    /** Zugang: Einstand (null = ohne Kaufpreis); [TaxStepKind.ADD_FEE]: Betrag. */
    val cost: BigDecimal? = null,
    /** Zugang: Erwerb ([PortfolioTrade.acquisitionTime]). */
    val acquired: Long = trade.time,
    /** Abgang: Erlös netto; null = unbekannt. */
    val proceeds: BigDecimal? = null,
    /** Abgang: Erlös vor Gebühren. */
    val gross: BigDecimal? = null,
    val feeLeg: Boolean = false,
    val swap: Boolean = false,
    val feeMissing: Boolean = false,
    /** Schenkung als Veräusserung ([PortfolioTrade.fromGift]) bzw. an Ehe-/Lebenspartner ([spouseGift]). */
    val gift: Boolean = false,
    /** UK/CA: Schenkung an Ehe-/Lebenspartner — Erlös je Teil = sein Einstand, kein Gewinn/Verlust ([emit]). */
    val spouseGift: Boolean = false,
    /** Zahlung mit Krypto ([PortfolioTxType.PAYMENT]). */
    val payment: Boolean = false,
    /** Erlös aus geschätztem Wert ([PortfolioTrade.valueEstimated]). */
    val estimated: Boolean = false,
) {
    val time: Long get() = trade.time
    val id: Long get() = trade.id
}

/** Ergebnis von [TaxPool.take]: abgegebene Menge, Teil mit Kaufpreis, abgegebener Einstand samt Herkunft. */
internal class TaxTaken(
    val removed: BigDecimal,
    val removedPriced: BigDecimal,
    val cost: BigDecimal,
    val parts: List<PortfolioCostPart>,
) {
    val removedUnpriced: BigDecimal get() = removed - removedPriced
}

/**
 * Durchschnitts-Topf (Menge mit/ohne Kaufpreis, Einstand mit Herkunft je Zeitpunkt) — dieselbe Rechnung wie der Ø in
 * [PortfolioCalculator]: ein Abgang nimmt beide Teile anteilig und den Einstand zum Durchschnitt mit; die Herkunftsteile
 * ([PortfolioCostPart]) erlauben die Umrechnung jedes Zugangs zum Devisenkurs seines Datums.
 */
internal class TaxPool(
    /**
     * Zeitzone der Kalendertage: Herkunftsteile desselben Tags werden zusammengefasst (gleicher Devisenkurs; die Liste
     * wächst mit den Tagen statt mit den Zugängen — kein O(Zugänge × Abgänge)). null = jeder Teil einzeln.
     */
    private val zone: ZoneId? = null,
) {
    var priced: BigDecimal = BigDecimal.ZERO
        private set
    var unpriced: BigDecimal = BigDecimal.ZERO
        private set
    var cost: BigDecimal = BigDecimal.ZERO
        private set
    val parts = ArrayList<PortfolioCostPart>()
    /** Mit [zone]: Tag → Index in [parts] (erster Zeitpunkt des Tags bleibt). */
    private val dayIndex = HashMap<LocalDate, Int>()
    val held: BigDecimal get() = priced + unpriced

    /** Zugang [qty] mit Einstand [cost] (null = ohne Kaufpreis), Herkunft [time]. */
    fun add(qty: BigDecimal, cost: BigDecimal?, time: Long) {
        if (qty.signum() <= 0) return
        if (cost == null) {
            unpriced += qty
            return
        }
        priced += qty
        addCost(time, cost)
    }

    /** Zugang mit fortgeführtem Einstand (Herkunftsteile [from]). */
    fun addCarried(qty: BigDecimal, from: List<PortfolioCostPart>) {
        if (qty.signum() <= 0) return
        priced += qty
        from.forEach { addCost(it.time, it.usdt) }
    }

    /** Einstand direkt zum Teil mit Kaufpreis (z. B. nicht abziehbarer Verlust). */
    fun addCost(time: Long, usdt: BigDecimal) {
        cost += usdt
        if (usdt.signum() == 0) return
        val z = zone
        if (z == null) {
            parts += PortfolioCostPart(time, usdt)
            return
        }
        val day = Instant.ofEpochMilli(time).atZone(z).toLocalDate()
        val i = dayIndex[day]
        if (i != null) {
            parts[i] = PortfolioCostPart(parts[i].time, parts[i].usdt + usdt)
        } else {
            dayIndex[day] = parts.size
            parts += PortfolioCostPart(time, usdt)
        }
    }

    /** Alle Herkunftsteile entfernen (samt Tag-Index). */
    private fun clearParts() {
        parts.clear()
        dayIndex.clear()
    }

    /**
     * Gebühr einer Umbuchung: anteilig (Menge mit Kaufpreis / Bestand) zum Einstand, wie der Ø. Rückgabe = Anteil des
     * Teils ohne Kaufpreis (ohne Teil mit Kaufpreis: die ganze Gebühr) — der Aufrufer vermerkt ihn als
     * [PortfolioUnassignedFee]; 0 = alles zugeordnet bzw. kein Bestand.
     */
    fun addFee(time: Long, extra: BigDecimal): BigDecimal {
        val h = held
        if (extra.signum() <= 0 || h.signum() <= 0) return BigDecimal.ZERO
        if (priced.signum() <= 0) return extra
        val assigned = if (unpriced.signum() == 0) extra else extra.multiply(priced).divide(h, PortfolioDecimal.MC)
        addCost(time, assigned)
        return extra - assigned
    }

    /** Übernimmt den ganzen Inhalt von [other] (danach leer). */
    fun absorb(other: TaxPool) {
        priced += other.priced
        unpriced += other.unpriced
        // Einstand unverändert, Herkunft je Teil (mit [zone] nach Tag zusammengefasst)
        val c = cost
        other.parts.forEach { addCost(it.time, it.usdt) }
        cost = c + other.cost
        other.priced = BigDecimal.ZERO
        other.unpriced = BigDecimal.ZERO
        other.cost = BigDecimal.ZERO
        other.clearParts()
    }

    /** Gibt [requested] ab (gekappt auf den Bestand), beide Teile anteilig samt Einstand. */
    fun take(requested: BigDecimal): TaxTaken {
        val zero = BigDecimal.ZERO
        val mc = PortfolioDecimal.MC
        val h = held
        val removed = requested.min(h).max(zero)
        if (removed.signum() <= 0) return TaxTaken(zero, zero, zero, emptyList())
        val allPriced = unpriced.signum() == 0
        val removedPriced = if (allPriced) removed else PortfolioDecimal.amount(removed.multiply(priced).divide(h, mc)).min(priced)
        val costRemoved = when {
            priced.signum() == 0 -> zero
            removedPriced >= priced -> cost
            else -> cost.multiply(removedPriced).divide(priced, mc)
        }
        val partsRemoved = takeParts(costRemoved)
        val rest = h - removed
        priced -= removedPriced
        cost -= costRemoved
        if (PortfolioDecimal.isDust(priced) || priced.signum() < 0) { priced = zero; cost = zero; clearParts() }
        unpriced = (rest - priced).max(zero)
        if (PortfolioDecimal.isDust(unpriced)) unpriced = zero
        return TaxTaken(removed, removedPriced, costRemoved, partsRemoved)
    }

    /**
     * Netzwerkgebühr: Menge sinkt anteilig, der Einstand bleibt im Rest. Bleibt kein Teil mit Kaufpreis, geht der
     * Einstand nicht still verloren: Rückgabe = dieser Einstand (Aufrufer vermerkt [PortfolioLostFeeCost]), sonst 0.
     */
    fun dropQty(qty: BigDecimal): BigDecimal {
        val h = held
        if (qty.signum() <= 0 || h.signum() <= 0) return BigDecimal.ZERO
        val removed = qty.min(h)
        val removedPriced = if (unpriced.signum() == 0) removed
            else PortfolioDecimal.amount(removed.multiply(priced).divide(h, PortfolioDecimal.MC)).min(priced)
        priced -= removedPriced
        var lost = BigDecimal.ZERO
        if (PortfolioDecimal.isDust(priced) || priced.signum() < 0) {
            lost = cost
            priced = BigDecimal.ZERO; cost = BigDecimal.ZERO; clearParts()
        }
        unpriced = (h - removed - priced).max(BigDecimal.ZERO)
        if (PortfolioDecimal.isDust(unpriced)) unpriced = BigDecimal.ZERO
        return lost
    }

    /** Nimmt [removed] Einstand anteilig aus allen Herkunftsteilen; Summe exakt [removed]. */
    private fun takeParts(removed: BigDecimal): List<PortfolioCostPart> {
        if (removed.signum() == 0 || parts.isEmpty()) return emptyList()
        if (removed >= cost) {
            val all = parts.toList()
            clearParts()
            return all
        }
        val out = ArrayList<PortfolioCostPart>(parts.size)
        var left = removed
        for (i in parts.indices) {
            val p = parts[i]
            val r = if (i == parts.size - 1) left else p.usdt.multiply(removed).divide(cost, PortfolioDecimal.MC)
            left -= r
            out += PortfolioCostPart(p.time, r)
            parts[i] = PortfolioCostPart(p.time, p.usdt - r)
        }
        return out
    }
}

/** Ein Teil einer Veräusserung vor der Erlös-Verteilung. */
internal class TaxPiece(
    val amount: BigDecimal,
    val cost: BigDecimal?,
    val parts: List<PortfolioCostPart> = emptyList(),
    val acquiredTime: Long? = null,
    val lotTxId: Long? = null,
    val lotKind: PortfolioTxType? = null,
    val match: TaxMatch? = null,
    val matchedTime: Long? = null,
    val uncovered: Boolean = false,
    val oldHolding: Boolean? = null,
)
