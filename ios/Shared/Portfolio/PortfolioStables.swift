import Foundation

/// Eine Regel für Stablecoins im ganzen Portfolio (Kopf/Positionen, Wertverlauf, Stichtag-Export,
/// Widget, Portfolio-Alarme) — Spiegel von `PortfolioStables.kt`:
///  - Liste: `CurrencyConversion.usdStables` (dieselbe wie die «≈ Umrechnung» der Merkliste).
///  - USDT ist die interne Rechenwährung (1 USDT = 1 USDT). In Fiat (auch USD) gilt USDT seinen Marktkurs USDT/USD
///    (`usdtUsd`, `UsdtUsdRates`; Owner-Entscheid Okt. 2026, früher fest 1): aktuell der Live-Kurs, für einen Tag der
///    Tagesschluss USDT/USD. Ohne Kurs (offline, Tag ohne Schluss) gilt 1 als NÄHERUNG (`UsdtUsd.approximate`) —
///    nie still: die Anzeige markiert sie («USDT = 1 angenommen», Steuer-CSV Vermerk je Zeile). So bleiben Werte
///    USDT-notierter Portfolios auch offline sichtbar. USD (Fiat) selbst ist in USD immer 1.
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

    /// Die Rechenwährung selbst (in USDT immer 1, keine Abfrage; in Fiat über `usdtUsd`).
    static func isValuation(_ coin: String) -> Bool { PortfolioCalculator.normalizeCoin(coin) == valuation }

    /// Braucht der Coin einen Kurs in USDT von einer Quelle? Alle ausser USDT — auch die übrigen Stablecoins.
    static func needsQuote(_ coin: String) -> Bool { !isValuation(coin) }

    /// Wert einer Einheit `coin` in USDT nach der Regel oben; nil für jeden Coin ausser USDT ohne
    /// gültigen Kurs (auch für USDC, DAI …); USDT = 1 (Rechenwährung — sein Wert in Fiat folgt aus `usdtUsd`).
    static func price(_ coin: String, market: Double?) -> Double? {
        if isValuation(coin) { return 1 }
        if let m = market, m > 0, m.isFinite { return m }
        return nil
    }

    /// Höchste Abweichung des aktuellen Kurses von 1, bei der ein fehlender Schluss als 1 gilt (0.1 %).
    static let maxFallbackDeviation = 0.001

    /// Ersatzkurs für einen Stichtag ohne Tagesschluss («7 T»): USDT (Rechenwährung) immer 1; andere Stablecoins 1 nur,
    /// wenn `live` höchstens `maxFallbackDeviation` von 1 abweicht; sonst und für Nicht-Stablecoins
    /// nil (= unbekannt).
    static func fallbackWithoutClose(_ coin: String, live: Double) -> Double? {
        if isValuation(coin) { return 1 }
        guard isStable(coin) else { return nil }
        return live.isFinite && abs(live - 1) <= maxFallbackDeviation ? 1 : nil
    }

    /// Plausibler Marktkurs USDT/USD (wie `DepegAlarm`): sonst Börsenfehler.
    static let usdtUsdMin = 0.5
    static let usdtUsdMax = 1.5

    /// Ersatz ohne Marktkurs USDT/USD: 1, als Näherung markiert.
    static let usdtUsdFallback = UsdtUsd(rate: 1, approximate: true)

    /// USDT in USD aus einem Marktkurs `market` (live oder Tagesschluss): gültig und plausibel
    /// (`usdtUsdMin`…`usdtUsdMax`) → dieser Kurs; sonst `usdtUsdFallback` (1, Näherung).
    static func usdtUsd(_ market: Double?) -> UsdtUsd {
        guard let m = market, m.isFinite, m >= usdtUsdMin, m <= usdtUsdMax else { return usdtUsdFallback }
        return UsdtUsd(rate: m, approximate: false)
    }

    /// Einbruch-Bereich des LIVE-Kurses USDT/USD: ausserhalb `usdtUsdMin`…`usdtUsdMax`, aber innerhalb
    /// `usdtUsdBreakMin`…`usdtUsdBreakMax` zählt ein Kurs nur, wenn die Depeg-Messung ihn von mindestens zwei Quellen
    /// bestätigt hat (`DepegAlarm.measure`). Ein echter USDT-Einbruch (z. B. 0.30) soll mit dem Marktkurs bewertet
    /// werden statt still mit 1. Wie Android.
    static let usdtUsdBreakMin = 0.05
    static let usdtUsdBreakMax = 3.0

    /// USDT in USD aus dem Live-Kurs `live` (`DepegAlarm.liveUsdtUsd`, schon von der Messung geprüft): gültig und im
    /// Einbruch-Bereich → dieser Kurs; sonst `usdtUsdFallback`. Tagesschlüsse bleiben bei `usdtUsd` (eine einzelne
    /// Quelle, kein Abgleich).
    static func usdtUsdLive(_ live: Double?) -> UsdtUsd {
        guard let m = live, m.isFinite, m >= usdtUsdBreakMin, m <= usdtUsdBreakMax else { return usdtUsdFallback }
        return UsdtUsd(rate: m, approximate: false)
    }

    /// Liegt `rate` ausserhalb des Normalbereichs (bestätigter USDT-Einbruch, Anzeige «USDT-Einbruch»)?
    static func isUsdtBreak(_ rate: Double) -> Bool {
        rate.isFinite && (rate < usdtUsdMin || rate > usdtUsdMax)
    }

    /// Faktor USDT → Zielwährung: `usdToTarget` (Einheiten Zielwährung je USD, USD = 1) × USDT/USD `usdt`.
    /// nil ohne gültigen Devisenkurs.
    static func usdtTo(_ usdToTarget: Double?, usdt: UsdtUsd) -> Double? {
        guard let fx = usdToTarget, fx > 0, fx.isFinite else { return nil }
        let rate = fx * usdt.rate
        return rate > 0 && rate.isFinite ? rate : nil
    }
}

/// USDT in USD: `rate` = Marktkurs, oder 1 mit `approximate` (kein Kurs — Anzeige markiert die Näherung).
struct UsdtUsd: Equatable, Sendable {
    let rate: Double
    let approximate: Bool
}

/// Marktkurse USDT/USD für Umrechnungen in Fiat: `live` = aktueller Kurs (Kraken USDT/USD bzw. Binance USDC/USDT wie der
/// Depeg-Alarm, `DepegAlarm.measure`); `daily` = Tagesschluss je Tag (epochDay). Ab `todayEpochDay` gilt der Live-Kurs,
/// davor der Schluss DES Tags — fehlt er, 1 als Näherung (`PortfolioStables.usdtUsd`), kein Schluss eines anderen Tags.
struct UsdtUsdRates: Equatable, Sendable {
    var live: Double?
    var daily: [Int: Double] = [:]

    /// Aktueller Kurs (oder Ersatz 1, Näherung); bestätigter Einbruch zählt (`PortfolioStables.usdtUsdLive`).
    func current() -> UsdtUsd { PortfolioStables.usdtUsdLive(live) }

    /// Kurs für den Tag `day`: ab `todayEpochDay` `current`, sonst Tagesschluss oder Ersatz 1 (Näherung).
    func on(_ day: Int, todayEpochDay: Int) -> UsdtUsd {
        day >= todayEpochDay ? current() : PortfolioStables.usdtUsd(daily[day])
    }
}
