import Foundation

// Zugänge ohne Einstand, die der Nutzer ergänzen kann — wie `PortfolioCostGaps.kt`.

/// Wie eine Lücke beim Einstand zu schliessen ist (`PortfolioCostGaps.kind`) — wie Android:
///  - `EDITABLE`: Kauf/Einzahlung ohne Preis — Einstand erfassen (Vorschlag: Tagesschluss) oder bewusst unbekannt lassen.
///  - `INCOME_RECEIPT_VALUE`: Belohnung/Airdrop ohne Wert — Einstand = Wert beim Zufluss (Einkommen); der Steuer-Export
///    rechnet ihn schon zum Tagesschluss (`PortfolioLedger.valueRewardsAtDayClose`). Kein Angebot, ihn zu ändern.
///  - `INCOME_ZERO`: AT, Belohnung ohne Zins (Anschaffungskosten 0, § 27b Abs. 2 EStG) — ebenfalls nicht zu ändern.
enum CostGapKind: String, Sendable {
    case EDITABLE, INCOME_RECEIPT_VALUE, INCOME_ZERO
}

/// Ein Zugang ohne Einstand und wie er zu behandeln ist.
struct PortfolioCostGap: Equatable, Sendable {
    let trade: PortfolioTx
    let kind: CostGapKind
}

/// Zugänge ohne Einstand («Einträge ohne Einstand»): Kauf, Einzahlung (auch aus einem CSV-Import ohne Preis) und
/// Belohnung ohne Preis. Ein erfasster Einstand kommt ins Preisfeld der Transaktion (`priceExact`, Preis je Coin) — kein
/// neues Feld. Adress-Einträge sind keine Lücken dieser Art (dafür `AddressCostLots`). Wie Android.
enum PortfolioCostGaps {

    /// Eigener Zugang ohne gültigen Preis mit Menge > 0?
    static func isGap(_ t: PortfolioTx) -> Bool {
        !AddressBalance.isAddressTrade(t.id) && t.amountExact > 0 && t.validPriceExact == nil
            && (t.type == .BUY || t.type == .DEPOSIT || t.type == .REWARD)
    }

    /// Art der Lücke nach der Steuer-Vorlage (nur AT rechnet Belohnungen ohne Zins mit 0).
    static func kind(_ t: PortfolioTx, template: TaxTemplate) -> CostGapKind {
        if t.type != .REWARD { return .EDITABLE }
        if template == .AT && !t.interest { return .INCOME_ZERO }
        return .INCOME_RECEIPT_VALUE
    }

    /// Alle Lücken, neueste zuerst (gleiche Zeit: höhere Id zuerst, wie die Transaktionsliste).
    static func gaps(_ trades: [PortfolioTx], template: TaxTemplate) -> [PortfolioCostGap] {
        trades.filter(isGap)
            .sorted { ($0.time, $0.id) > ($1.time, $1.id) }
            .map { PortfolioCostGap(trade: $0, kind: kind($0, template: template)) }
    }

    /// Offene Lücken für die Karte: erfassbar und nicht bewusst «unbekannt gelassen» (`acknowledged` = Ids).
    static func pending(_ gaps: [PortfolioCostGap], acknowledged: Set<Int64>) -> [PortfolioCostGap] {
        gaps.filter { $0.kind == .EDITABLE && !acknowledged.contains($0.trade.id) }
    }

    /// «Unbekannt gelassen»-Merker ohne Lücke mehr (gelöscht oder inzwischen mit Einstand) — weg damit.
    static func prune(_ acknowledged: Set<Int64>, gaps: [PortfolioCostGap]) -> Set<Int64> {
        acknowledged.intersection(Set(gaps.map(\.trade.id)))
    }

    /// Vorschlag Einstand gesamt = Menge × Tagesschluss (> 0) — genau das, was der Preis je Coin = Tagesschluss im Rechner
    /// ergibt; sonst nil.
    static func suggestion(_ t: PortfolioTx, close: Decimal?) -> Decimal? {
        guard let close, close > 0, t.amountExact > 0 else { return nil }
        return PortfolioDecimal.multiply(t.amountExact, close)
    }

    /// Preis je Coin aus einem Einstand gesamt (≥ 0) für die Menge (> 0), 34 Stellen; sonst nil.
    static func priceFromTotal(_ total: Decimal?, amount: Decimal) -> Decimal? {
        guard let total, total >= 0, amount > 0 else { return nil }
        return PortfolioDecimal.divide(total, amount)
    }
}
