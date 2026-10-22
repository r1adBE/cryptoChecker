import Foundation

/// Art eines Gebühr-Aufwands (`PortfolioFeeExpenses`) — wie Android.
enum PortfolioFeeExpense: String, Sendable, Equatable {
    /// Gebühr im Coin verbraucht den ganzen Zugang (Kauf/Belohnung/Einzahlung bzw. erhaltener Coin eines Tauschs).
    case CONSUMED
    /// Gebühr USDT + Wert der Gebühr im dritten Coin einer Auszahlung.
    case WITHDRAWAL
    /// Nur Steuer-Vorlagen mit `TaxEventRules.quoteFeeIsInfo` (Build 57): Gebühr USDT einer Umbuchung zwischen eigenen Wallets
    /// — nicht im Einstand, Zeile nur zur Information (`TaxEngines.disposals`). Das Portfolio rechnet sie weiter zum Einstand.
    case TRANSFER
}

/// Gebühr als Aufwand — für alle Methoden gleich, Regeln exakt wie `PortfolioFeeExpenses` in `PortfolioFeeExpenses.kt`:
///  - `.CONSUMED`: Gebühr im Coin ≥ Menge (Kauf/Belohnung/Einzahlung mit Preis): Aufwand = Menge × Preis + Gebühr USDT +
///    Wert der Gebühr im dritten Coin. Tausch mit Gebühr im erhaltenen Coin ≥ erhaltener Menge: Aufwand = Wert des Tauschs
///    (der abgegebene Coin bucht ihn als Erlös, der Aufwand hebt ihn auf — kein Schein-Gewinn). Ohne Preis/Wert: keiner.
///  - `.WITHDRAWAL`: Gebühr USDT + Wert der Gebühr im dritten Coin einer Auszahlung (nur mit Bestand; reicht er nicht,
///    zum gedeckten Anteil `PortfolioLedger.withdrawnShare` wie der Abfluss im Verlauf).
/// Je Fall eine Veräusserung: Einstand = Aufwand, Erlös 0, Gewinn = −Aufwand, Haltedauer 0.
enum PortfolioFeeExpenses {

    /// Aufwand (USDT) und «Wert der Gebühr im dritten Coin fehlt».
    struct Expense {
        let usdt: Decimal
        let feeValueMissing: Bool
    }

    /// Gebühr verbraucht den ganzen Zugang `e`; nil = nicht so oder ohne Preis/Wert.
    static func consumed(_ e: PortfolioLedgerEvent) -> Expense? {
        let t = e.trade
        if e.feeLeg || t.amountExact <= 0 { return nil }
        if e.swapIn {
            if t.swapToCoin == nil || t.swapReceived > 0 { return nil }
            return t.swapValue.map { Expense(usdt: $0, feeValueMissing: false) }
        }
        if !t.type.isInflow || t.receivedExact > 0 { return nil }
        guard let price = t.validPriceExact else { return nil }
        let feeValue = t.thirdFeeValue
        let usdt: Decimal = t.amountExact * price + t.quoteFeeExact + (feeValue ?? 0)
        return Expense(usdt: usdt, feeValueMissing: feeValue == nil)
    }

    /// Auszahlung bei Bestand `holdings`: Gebühr USDT + Wert der Gebühr im dritten Coin zum gedeckten Anteil
    /// (`PortfolioLedger.withdrawnShare`); nil = keine Gebühr oder nichts abgegeben.
    static func withdrawal(_ t: PortfolioTx, holdings: Decimal) -> Expense? {
        // Auszahlung und Verschenkt (wie eine Auszahlung)
        if !t.type.isWithdrawalLike || t.amountExact <= 0 { return nil }
        guard let share = PortfolioLedger.withdrawnShare(t, holdings: holdings) else { return nil }
        let feeValue = t.thirdFeeValue
        let full: Decimal = t.quoteFeeExact + (feeValue ?? 0)
        if full <= 0 && feeValue != nil { return nil }
        let usdt = share == 1 ? full : PortfolioDecimal.multiply(full, share)
        return Expense(usdt: usdt, feeValueMissing: feeValue == nil)
    }

    /// Gebühr einer Auszahlung (Gebühr USDT + Wert der Gebühr im dritten Coin), die auf den nicht erfassten Teil fällt:
    /// ganze Gebühr minus `withdrawal` (gedeckter Anteil), auch bei leerem Bestand die ganze Gebühr. Sie gehört zum
    /// unbekannten Einstand dieser Coins — vermerkt als `PortfolioUnassignedFee` statt still verloren, nicht realisiert.
    /// 0 = nichts zu vermerken (ganz gedeckt, keine Gebühr oder Wert der Gebühr unbekannt). Wie Android.
    static func withdrawalUncovered(_ t: PortfolioTx, holdings: Decimal) -> Decimal {
        if !t.type.isWithdrawalLike || t.amountExact <= 0 { return 0 }
        let full: Decimal = t.quoteFeeExact + (t.thirdFeeValue ?? 0)
        if full <= 0 { return 0 }
        return max(0, full - (withdrawal(t, holdings: holdings)?.usdt ?? 0))
    }

    /// Menge der Zeile: verbrauchter Zugang (Tausch: erhaltene Menge), Auszahlung 0 (Gebühr in USDT).
    private static func amountOf(_ e: PortfolioLedgerEvent, _ kind: PortfolioFeeExpense) -> Decimal {
        if kind == .WITHDRAWAL || kind == .TRANSFER { return 0 }
        if e.swapIn { return e.trade.toAmountExact ?? 0 }
        return e.trade.amountExact
    }

    static func disposal(_ symbol: String, _ e: PortfolioLedgerEvent, _ kind: PortfolioFeeExpense, _ x: Expense,
                         method: CostMethod, wallet: String? = nil) -> PortfolioDisposal {
        let t = e.trade
        return PortfolioDisposal(
            coin: symbol, sellTxId: t.id, sellTime: t.time,
            lotTxId: t.id, acquiredTime: t.time, lotKind: e.swapIn ? .SWAP : t.type,
            amount: amountOf(e, kind), costUsdt: x.usdt, proceedsUsdt: 0, gainUsdt: -x.usdt,
            holdingDays: 0, feeLeg: false, feeValueMissing: x.feeValueMissing, method: method,
            wallet: wallet, feeExpense: kind)
    }

    /// Alle Gebühr-Aufwände eines Coins (eigene Rechnungen der Steuer-Vorlagen, `TaxEngines`); Auszahlung nur mit Bestand
    /// (`PortfolioHoldingsBook`, ein Topf). `swaps` false: ohne den verbrauchten Zugang eines Tauschs.
    static func of(_ symbol: String, _ events: [PortfolioLedgerEvent], swaps: Bool = true) -> [PortfolioDisposal] {
        var out: [PortfolioDisposal] = []
        var book = PortfolioHoldingsBook(method: .FIFO)
        for e in events {
            if let x = consumed(e), swaps || !e.swapIn {
                out.append(disposal(symbol, e, .CONSUMED, x, method: .FIFO))
            }
            if !e.feeLeg && !e.swapIn && book.cap(for: e) > 0, let x = withdrawal(e.trade, holdings: book.cap(for: e)) {
                out.append(disposal(symbol, e, .WITHDRAWAL, x, method: .FIFO))
            }
            book.apply(e)
        }
        return out
    }
}
