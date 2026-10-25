import CoreGraphics
import Foundation

/// Zeilen, die das Merkliste-Widget höchstens zeigt. Logos und «wird veraltet»-Zeitpunkte nur für diese laden:
/// Mit 2000 Paaren in «Alle» lasen sonst bis zu 2000 Logo-Dateien (je ein UIImage) in eine Erweiterung mit
/// rund 30 MB Speicher und kurzer Zeichenfrist — für höchstens 10 sichtbare Zeilen.
enum WidgetRows {
    /// Grosse Grösse: 10 Zeilen (mittel 4, klein 1 — alle zeigen die ersten Paare der Liste).
    static let maxListRows = 10
    static let mediumListRows = 4

    /// Höhe einer Zeile des Paarnamens bzw. Kurses (14 pt) in der Grundgrösse; um so viel × (Faktor − 1) wird eine
    /// Zeile mit grosser Schrift höher.
    static let primaryLineHeight: Double = 17

    /// Zeilen der Liste bei der Listenhöhe `height` (pt) und dem Dynamic-Type-Faktor `scale` (`@ScaledMetric` zu
    /// `.body`, gedeckelt auf 1.3 × wie `WidgetTextSize.primaryScale`): In der Grundgrösse genau `baseRows`
    /// (heutige Zahl); sonst so viele, wie Zeilen der Höhe `height / baseRows + primaryLineHeight × (Faktor − 1)`
    /// Platz haben, mindestens 1. Wie Android `WidgetListRows.rowsFor` (Paritäts-Fixture `widget_list_rows.json`).
    static func rowsFor(height: Double, scale: Double, baseRows: Int) -> Int {
        guard baseRows > 0 else { return 0 }
        let factor = Double(WidgetTextSize.primaryScale(CGFloat(scale)))
        guard height > 0, height.isFinite, factor > 1 else { return baseRows }
        let row = height / Double(baseRows) + primaryLineHeight * (factor - 1)
        let fitting = Int((height / row + 1e-9).rounded(.down))
        return Swift.min(baseRows, Swift.max(1, fitting))
    }

    /// Die Paare, die eine der Grössen überhaupt zeigen kann (Anfang der Liste, gleiche Reihenfolge).
    static func shown(_ watches: [Watch], rows: Int = maxListRows) -> [Watch] {
        Array(watches.prefix(max(0, rows)))
    }

    /// Logo-Schlüssel der gezeigten Paare; DEX-Pools nie (`CoinLogos.allowed(forMarket:)`), TradFi-Paare unter
    /// eigenem Schlüssel (nie das Logo eines gleichnamigen Krypto-Tokens, `CoinLogoStore.logoKey`).
    static func logoKeys(for watches: [Watch]) -> [String] {
        shown(watches).filter { CoinLogos.allowed(forMarket: $0.marketKey) }.map { CoinLogoStore.logoKey(for: $0) }
    }
}
