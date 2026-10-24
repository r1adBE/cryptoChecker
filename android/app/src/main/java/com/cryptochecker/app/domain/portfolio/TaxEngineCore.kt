package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
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
 * ([PortfolioCostPart], geführt von [PortfolioCostPartPool]: je Abgang O(1)) erlauben die Umrechnung jedes Zugangs zum
 * Devisenkurs seines Datums.
 */
internal class TaxPool(
    /**
     * Zeitzone der Kalendertage: Herkunftsteile desselben Tags werden zusammengefasst (gleicher Devisenkurs; die Liste
     * wächst mit den Tagen statt mit den Zugängen). null = jeder Teil einzeln.
     */
    zone: ZoneId? = null,
) {
    var priced: BigDecimal = BigDecimal.ZERO
        private set
    var unpriced: BigDecimal = BigDecimal.ZERO
        private set
    var cost: BigDecimal = BigDecimal.ZERO
        private set
    private val costParts = PortfolioCostPartPool(zone)

    /** Offene Herkunftsteile (Summe exakt [cost]). */
    val parts: List<PortfolioCostPart> get() = costParts.toList(cost)
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
        costParts.add(time, usdt)
    }

    /** Alle Herkunftsteile entfernen (samt Tag-Index). */
    private fun clearParts() = costParts.clear()

    /**
     * Gebühr [extra] einer Umbuchung der Menge [amount] mit Netzwerkgebühr [coinFee] (vor dem Schritt
     * [TaxStepKind.NETWORK_FEE]), wie der Ø des Portfolios: zuerst der Anteil der nicht gedeckten Menge (gedeckt =
     * Bestand − Netzwerkgebühr, wie FIFO je Wallet, [PortfolioFeeExpenses.uncoveredShare]; leerer Pool: die ganze Gebühr),
     * der Rest anteilig (Menge mit Kaufpreis / Bestand) zum Einstand. Rückgabe = nicht gedeckter Anteil + Anteil des Teils
     * ohne Kaufpreis (ohne Teil mit Kaufpreis: der ganze Rest) — der Aufrufer vermerkt ihn als [PortfolioUnassignedFee];
     * 0 = alles zugeordnet.
     */
    fun addFee(time: Long, extra: BigDecimal, amount: BigDecimal, coinFee: BigDecimal): BigDecimal {
        if (extra.signum() <= 0) return BigDecimal.ZERO
        val h = held
        val rest = extra - PortfolioFeeExpenses.uncoveredShare(extra, amount, (h - coinFee).max(BigDecimal.ZERO))
        if (rest.signum() <= 0 || priced.signum() <= 0) return extra
        val assigned = if (unpriced.signum() == 0) rest else rest.multiply(priced).divide(h, PortfolioDecimal.MC)
        addCost(time, assigned)
        return extra - assigned
    }

    /** Übernimmt den ganzen Inhalt von [other] (danach leer). */
    fun absorb(other: TaxPool) {
        priced += other.priced
        unpriced += other.unpriced
        // Einstand unverändert, Herkunft je Teil (mit Zeitzone nach Tag zusammengefasst)
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

    /** Nimmt [removed] Einstand anteilig aus allen Herkunftsteilen; Summe exakt [removed] (Ansicht, O(1)). */
    private fun takeParts(removed: BigDecimal): List<PortfolioCostPart> = costParts.take(removed, cost)
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
