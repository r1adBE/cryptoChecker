import Foundation

/// 24-h-Bezug aus Stundenkerzen (24 × 1 h, wie der Mini-Chart): Eröffnung der
/// ersten Kerze und Schluss der letzten (läuft in der Regel noch) — wie `DayReference` in Android.
struct DayReference: Sendable, Equatable {
    let open: Double
    let lastClose: Double

    /// nil, wenn ein Wert fehlt, nicht endlich oder nicht positiv ist.
    static func of(open: Double?, lastClose: Double?) -> DayReference? {
        guard let open, let lastClose, open.isFinite, lastClose.isFinite, open > 0, lastClose > 0 else { return nil }
        return DayReference(open: open, lastClose: lastClose)
    }
}

/// Veränderung über 24 Stunden für Prozent-Pille, Puls-Zeile, Widgets und Live Activity —
/// wie `DayChange.kt`.
///
/// Reihenfolge (`choose`):
///  0. Rollende 24-h-Veränderung, die die Börse im Ticker mitliefert (`Ticker.change24hPercent`,
///     siehe DEVELOPMENT.md «24 h change») — gilt für das Paar selbst, also schon in seiner
///     Quote. Nur endlich und |x| < `maxTickerChange`.
/// Fehlt er, kommt der Bezug aus Stundenkerzen (`select`):
///  1. Kerzen des Paars selbst (USD-artige Quotes teilen sich die USDT-Reihe des
///     Mini-Charts): aktueller Kurs gegen die Eröffnung vor 24 h.
///  2. Fiat-Quote ohne eigene Kerzen (z. B. BTC/CHF): Verlauf der USDT-Reihe allein
///     (letzter Schluss gegen Eröffnung) — Näherung, Wechselkursbewegung bleibt aussen vor.
///  3. Sonst nichts (nil → Pille «—»), nie die Veränderung seit der letzten Abfrage.
enum DayChange {
    /// Quotes, für die die USDT-Reihe des Mini-Charts gilt.
    static let usdLikeQuotes: Set<String> = ["USDT", "USD", "USDC", "FDUSD"]

    /// Kerzen-Quote der Mini-Charts (Ausweich-Reihe für Fiat-Quotes).
    static let usdtQuote = "USDT"

    /// Weicht der Kurs um mehr als diesen Anteil vom letzten Kerzenschluss ab, gehören
    /// Kerzen und Kurs wohl nicht zum selben Coin (gleiches Kürzel, anderer Token).
    static let maxPriceGap = 0.25

    /// Kerzen-Quote für ein Paar: USD-artige auf USDT (gleiche Reihe wie der Mini-Chart).
    static func candleQuote(_ quote: String) -> String {
        let q = quote.trimmingCharacters(in: .whitespaces).uppercased()
        return usdLikeQuotes.contains(q) ? usdtQuote : q
    }

    /// Aktueller Kurs gegen die 24-h-Eröffnung; nil ohne Kurs oder bei unpassender Reihe.
    static func fromPrice(_ price: Double?, _ reference: DayReference?) -> Double? {
        guard let ref = reference, let p = price, p.isFinite, p > 0 else { return nil }
        guard abs(p / ref.lastClose - 1) <= maxPriceGap else { return nil }
        return (p - ref.open) / ref.open * 100
    }

    /// Nur der Verlauf der Reihe: letzter Schluss gegen Eröffnung.
    static func fromSeries(_ reference: DayReference?) -> Double? {
        guard let ref = reference else { return nil }
        return (ref.lastClose - ref.open) / ref.open * 100
    }

    /// Auswahl in fester Reihenfolge (siehe oben).
    /// - Parameters:
    ///   - pairReference: Kerzen des Paars in seiner Quote (bzw. USDT bei USD-artigen Quotes)
    ///   - usdtReference: USDT-Reihe des Basis-Assets (Mini-Chart), nur für Fiat-Quotes
    ///   - quoteIsFiat: Quote ist eine Landeswährung (EUR, CHF, …)
    static func select(price: Double?, pairReference: DayReference?, usdtReference: DayReference?,
                       quoteIsFiat: Bool) -> Double? {
        if let pairReference { return fromPrice(price, pairReference) }
        if quoteIsFiat { return fromSeries(usdtReference) }
        return nil
    }

    /// Grösster plausibler Betrag (Prozent) für den 24-h-Wert aus dem Ticker.
    static let maxTickerChange = 10_000.0

    /// 24-h-Wert aus dem Ticker, wenn brauchbar (endlich, |x| < `maxTickerChange`); sonst nil.
    static func fromTicker(_ change24hPercent: Double?) -> Double? {
        guard let value = change24hPercent, value.isFinite, abs(value) < maxTickerChange else { return nil }
        return value
    }

    /// Kerzen nötig? Nur wenn der Ticker keinen brauchbaren 24-h-Wert hat.
    static func needsCandles(_ tickerChange: Double?) -> Bool { fromTicker(tickerChange) == nil }

    /// Endgültiger Wert: Ticker vor Kerzen. `candles` wird nur ausgewertet, wenn der Ticker
    /// nichts Brauchbares liefert (Kerzenwert mit den Prüfungen aus `select`).
    static func choose(tickerChange: Double?, candles: () -> Double?) -> Double? {
        if let value = fromTicker(tickerChange) { return value }
        guard let value = candles(), value.isFinite else { return nil }
        return value
    }

    /// Anbieter der Kerzen, wenn er NICHT die Börse des Paars ist — z. B. Kraken-Paar ohne 24-h-Wert
    /// im Ticker, Pille aus Binance-Kerzen; dann sagt das Aktionsblatt «Veränderung aus
    /// Binance-Kerzen». Gleich gilt die Börse selbst oder ihr Futures-Markt; Vergleich ohne
    /// Gross-/Kleinschreibung, Leer- und Satzzeichen — wie `DayChange.foreignCandleSource` (Android).
    /// - Parameters:
    ///   - markets: Schlüssel und Name der Börse des Paars
    ///   - provider: Anbieter der Kerzen («Binance», «Binance.US», «Coinbase»); nil = keine Kerzen
    static func foreignCandleSource(markets: [String], provider: String?) -> String? {
        guard let p = provider?.trimmingCharacters(in: .whitespaces), !p.isEmpty else { return nil }
        func norm(_ s: String) -> String { String(s.lowercased().filter { $0.isLetter || $0.isNumber }) }
        let n = norm(p)
        let own = markets.map(norm).contains { $0 == n || $0 == n + "futures" }
        return own ? nil : p
    }

    /// Quote ist eine Landeswährung (gleiche Liste wie die Umrechnung).
    static func isFiat(_ quote: String) -> Bool {
        FxRateSource.currencies.contains(quote.trimmingCharacters(in: .whitespaces).uppercased())
    }
}
