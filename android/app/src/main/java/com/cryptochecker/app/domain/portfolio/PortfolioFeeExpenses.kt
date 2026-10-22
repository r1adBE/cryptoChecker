package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal

/** Art eines Gebühr-Aufwands ([PortfolioFeeExpenses]). */
enum class PortfolioFeeExpense {
    /** Gebühr im Coin verbraucht den ganzen Zugang (Kauf/Belohnung/Einzahlung bzw. erhaltener Coin eines Tauschs). */
    CONSUMED,
    /** Gebühr USDT + Wert der Gebühr im dritten Coin einer Auszahlung. */
    WITHDRAWAL,
    /**
     * Nur Steuer-Vorlagen mit [TaxEventRules.quoteFeeIsInfo] (Build 57): Gebühr USDT einer Umbuchung zwischen eigenen
     * Wallets — nicht im Einstand, Zeile nur zur Information ([TaxEngines.disposals]). Das Portfolio rechnet sie weiter
     * zum Einstand.
     */
    TRANSFER,
}

/**
 * Gebühr als Aufwand — für alle Methoden gleich (Spiegel in `PortfolioFeeExpenses.swift`):
 *  - [PortfolioFeeExpense.CONSUMED]: Die Gebühr im Coin verbraucht den ganzen Zugang. Kauf/Belohnung/Einzahlung mit
 *    Preis und Gebühr im Coin ≥ Menge: Aufwand = Menge × Preis + Gebühr USDT + Wert der Gebühr im dritten Coin. Tausch mit
 *    Gebühr im erhaltenen Coin ≥ erhaltener Menge: Aufwand = Wert des Tauschs — der abgegebene Coin bucht den Wert als
 *    Erlös, der Aufwand hebt ihn auf (kein Schein-Gewinn: realisiert = −Einstand des abgegebenen Coins). Das Blatt sperrt
 *    solche Eingaben ([PortfolioTxForm.feeConsumesReceived]), der CSV-Import überspringt sie; bestehende Daten rechnen so.
 *    Ohne Preis bzw. Wert: kein Aufwand (der Erlös des Tauschs ist dann ebenfalls unbekannt).
 *  - [PortfolioFeeExpense.WITHDRAWAL]: Gebühr USDT + Wert der Gebühr im dritten Coin einer Auszahlung (nur mit Bestand;
 *    reicht er nicht, zum gedeckten Anteil [PortfolioLedger.withdrawnShare] wie der Abfluss im Verlauf)
 *    — vorher hing sie an nichts; eine Umbuchungsgebühr erhöht dagegen den Einstand (Coins bleiben im Portfolio).
 * Je Fall eine Veräusserung ([PortfolioDisposal.feeExpense]): Einstand = Aufwand, Erlös 0, Gewinn = −Aufwand, Erwerb =
 * Zeit der Transaktion (Haltedauer 0). Zählt im realisierten Gewinn und im Steuer-Export (Vermerk «Gebühr verbraucht den
 * ganzen Zugang» bzw. «Gebühr Auszahlung»). «Heute/7 T/1 J» und der Wertverlauf ([PortfolioLedger.flow]) zählen dieselbe
 * Gebühr schon als Verlust (Zufluss ohne Bestand bzw. Abfluss um die Gebühr kleiner).
 */
internal object PortfolioFeeExpenses {

    /** Aufwand (USDT) und «Wert der Gebühr im dritten Coin fehlt». */
    class Expense(val usdt: BigDecimal, val feeValueMissing: Boolean)

    /** Gebühr verbraucht den ganzen Zugang [e]; null = nicht so oder ohne Preis/Wert. */
    fun consumed(e: PortfolioLedgerEvent): Expense? {
        val t = e.trade
        if (e.feeLeg || t.amount.signum() <= 0) return null
        if (e.swapIn) {
            if (t.swapToCoin == null || t.swapReceived.signum() > 0) return null
            return t.swapValue?.let { Expense(it, false) }
        }
        if (!t.type.isInflow || t.received.signum() > 0) return null
        val price = t.validPrice ?: return null
        val feeValue = t.thirdFeeValue
        return Expense(t.amount * price + t.quoteFee + (feeValue ?: BigDecimal.ZERO), feeValue == null)
    }

    /**
     * Auszahlung bei Bestand [holdings]: Gebühr USDT + Wert der Gebühr im dritten Coin zum gedeckten Anteil
     * ([PortfolioLedger.withdrawnShare]); null = keine Gebühr oder nichts abgegeben.
     */
    fun withdrawal(t: PortfolioTrade, holdings: BigDecimal): Expense? {
        // Auszahlung und Verschenkt (wie eine Auszahlung)
        if (!t.type.isWithdrawalLike || t.amount.signum() <= 0) return null
        val share = PortfolioLedger.withdrawnShare(t, holdings) ?: return null
        val feeValue = t.thirdFeeValue
        val full = t.quoteFee + (feeValue ?: BigDecimal.ZERO)
        if (full.signum() <= 0 && feeValue != null) return null
        val usdt = if (share.compareTo(BigDecimal.ONE) == 0) full else full.multiply(share, PortfolioDecimal.MC)
        return Expense(usdt, feeValue == null)
    }

