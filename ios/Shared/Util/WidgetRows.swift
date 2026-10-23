import Foundation

/// Zeilen, die das Merkliste-Widget höchstens zeigt. Logos und «wird veraltet»-Zeitpunkte nur für diese laden:
/// Mit 2000 Paaren in «Alle» lasen sonst bis zu 2000 Logo-Dateien (je ein UIImage) in eine Erweiterung mit
/// rund 30 MB Speicher und kurzer Zeichenfrist — für höchstens 10 sichtbare Zeilen.
enum WidgetRows {
    /// Grosse Grösse: 10 Zeilen (mittel 4, klein 1 — alle zeigen die ersten Paare der Liste).
    static let maxListRows = 10
    static let mediumListRows = 4

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
