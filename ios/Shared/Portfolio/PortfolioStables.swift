import Foundation

/// Eine Regel für Stablecoins im ganzen Portfolio (Kopf/Positionen, Wertverlauf, Stichtag-Export,
/// Widget, Portfolio-Alarme) — Spiegel von `PortfolioStables.kt`:
///  - Liste: `CurrencyConversion.usdStables` (dieselbe wie die «≈ Umrechnung» der Merkliste).
///  - USDT ist die Bewertungswährung und gilt immer genau 1.
///  - Jeder andere Stablecoin gilt seinen Marktkurs in USDT (z. B. USDCUSDT 0.9993). Ohne Kurs
///    hat er keinen Wert (nil, Position «—» und «ohne Kurs», zählt unter «ohne N Coins») — kein
///    stilles 1: ein abgekoppelter Stablecoin soll nicht als 1 in Wert und Gewinn landen. Im
///    Wertverlauf und im Stichtag-Export gilt das je Tag (Tagesschluss, sonst kein Wert).
///  - Ausnahme «7 T» (`PortfolioPeriodChange`): fehlt einem Stablecoin (ausser USDT) der Schluss am
///    Stichtag, gilt 1 nur, solange sein aktueller Kurs höchstens 0.1 % von 1 abweicht
///    (`maxFallbackDeviation`); sonst ist die Veränderung unbekannt («—») statt eine Zahl aus
///    einem nie beobachteten Kurs 1 (`fallbackWithoutClose`).
///    Diese Ausnahme setzt einen aktuellen Kurs voraus (ohne ihn fehlt der Coin ohnehin).
///  - Alle anderen Coins brauchen ebenso einen Kurs; ohne Kurs bleibt der Wert offen (nil).
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

    /// Wert einer Einheit `coin` in USDT nach der Regel oben; nil für jeden Coin ausser USDT ohne
    /// gültigen Kurs (auch für USDC, DAI …).
    static func price(_ coin: String, market: Double?) -> Double? {
        if isValuation(coin) { return 1 }
        if let m = market, m > 0, m.isFinite { return m }
        return nil
    }

    /// Höchste Abweichung des aktuellen Kurses von 1, bei der ein fehlender Schluss als 1 gilt (0.1 %).
    static let maxFallbackDeviation = 0.001

    /// Ersatzkurs für einen Stichtag ohne Tagesschluss («7 T»): USDT immer 1; andere Stablecoins 1 nur,
    /// wenn `live` höchstens `maxFallbackDeviation` von 1 abweicht; sonst und für Nicht-Stablecoins
    /// nil (= unbekannt).
    static func fallbackWithoutClose(_ coin: String, live: Double) -> Double? {
        if isValuation(coin) { return 1 }
        guard isStable(coin) else { return nil }
        return live.isFinite && abs(live - 1) <= maxFallbackDeviation ? 1 : nil
    }
}