    /**
     * Gebühr einer Auszahlung (Gebühr USDT + Wert der Gebühr im dritten Coin), die auf den nicht erfassten Teil fällt:
     * ganze Gebühr minus [withdrawal] (gedeckter Anteil), auch bei leerem Bestand die ganze Gebühr. Sie gehört zum
     * unbekannten Einstand dieser Coins — vermerkt als [PortfolioUnassignedFee] statt still verloren, nicht realisiert.
     * 0 = nichts zu vermerken (ganz gedeckt, keine Gebühr oder Wert der Gebühr unbekannt).
     */
    fun withdrawalUncovered(t: PortfolioTrade, holdings: BigDecimal): BigDecimal {
        if (!t.type.isWithdrawalLike || t.amount.signum() <= 0) return BigDecimal.ZERO
        val full = t.quoteFee + (t.thirdFeeValue ?: BigDecimal.ZERO)
        if (full.signum() <= 0) return BigDecimal.ZERO
        return (full - (withdrawal(t, holdings)?.usdt ?: BigDecimal.ZERO)).max(BigDecimal.ZERO)
    }

    /**
     * Seit Build 104: Gebühr einer Umbuchung (Gebühr USDT + Wert der Gebühr im dritten Coin), die auf den nicht erfassten
     * Teil der Menge fällt: Gebühr × max(Menge − [covered], 0) / Menge, bei leerem Bestand die ganze Gebühr. Wie
     * [withdrawalUncovered] als [PortfolioUnassignedFee] vermerkt statt still verworfen; der Rest verteilt sich wie bisher.
     * [covered]: universell der Bestand vor der Umbuchung, je Wallet die verschobene Menge.
     */
    fun transferUncovered(t: PortfolioTrade, covered: BigDecimal): BigDecimal {
        val amount = t.amount
        if (t.type != PortfolioTxType.TRANSFER || amount.signum() <= 0 || covered >= amount) return BigDecimal.ZERO
        val full = t.quoteFee + (t.thirdFeeValue ?: BigDecimal.ZERO)
        if (full.signum() <= 0) return BigDecimal.ZERO
        if (covered.signum() <= 0) return full
        return full.multiply(amount - covered).divide(amount, PortfolioDecimal.MC)
    }

    /** Menge der Zeile: verbrauchter Zugang (Tausch: erhaltene Menge), Auszahlung und Umbuchung 0 (Gebühr in USDT). */
    private fun amountOf(e: PortfolioLedgerEvent, kind: PortfolioFeeExpense): BigDecimal = when {
        kind == PortfolioFeeExpense.WITHDRAWAL || kind == PortfolioFeeExpense.TRANSFER -> BigDecimal.ZERO
        e.swapIn -> e.trade.toAmount ?: BigDecimal.ZERO
        else -> e.trade.amount
    }

    fun disposal(
        symbol: String,
        e: PortfolioLedgerEvent,
        kind: PortfolioFeeExpense,
        x: Expense,
        method: CostMethod,
        wallet: String? = null,
    ): PortfolioDisposal {
        val t = e.trade
        return PortfolioDisposal(
            coin = symbol, sellTxId = t.id, sellTime = t.time,
            lotTxId = t.id, acquiredTime = t.time, lotKind = if (e.swapIn) PortfolioTxType.SWAP else t.type,
            amount = amountOf(e, kind), costUsdt = x.usdt, proceedsUsdt = BigDecimal.ZERO, gainUsdt = x.usdt.negate(),
            holdingDays = 0L, feeLeg = false, feeValueMissing = x.feeValueMissing, method = method,
            wallet = wallet, feeExpense = kind,
        )
    }

    /**
     * Alle Gebühr-Aufwände eines Coins (eigene Rechnungen der Steuer-Vorlagen, [TaxEngines]); Auszahlung nur mit Bestand
     * ([PortfolioHoldingsBook], ein Topf). [CostMethod.FIFO] wie die Posten-Teile dort. [swaps] false: ohne den verbrauchten
     * Zugang eines Tauschs (Vorlagen, bei denen der Tausch keine Veräusserung ist).
     */
    fun of(symbol: String, events: List<PortfolioLedgerEvent>, swaps: Boolean = true): List<PortfolioDisposal> {
        val out = ArrayList<PortfolioDisposal>()
        val book = PortfolioHoldingsBook(CostMethod.FIFO)
        for (e in events) {
            consumed(e)?.takeIf { swaps || !e.swapIn }?.let { out += disposal(symbol, e, PortfolioFeeExpense.CONSUMED, it, CostMethod.FIFO) }
            if (!e.feeLeg && !e.swapIn && book.capFor(e).signum() > 0) {
                withdrawal(e.trade, book.capFor(e))?.let { out += disposal(symbol, e, PortfolioFeeExpense.WITHDRAWAL, it, CostMethod.FIFO) }
            }
            book.apply(e)
        }
        return out
    }
}
