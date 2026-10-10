import Foundation

/// Eine Regel für Stablecoins im ganzen Portfolio (Kopf/Positionen, Wertverlauf, Stichtag-Export,
/// Widget, Portfolio-Alarme) — Spiegel von `PortfolioStables.kt`:
///  - Liste: `CurrencyConversion.usdStables` (dieselbe wie die «≈ Umrechnung» der Merkliste).
///  - USDT ist die Bewertungswährung und gilt immer genau 1.
///  - Jeder andere Stablecoin gilt seinen Marktkurs in USDT (z. B. USDCUSDT 0.9993), wenn einer
///    da ist — sonst 1. Im Wertverlauf und im Stichtag-Export gilt das je Tag (Tagesschluss, sonst 1).
///  - Alle anderen Coins brauchen einen Kurs; ohne Kurs bleibt der Wert offen (nil).
enum PortfolioStables {
    /// Bewertungswährung des Portfolios.
    static let valuation = "USDT"

    /// Die eine Stablecoin-Liste.
    static var coins: Set<String> { CurrencyConversion.usdStables }

    /// Stablecoin (Gross-/Kleinschreibung und Leerzeichen egal)?
    static func isStable(_ coin: String) -> Bool { coins.contains(PortfolioCalculator.normalizeCoin(coin)) }

    /// Die Bewertungswährung selbst (immer 1, keine Abfrage).
    static func isValuation(_ coin: String) -> Bool { PortfolioCalculator.normalizeCoin(coin) == valuation }

    /// Braucht der Coin einen Kurs von einer Quelle? Alle ausser USDT — auch die übrigen Stablecoins.
    static func needsQuote(_ coin: String) -> Bool { !isValuation(coin) }

    /// Wert einer Einheit `coin` in USDT nach der Regel oben; nil nur für Nicht-Stablecoins ohne gültigen Kurs.
    static func price(_ coin: String, market: Double?) -> Double? {
        if isValuation(coin) { return 1 }
        if let m = market, m > 0, m.isFinite { return m }
        return isStable(coin) ? 1 : nil
    }
}
