import Foundation

/// Prüft Kerzenreihen, bevor «Warum bewegt sich das?» und «Ungewöhnliche Aktivität»
/// damit rechnen — wie `CandleSeries.kt`. Eine Quelle kann ein Paar noch führen, obwohl
/// dort nicht mehr gehandelt wird (z. B. XMRUSDT im Binance-Spot nach dem Delisting:
/// die Kerzen enden beim Delisting). Mit solchen Reihen wären 1h/24h immer 0.00 % und
/// die Einordnung falsch — sie zählen deshalb als «keine Daten».
///
/// Nur Foundation, kein App-Code: auch aus dem Widget-Prozess nutzbar.
enum CandleSeries {

    /// Ergebnis der Prüfung.
    enum Status: Equatable, Sendable {
        case ok, missing, stale, flat, noVolume
    }

    /// Die letzte (laufende) Stundenkerze darf höchstens so alt sein (Uhrabweichung eingerechnet).
    static let staleMillis: Int64 = 2 * ActivityAnalyzer.hourMillis

    /// Geprüftes Fenster für «völlig flach» / «kein Umsatz»: 24 Stunden plus die laufende.
    static let window = ActivityAnalyzer.windowHours + 1

    /// Ab dieser 24-h-Veränderung im Ticker gilt ein völlig flacher Verlauf als unplausibel.
    static let tickerMovePercent = 0.5

    /// Relative Toleranz für «gleicher Schlusskurs».
    private static let flatTolerance = 1e-9

    /// Untergrenze für `isLive`, damit dünn gehandelte Paare mit Lücken nicht herausfallen.
    static let liveMinMillis: Int64 = 24 * ActivityAnalyzer.hourMillis

    /// Stundenreihe für die Auswertung: leer/fehlend, veraltet (letzte Kerze älter als
    /// `staleMillis`), ohne jeden Umsatz oder völlig flach, obwohl der Ticker
    /// (`tickerChange24h`, %) eine Bewegung zeigt.
    static func status(_ candles: [MarketCandle]?, now: Int64, tickerChange24h: Double?) -> Status {
        guard let candles, candles.count >= 2, let last = candles.last else { return .missing }
        if now - last.openTime > staleMillis { return .stale }
        let recent = Array(candles.suffix(window))
        if recent.allSatisfy({ $0.volume <= 0 }) { return .noVolume }
        if let ticker = tickerChange24h, ticker.isFinite, abs(ticker) >= tickerMovePercent, isFlat(recent) {
            return .flat
        }
        return .ok
    }

    /// `candles`, wenn `status` ok ist, sonst nil («keine Daten»).
    static func usable(_ candles: [MarketCandle]?, now: Int64, tickerChange24h: Double?) -> [MarketCandle]? {
        status(candles, now: now, tickerChange24h: tickerChange24h) == .ok ? candles : nil
    }

    /// Alle Schlusskurse gleich (innerhalb einer winzigen relativen Toleranz).
    static func isFlat(_ candles: [MarketCandle]) -> Bool {
        let closes = candles.map(\.close)
        guard let low = closes.min(), let high = closes.max() else { return false }
        return high - low <= abs(high) * flatTolerance
    }

    /// Für die Ausweich-Kette der Kerzenquellen: liefert eine Quelle noch laufende Daten?
    /// Nein, wenn die letzte Kerze älter als vier Intervalle (mindestens 24 h) ist —
    /// dann ist das Paar dort nicht mehr gehandelt und die nächste Quelle ist dran.
    static func isLive(_ candles: [MarketCandle]?, intervalMillis: Int64, now: Int64) -> Bool {
        guard let last = candles?.last else { return false }
        return now - last.openTime <= max(4 * intervalMillis, liveMinMillis)
    }

    /// 24-h-Veränderung im Blatt: die des Tickers (dieselbe wie Pille und Merkliste),
    /// sonst die aus den Kerzen.
    static func change24h(ticker: Double?, candles: Double?) -> Double? {
        if let ticker, ticker.isFinite { return ticker }
        if let candles, candles.isFinite { return candles }
        return nil
    }

    /// Welches beobachtete Paar ein Coin aus «Heute auffällig» öffnet: zuerst Spot
    /// (wie die Karte, Binance-Spot …USDT), dabei USDT/USD vor anderen Quotes; sonst
    /// ein Perpetual, dann übrige Kontrakte. nil = Coin nicht beobachtet.
    static func pickWatch<T>(_ items: [T], symbol: String,
                             base: (T) -> String, quote: (T) -> String,
                             isSpot: (T) -> Bool, isPerpetual: (T) -> Bool) -> T? {
        let wanted = symbol.trimmingCharacters(in: .whitespaces).uppercased()
        var best: (item: T, rank: Int)? = nil
        for item in items where base(item).trimmingCharacters(in: .whitespaces).uppercased() == wanted {
            let market = isSpot(item) ? 0 : (isPerpetual(item) ? 2 : 4)
            let q = quote(item).trimmingCharacters(in: .whitespaces).uppercased()
            let rank = market + (q == "USDT" || q == "USD" ? 0 : 1)
            // Bei Gleichstand bleibt das erste (wie minByOrNull)
            if let current = best, current.rank <= rank { continue }
            best = (item: item, rank: rank)
        }
        return best?.item
    }
}
