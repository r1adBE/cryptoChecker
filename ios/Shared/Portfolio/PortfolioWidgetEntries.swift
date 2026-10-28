import Foundation

/// Eine Buchung unter einem Coin im Portfolio-Widget (Ansicht «Liste», Wunsch 27.10.2026): Bestandsänderung durch diese
/// Buchung (`quantity`, Zugang +, Abgang −, Gebühren im Coin eingerechnet), gekürzte Notiz und heutiger Wert (Menge ×
/// aktueller Kurs in der Anzeigewährung; nil = ohne Kurs). Wie Android `PortfolioWidgetEntry`
/// (Paritäts-Fixture `widget_portfolio_entries.json`).
struct PortfolioWidgetEntry: Codable, Equatable, Sendable {
    var time: Int64
    var quantity: Double
    var note: String?
    var value: Double?
}

/// Welche Buchungen das Listen-Widget unter den Coins zeigt (Wahl beim Einrichten; voreingestellt `off`).
enum PortfolioWidgetEntryFilter: String, CaseIterable, Sendable {
    case off, buys, sells, both

    static let defaultFilter: PortfolioWidgetEntryFilter = .off

    /// Zugänge (Kauf, Einzahlung, Belohnung, Tausch hinein) = Käufe, Abgänge = Verkäufe.
    func accepts(_ entry: PortfolioWidgetEntry) -> Bool {
        switch self {
        case .off: return false
        case .buys: return entry.quantity > 0
        case .sells: return entry.quantity < 0
        case .both: return entry.quantity != 0
        }
    }

    static func from(key: String?) -> PortfolioWidgetEntryFilter {
        key.flatMap(PortfolioWidgetEntryFilter.init(rawValue:)) ?? defaultFilter
    }
}

/// Buchungen je Coin für das Listen-Widget: dieselben Schritte und dieselbe Bestandsregel wie der Rechner
/// (`PortfolioLedger.events`, `PortfolioHoldingsBook`) — alle Zeilen zusammen ergeben genau den Bestand der Kopfzeile.
/// Der Adress-Teil ohne Einstand (`AddressBalance.isAddressPart`) ist keine Buchung und fehlt (sein eigener Topf im Buch
/// berührt die übrigen Zeilen nicht); Eröffnungsposten stehen wie Käufe in der Liste. Wie Android `PortfolioWidgetEntries`.
enum PortfolioWidgetEntries {
    /// Sichtbare Zeichen der Notiz; länger → gekürzt und «…» angehängt.
    static let noteChars = 6
    /// Höchstens so viele Buchungen je Coin; mehr → keine (je Coin alle oder keine).
    static let maxPerCoin = 40

    /// Notiz fürs Widget: ohne Leerraum am Rand (innen zusammengefasst), leer → nil, länger als `noteChars` → «…».
    static func shortNote(_ note: String?) -> String? {
        guard let note else { return nil }
        let text = note.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
        guard !text.isEmpty else { return nil }
        guard text.count > noteChars else { return text }
        let cut = String(text.prefix(noteChars))
        return cut.replacingOccurrences(of: "\\s+$", with: "", options: .regularExpression) + "…"
    }

    /// Buchungen von `coin` aus `trades` ohne Adress-Teil, zeitlich aufsteigend (Notiz aus `PortfolioTx.note`); leer bei mehr als
    /// `maxPerCoin`. Ohne Wert (setzt `withValues`).
    static func of(_ coin: String, trades: [PortfolioTx], method: CostMethod = .defaultMethod) -> [PortfolioWidgetEntry] {
        var book = PortfolioHoldingsBook(method: method)
        var out: [PortfolioWidgetEntry] = []
        for e in PortfolioLedger.events(coin, trades: trades) {
            guard e.trade.amountExact > 0, !AddressBalance.isAddressPart(e.trade.id) else { continue }
            let before = book.total
            book.apply(e)
            let delta = book.total - before
            if delta == 0 { continue }
            out.append(PortfolioWidgetEntry(time: e.trade.time, quantity: nearestDouble(delta),
                                            note: shortNote(e.trade.note), value: nil))
            if out.count > maxPerCoin { return [] }
        }
        return out
    }

    /// Nächstgelegene Double-Zahl zum Dezimalwert (über den Text, wie Kotlin `BigDecimal.toDouble`): `doubleValue` von
    /// NSDecimalNumber liegt teils eine Stelle daneben (0.49 → 0.49000000000000005).
    static func nearestDouble(_ value: Decimal) -> Double {
        Double(value.description) ?? PortfolioDecimal.double(value)
    }

    /// `entries` mit heutigem Wert: Menge × `price` (USDT) × `factor`; ohne Kurs bleibt der Wert leer.
    static func withValues(_ entries: [PortfolioWidgetEntry], price: Double?, factor: Double) -> [PortfolioWidgetEntry] {
        let p = price.flatMap { $0.isFinite && $0 > 0 ? $0 : nil }
        return entries.map { e in
            var e = e
            e.value = p.map { e.quantity * $0 * factor }
            return e
        }
    }

    /// Werte der Buchungen umgerechnet (USDT ↔ Anzeigewährung), wie die Werte der Positionen.
    static func scaled(_ entries: [PortfolioWidgetEntry]?, _ convert: (Double) -> Double) -> [PortfolioWidgetEntry]? {
        entries?.map { e in
            var e = e
            e.value = e.value.map(convert)
            return e
        }
    }

    /// Positionen mit ihren Buchungen (heutiger Wert mit dem Kurs aus `current` und `factor`) — wie Android `withEntries`.
    static func withEntries(_ positions: [PortfolioWidgetPosition]?, trades: [PortfolioTx], current: [String: Double],
                            factor: Double, method: CostMethod) -> [PortfolioWidgetPosition]? {
        guard let positions, !positions.isEmpty else { return positions }
        var prices: [String: Double] = [:]
        for (coin, price) in current { prices[PortfolioCalculator.normalizeCoin(coin)] = price }
        return positions.map { p in
            var p = p
            let coin = PortfolioCalculator.normalizeCoin(p.symbol)
            p.entries = withValues(of(coin, trades: trades, method: method), price: prices[coin], factor: factor)
            return p
        }
    }

    /// Welche Buchungen unter den gezeigten Coins Platz haben: je Coin die zur Wahl passenden — alle oder keine —,
    /// solange sie in `available` passen (`rowHeight` je Zeile). Coins haben Vorrang. Wie Android `assign`.
    static func assign(_ perCoin: [[PortfolioWidgetEntry]?], filter: PortfolioWidgetEntryFilter, available: Double,
                       rowHeight: Double) -> [[PortfolioWidgetEntry]] {
        guard filter != .off, rowHeight > 0 else { return perCoin.map { _ in [] } }
        var left = available
        return perCoin.map { entries in
            let shown = (entries ?? []).filter(filter.accepts)
            let need = Double(shown.count) * rowHeight
            guard !shown.isEmpty, need <= left + 1e-6 else { return [] }
            left -= need
            return shown
        }
    }

    /// Summe der Mengen (Prüfung: bei «beides» = Bestand).
    static func total(_ entries: [PortfolioWidgetEntry]) -> Decimal {
        entries.reduce(Decimal(0)) { $0 + (PortfolioDecimal.of($1.quantity) ?? 0) }
    }
}
