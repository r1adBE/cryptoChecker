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

/// Veränderung über 24 Stunden für Prozent-Änderung, Puls-Zeile, Widgets und Live Activity —
/// wie `DayChange.kt`.
///
/// Reihenfolge (`choose`):
///  0. Rollende 24-h-Veränderung, die die Börse im Ticker mitliefert (`Ticker.change24hPercent`,
///     siehe DEVELOPMENT.md «24 h change») — gilt für das Paar selbst, also schon in seiner
///     Quote. Nur endlich und |x| < `maxTickerChange`.
/// Fehlt er, kommt der Bezug aus Stundenkerzen (`select`):
///  1. Kerzen des Paars selbst (USD-artige Quotes teilen sich die USDT-Reihe des
///     Mini-Charts): aktueller Kurs gegen die Eröffnung vor 24 h.
///  2. Fiat-Quote ohne eigene Kerzen (z. B. BTC/CHF): USDT-Reihe, in Fiat umgerechnet mit dem
///     Devisenkurs an BEIDEN Enden (jetzt und vor 24 h); fehlt einer, kein Wert — nie der
///     USDT-Prozentwert als Fiat-Wert (`fromUsdtSeries`). Der Hinweis im Aktionsblatt nennt
///     dann «Anbieter USDT» (`candleSourceName`).
///  3. Sonst nichts (nil → Anzeige «—»), nie die Veränderung seit der letzten Abfrage.
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

    /// 24-h-Veränderung eines Fiat-Paars aus der USDT-Reihe: letzter Schluss × `usdToQuote` (Fiat je
    /// USD jetzt, USDT gilt als USD) gegen Eröffnung × `usdToQuoteAtOpen` (Fiat je USD vor 24 h).
    /// Fehlt einer der beiden Kurse (nil, nicht endlich, ≤ 0) → nil: der USDT-Prozentwert ist kein
    /// Fiat-Wert, also lieber «—». Kursabstand-Prüfung wie `fromPrice` (`maxPriceGap`) gegen den
    /// umgerechneten letzten Schluss; ohne Paarkurs entfällt nur diese Prüfung.
    /// Wie `DayChange.fromUsdtSeries` (Android).
    static func fromUsdtSeries(_ price: Double?, _ reference: DayReference?, usdToQuote: Double?,
                               usdToQuoteAtOpen: Double?) -> Double? {
        guard let ref = reference,
              let rate = usdToQuote, rate.isFinite, rate > 0,
              let rateAtOpen = usdToQuoteAtOpen, rateAtOpen.isFinite, rateAtOpen > 0 else { return nil }
        if let p = price, p.isFinite, p > 0 {
            let gap: Double = abs(p / (ref.lastClose * rate) - 1)
            if gap > maxPriceGap { return nil }
        }
        let open: Double = ref.open * rateAtOpen
        let last: Double = ref.lastClose * rate
        return (last - open) / open * 100
    }

    /// Auswahl in fester Reihenfolge (siehe oben).
    /// - Parameters:
    ///   - pairReference: Kerzen des Paars in seiner Quote (bzw. USDT bei USD-artigen Quotes)
    ///   - usdtReference: USDT-Reihe des Basis-Assets (Mini-Chart), nur für Fiat-Quotes
    ///   - quoteIsFiat: Quote ist eine Landeswährung (EUR, CHF, …)
    ///   - usdToQuote: Fiat je USD jetzt (`fromUsdtSeries`); nil = unbekannt
    ///   - usdToQuoteAtOpen: Fiat je USD zur Eröffnung der Reihe (vor 24 h bzw. Tagesbeginn); nil = unbekannt
    static func select(price: Double?, pairReference: DayReference?, usdtReference: DayReference?,
                       quoteIsFiat: Bool, usdToQuote: Double? = nil, usdToQuoteAtOpen: Double? = nil) -> Double? {
        if let pairReference { return fromPrice(price, pairReference) }
        if quoteIsFiat {
            return fromUsdtSeries(price, usdtReference, usdToQuote: usdToQuote, usdToQuoteAtOpen: usdToQuoteAtOpen)
        }
        return nil
    }

    /// Welche Kerzenreihe `select` nimmt (für den Quellen-Hinweis im Aktionsblatt).
    enum CandleSeries: Sendable, Equatable {
        /// Kerzen des Paars selbst (bzw. USDT-Reihe bei USD-artigen Quotes).
        case pair
        /// Fiat-Quote ohne eigene Kerzen: USDT-Reihe des Basis-Assets (andere Quote!).
        case usdt
        /// Keine passende Reihe.
        case none
    }

    /// Reihe gemäss der Reihenfolge in `select` — eigene vor USDT (nur Fiat-Quotes).
    static func series(pairReference: DayReference?, usdtReference: DayReference?, quoteIsFiat: Bool) -> CandleSeries {
        if pairReference != nil { return .pair }
        if quoteIsFiat && usdtReference != nil { return .usdt }
        return CandleSeries.none
    }

    /// Name der Kerzenquelle für den Hinweis «Veränderung aus …-Kerzen»: bei eigener Reihe der
    /// Anbieter («Binance»), bei der USDT-Reihe eines Fiat-Paars Anbieter plus Quote
    /// («Binance USDT»). Letzteres ist nie die Börse des Paars, `foreignCandleSource` meldet es
    /// also immer — auch bei Binance BTC/EUR mit Binance-USDT-Kerzen. nil ohne Anbieter oder Reihe.
    static func candleSourceName(provider: String?, series: CandleSeries) -> String? {
        guard let p = provider?.trimmingCharacters(in: .whitespaces), !p.isEmpty else { return nil }
        switch series {
        case .pair: return p
        case .usdt: return "\(p) \(usdtQuote)"
        case .none: return nil
        }
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

    /// Was in der Merklisten-Zeile von einer anderen Börse kommt — wie Android `DayChange.RowSource`.
    enum RowSourceKind: Equatable, Sendable { case changeAndChart, change, chart }

    struct RowSource: Equatable, Sendable {
        let kind: RowSourceKind
        let provider: String
        /// Nur bei `.change`, wenn der Mini-Chart von einer DRITTEN Börse kommt — wie Android.
        var chartProvider: String? = nil
    }

    /// Hinweis für die Zeile («Kraken · Änderung & Chart: Binance»): `change` = fremder Anbieter der %-Änderung,
    /// `chart` = fremder Anbieter des Mini-Charts; nil = eigene Börse — wie Android.
    static func rowSource(change: String?, chart: String?) -> RowSource? {
        if let change, let chart, change == chart { return RowSource(kind: .changeAndChart, provider: change) }
        // Änderung und Chart von verschiedenen fremden Börsen: beide nennen, nie den Chart verschweigen
        if let change { return RowSource(kind: .change, provider: change, chartProvider: chart) }
        if let chart { return RowSource(kind: .chart, provider: chart) }
        return nil
    }

    /// Kleiner Marker «*» neben der %-Änderung der Merklisten-Zeile, wenn der GEZEIGTE Wert aus den Kerzen einer
    /// anderen Börse kommt; der Zeilensatz sagt dann «Veränderung aus Binance-Kerzen». Kein Marker ohne gezeigten
    /// Wert («—») und bei «Seit letzter Aktualisierung» (zwei Kurse, keine Kerzen) — wie Android `rowChangeMarker`.
    /// - Parameter foreignSource: fremder Anbieter der Änderung (`foreignCandleSource`); nil = eigene Börse/Ticker
    /// - Returns: Anbieter für Marker und Zeilensatz; nil = kein Marker
    static func rowChangeMarker(foreignSource: String?, shownChange: Double?, sinceLast: Bool) -> String? {
        guard let foreignSource, let shownChange, shownChange.isFinite, !sinceLast else { return nil }
        return foreignSource
    }

    /// Anbieter der Kerzen, wenn er NICHT die Börse des Paars ist — z. B. Kraken-Paar ohne 24-h-Wert
    /// im Ticker, Änderung aus Binance-Kerzen; dann sagt das Aktionsblatt «Veränderung aus
    /// Binance-Kerzen». Gleich gilt die Börse selbst oder ihr Futures-Markt; Vergleich ohne
    /// Gross-/Kleinschreibung, Leer- und Satzzeichen — wie `DayChange.foreignCandleSource` (Android).
    /// - Parameters:
    ///   - markets: Schlüssel und Name der Börse des Paars
    ///   - provider: Anbieter der Kerzen («Binance», «Binance.US», «Coinbase»); nil = keine Kerzen
    static func foreignCandleSource(markets: [String], provider: String?) -> String? {
        guard let p = provider?.trimmingCharacters(in: .whitespaces), !p.isEmpty else { return nil }
        func norm(_ s: String) -> String { String(s.lowercased().filter { $0.isLetter || $0.isNumber }) }
        let n = norm(p)
        let names = markets.map(norm)
        if names.contains(n) { return nil }
        // Spot-Kerzen («Binance») an einem Futures-Markt derselben Börse: als Spot kennzeichnen — wie Android
        return names.contains(n + "futures") ? "\(p) Spot" : p
    }

    /// Quote ist eine Landeswährung (gleiche Liste wie die Umrechnung).
    static func isFiat(_ quote: String) -> Bool {
        FxRateSource.currencies.contains(quote.trimmingCharacters(in: .whitespaces).uppercased())
    }
}
