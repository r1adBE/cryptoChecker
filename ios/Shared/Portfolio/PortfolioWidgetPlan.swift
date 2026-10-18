import Foundation

// Anzeige, Zeitraum und Chart des Portfolio-Widgets — Spiegel von `PortfolioWidgetChart.kt` und
// `PortfolioWidgetChartGeometry.kt` (Android, dort getestet in PortfolioWidgetChartTest; hier in
// PortfolioWidgetChartTests). Ohne Widget-Typen, damit App, Widget und Tests sie teilen.

/// Anzeige je Widget — wie `PortfolioDisplay` (Android): nur USDT, nur Umrechnung, Umrechnung + «≈ … USDT».
enum PortfolioWidgetDisplay: String, Sendable, CaseIterable {
    case usdt, conversion, conversionUsdt

    /// Ohne Wahl: «Umrechnung» — auch für USD (USDT zum Marktkurs USDT/USD); ist die Umrechnungswährung ein
    /// USD-Stablecoin, «Nur USDT». Wie Android `PortfolioDisplay.defaultFor`.
    static func defaultFor(currency: String) -> PortfolioWidgetDisplay {
        PortfolioWidgetChart.collapsesToUsdt(currency) ? .usdt : .conversion
    }
}

/// Zeitraum für Veränderung und Chart — wie `PortfolioChartRange` (Android).
enum PortfolioWidgetRange: String, Sendable, CaseIterable {
    case day, week, month, year

    var days: Int {
        switch self {
        case .day: 1
        case .week: 7
        case .month: 30
        case .year: 365
        }
    }

    /// Aus dem Tagesverlauf (statt aus den Stundenwerten)?
    var daily: Bool { self != .day }

    /// Zeiträume mit Tagesverlauf (Tage): 7, 30, 365.
    static var periodDays: [Int] { allCases.filter(\.daily).map(\.days) }

    /// Kurzform «24h» / «7T» / «30T» / «1J».
    var shortLabelKey: String {
        switch self {
        case .day: "widget_range_short_24h"
        case .week: "widget_range_short_7d"
        case .month: "widget_range_short_30d"
        case .year: "widget_range_short_1y"
        }
    }

    /// Langform «24h» / «7 Tage» / «30 Tage» / «1 Jahr» (VoiceOver).
    var labelKey: String {
        switch self {
        case .day: "widget_range_24h"
        case .week: "widget_range_7d"
        case .month: "widget_range_30d"
        case .year: "widget_range_1y"
        }
    }
}

/// Ein Tag des Widget-Tagesverlaufs (USDT) — speicherbare Form von `PortfolioHistoryPoint`.
struct PortfolioWidgetDay: Codable, Equatable, Sendable {
    var day: Int
    var value: Double
    var open: Double?
    var high: Double?
    var low: Double?

    init(_ p: PortfolioHistoryPoint) {
        day = p.epochDay
        value = p.value
        open = p.open
        high = p.high
        low = p.low
    }

    var point: PortfolioHistoryPoint {
        PortfolioHistoryPoint(epochDay: day, value: value, open: open, high: high, low: low)
    }
}

/// Veränderung eines Zeitraums in USDT (speicherbar); `percent` nil ohne Ausgangswert.
struct PortfolioWidgetChange: Codable, Equatable, Sendable {
    var amount: Double
    var percent: Double?
    /// Coins, die in der Zahl fehlen (kein Marktkurs) — «7 T ohne …» wie die App; nil = keiner (älterer Stand).
    var skipped: [String]? = nil
}

/// Ein Balken des Portfolio-Charts über `start`...`end` (ms) — wie `ChartBar` (Android). Ein Punkt
/// der Linie hat `start` = `end` und vier gleiche Werte.
struct PortfolioChartBar: Equatable, Sendable {
    let start: Int64
    let end: Int64
    let open: Double
    let high: Double
    let low: Double
    let close: Double

    var up: Bool { close >= open }

    static func point(_ time: Int64, _ value: Double) -> PortfolioChartBar {
        PortfolioChartBar(start: time, end: time, open: value, high: value, low: value, close: value)
    }
}

/// Was das Widget zeigt — wie `PortfolioChartPlan` (Android).
struct PortfolioChartPlan: Equatable, Sendable {
    /// Wirksamer Zeitraum (bei `fallback` 24 h statt des gewählten `requested`).
    let range: PortfolioWidgetRange
    let requested: PortfolioWidgetRange
    let fallback: Bool
    let type: PriceChartType
    /// Leer = «Verlauf folgt».
    let bars: [PortfolioChartBar]
    /// Senkrechtes Gitter wie im geteilten Chart (`PriceChartRange`: Stunde, Tag, Woche, Monat).
    let grid: PriceChartRange
    /// Feste Zeitachse (nur Tages-Basis über 24 h).
    let axisFrom: Int64?
    let axisTo: Int64?
    /// Veränderung in der Anzeige-Einheit; nil = unbekannt.
    let changeAmount: Double?
    let changePercent: Double?

    var hasChart: Bool { bars.count >= 2 }
}

/// Zuletzt bekannter Devisenkurs USD → `currency` mit Abrufzeit (ms), gemerkt neben dem Widget-Stand — wie
/// `PortfolioFxMemo` (Android).
struct PortfolioFxMemo: Codable, Equatable, Sendable {
    var currency: String
    var rate: Double
    var fetchedAt: Int64
}

/// Tagesverlauf (USDT) und Veränderung je Zeitraum für den Widget-Stand.
struct PortfolioWidgetDailyData: Equatable, Sendable {
    var points: [PortfolioHistoryPoint]
    var changes: [Int: PortfolioChange]

    static let empty = PortfolioWidgetDailyData(points: [], changes: [:])
}

/// Reine Regeln — wie `PortfolioWidgetChart` (Android): 24 h aus dem Stundenverlauf, 7 T / 30 T / 1 J
/// aus den schon geladenen Tageskursen des Wertverlaufs (`PortfolioWidgetDailyStore`, ohne eigene
/// Abfrage). Taugen sie nicht, ruhig 24 h als Linie mit Hinweis. Umgerechnet mit dem Kurs des Stands.
enum PortfolioWidgetChart {
    static let hourMillis: Int64 = 3_600_000
    /// Stundenverlauf über mehr als diese Spanne: Gitter je Tag statt je Stunde.
    static let hourlyGridMaxSpanMillis: Int64 = 26 * hourMillis
    /// Tageskurse eines Coins zählen nur, wenn der jüngste Schluss höchstens so viele UTC-Tage alt ist.
    static let maxCloseAgeDays = 1

    /// Anzeigewährung ein USD-Stablecoin (USDT, USDC …)? Dann fallen die drei Anzeigen auf «Nur USDT» zusammen. USD
    /// selbst nicht mehr: USDT gilt dort seinen Marktkurs USDT/USD (`usdtFactor`) wie in jeder anderen Fiat-Währung.
    /// Wie Android `collapsesToUsdt`.
    static func collapsesToUsdt(_ currency: String) -> Bool {
        let code = CurrencyConversion.normalize(currency)
        return CurrencyConversion.usdStables.contains(code) && code != "USD"
    }

    /// Wirksame Anzeige: Anzeigewährung ein USD-Stablecoin (`collapsesToUsdt`) → «Nur USDT» (USD nicht: dort wie jede
    /// Fiat-Währung); ohne USDT-Wert nur Umrechnung.
    static func effectiveDisplay(_ chosen: PortfolioWidgetDisplay, currency: String, hasUsdt: Bool) -> PortfolioWidgetDisplay {
        if !hasUsdt { return .conversion }
        if collapsesToUsdt(currency) { return .usdt }
        return chosen
    }

    /// Faktor USDT → Anzeigewährung (Gesamtwert ÷ Gesamtwert in USDT); nil ohne gültigen USDT-Wert.
    static func fxFactor(_ s: PortfolioWidgetSnapshot) -> Double? {
        guard let usdt = s.totalUsdt, usdt > 0, usdt.isFinite, s.total.isFinite else { return nil }
        let f = s.total / usdt
        return f > 0 && f.isFinite ? f : nil
    }

    /// Der Stand in USDT («Nur USDT»): Beträge, Positionen und Stundenverlauf ÷ `fxFactor`, Währung «USDT».
    static func inUsdt(_ s: PortfolioWidgetSnapshot) -> PortfolioWidgetSnapshot {
        var copy = s
        copy.currency = "USDT"
        guard let f = fxFactor(s) else { return copy }
        copy.total = s.totalUsdt ?? s.total / f
        copy.changeAmount = s.changeAmount.map { $0 / f }
        copy.positions = s.positions?.map {
            var p = $0
            p.value /= f
            p.pnlAmount = p.pnlAmount.map { $0 / f }
            return p
        }
        copy.history = s.history?.map { PortfolioWidgetPoint(at: $0.at, value: $0.value / f) }
        return copy
    }

    /// Der Stand, wie das Widget ihn zeigt: bei «Nur USDT» in USDT, sonst wie gespeichert.
    static func displayed(_ s: PortfolioWidgetSnapshot, display: PortfolioWidgetDisplay) -> PortfolioWidgetSnapshot {
        display == .usdt ? inUsdt(s) : s
    }

    /// Gemerkter Devisenkurs höchstens so lange gültig (wie `FxRateSource`: älter als ein Tag = keiner).
    static let fxMemoMaxAgeMillis: Int64 = 24 * hourMillis

    private static func validRate(_ rate: Double?) -> Double? {
        guard let rate, rate > 0, rate.isFinite else { return nil }
        return rate
    }

    private static func isUsd(_ currency: String) -> Bool {
        CurrencyConversion.usdStables.contains(CurrencyConversion.normalize(currency))
    }

    /// Devisenkurs USD → `currency` für den Stand: der frische, sonst der gemerkte (gleiche Währung, höchstens
    /// `fxMemoMaxAgeMillis` alt), bei USD 1; sonst nil (dann in USD). Ohne den gemerkten Kurs fiel jeder Stand
    /// ohne Netz bzw. in einem frischen Prozess (Widget, Hintergrund) auf USD zurück — und das Widget zeigte
    /// trotz «Umrechnung» USDT (`effectiveDisplay`). Wie Android `fxRateFor`.
    static func fxRate(for currency: String, fresh: Double?, memo: PortfolioFxMemo?, now: Int64) -> Double? {
        if let rate = validRate(fresh) { return rate }
        if isUsd(currency) { return 1 }
        guard let memo, CurrencyConversion.normalize(memo.currency) == CurrencyConversion.normalize(currency) else {
            return nil
        }
        let age = now - memo.fetchedAt
        guard age >= 0 && age < fxMemoMaxAgeMillis else { return nil }
        return validRate(memo.rate)
    }

    /// Faktor USDT → `currency` für den Stand: Devisenkurs `fx` (USD → Währung) × Live-Kurs USDT/USD `usdtLive`
    /// (`PortfolioStables.usdtTo`, USDT zum Marktkurs; ohne Messung × 1) — auch für USD (`fx` = 1). USD-Stablecoins: `fx`
    /// unverändert — das Widget zeigt dort USDT (`effectiveDisplay`). Wie Android `usdtFactor`.
    static func usdtFactor(currency: String, fx: Double, usdtLive: Double?) -> Double {
        if collapsesToUsdt(currency) { return fx }
        return PortfolioStables.usdtTo(fx, usdt: PortfolioStables.usdtUsdLive(usdtLive)) ?? fx
    }

    /// Zu merkender Kurs: nur ein gültiger `fresh`, der zur Währung gehört (`cachedForCurrency` = derselbe Kurs
    /// aus `FxRateSource`); nicht für USD. `fetchedAt`: Abrufzeit des Kurses, sonst `now`. Wie Android `fxMemoToSave`.
    static func fxMemoToSave(currency: String, fresh: Double?, cachedForCurrency: Double?, fetchedAt: Int64?,
                             now: Int64) -> PortfolioFxMemo? {
        guard let rate = validRate(fresh), !isUsd(currency), cachedForCurrency == rate else { return nil }
        return PortfolioFxMemo(currency: CurrencyConversion.normalize(currency), rate: rate, fetchedAt: fetchedAt ?? now)
    }

    /// Der Stand in der eingestellten Umrechnungswährung: lautet er anders (ohne Kurs in USD aufgenommen oder vor
    /// dem Wechsel der Währung), werden Gesamtwert, Betrag, Positionen und Stundenverlauf mit `rate` (USD →
    /// `currency`) neu umgerechnet — sofort beim Zeichnen. Ohne Kurs oder USDT-Wert unverändert. Wie Android `rebased`.
    static func rebased(_ s: PortfolioWidgetSnapshot, currency: String, rate: Double?) -> PortfolioWidgetSnapshot {
        if CurrencyConversion.normalize(s.currency) == CurrencyConversion.normalize(currency) { return s }
        guard let target = validRate(rate), let f = fxFactor(s) else { return s }
        let k = target / f
        var copy = s
        copy.currency = CurrencyConversion.normalize(currency)
        copy.total = s.total * k
        copy.changeAmount = s.changeAmount.map { $0 * k }
        copy.positions = s.positions?.map {
            var p = $0
            p.value *= k
            p.pnlAmount = p.pnlAmount.map { $0 * k }
            return p
        }
        copy.history = s.history?.map { PortfolioWidgetPoint(at: $0.at, value: $0.value * k) }
        return copy
    }

    /// Nur Coins, deren jüngster Schluss höchstens `maxCloseAgeDays` UTC-Tage vor `todayUtcDay` liegt.
    static func usableCloses(_ closes: [String: [Int: Double]], todayUtcDay: Int) -> [String: [Int: Double]] {
        closes.filter { _, byDay in
            let latest = byDay.filter { $0.value > 0 && $0.value.isFinite }.keys.max()
            guard let latest else { return false }
            return latest >= todayUtcDay - maxCloseAgeDays
        }
    }

    /// Tagesverlauf in USDT über höchstens ein Jahr und Veränderung je Zeitraum (`PortfolioPeriodChange`)
    /// aus den Tageskursen; fehlt einem Coin mit Bestand im Jahr der Verlauf, kein Tagesverlauf.
    /// `addresses`: beobachtete Adress-Guthaben, ab ihrer ersten Beobachtung (nie rückwirkend) — wie Android.
    static func dailyData(trades: [PortfolioTx], closes: [String: [Int: Double]],
                          ranges: [String: [Int: PortfolioDayRange]], holdings: [String: Double],
                          current: [String: Double], todayEpochDay: Int, todayUtcDay: Int,
                          dayEndMillis: (Int) -> Int64,
                          addresses: [AddressBalanceHistory] = [],
                          method: CostMethod = .defaultMethod) -> PortfolioWidgetDailyData {
        guard !holdings.isEmpty else { return .empty }
        let usable = usableCloses(closes, todayUtcDay: todayUtcDay)
        let series = PortfolioHistory.build(trades: trades, closes: usable, livePrices: current, range: .year,
                                            todayEpochDay: todayEpochDay, dayEndMillis: dayEndMillis, fxRate: 1,
                                            ranges: ranges.filter { usable[$0.key] != nil }, addresses: addresses,
                                            method: method)
        let points = series.skipped.isEmpty && series.hasChart ? series.points : []
        var changes: [Int: PortfolioChange] = [:]
        for days in PortfolioWidgetRange.periodDays {
            // Wie «Gewinn 7 T» im Portfolio-Tab: Käufe/Verkäufe im Zeitraum herausgerechnet
            // Dieselbe Zahl wie die App («7 T»/«1 J»), auch wenn ein Coin fehlt — dann mit «ohne …» (`skipped`), wie Android
            if let change = PortfolioPeriodChange.gain(trades: trades, current: current, closes: usable,
                                                       baseDay: todayEpochDay - days, addresses: addresses,
                                                       method: method) {
                changes[days] = change
            }
        }
        return PortfolioWidgetDailyData(points: points, changes: changes)
    }

    /// Linie = je Punkt ein Balken, Kerzen = je zwei Nachbarn einer; weniger als zwei: leer.
    static func hourlyBars(_ points: [PortfolioWidgetPoint], type: PriceChartType) -> [PortfolioChartBar] {
        let clean = points.filter { $0.value.isFinite }.sorted { $0.at < $1.at }
        var out: [PortfolioChartBar] = []
        switch type {
        case .line:
            out = clean.map { PortfolioChartBar.point($0.at, $0.value) }
        case .candles:
            for i in clean.indices.dropLast() {
                let a = clean[i]
                let b = clean[i + 1]
                out.append(PortfolioChartBar(start: a.at, end: b.at, open: a.value, high: Swift.max(a.value, b.value),
                                             low: Swift.min(a.value, b.value), close: b.value))
            }
        }
        return out.count >= 2 ? out : []
    }

    /// Beginn des lokalen Tags `day` (ms).
    static func dayStart(_ day: Int, timeZone: TimeZone = .current) -> Int64 {
        PortfolioHistory.dayEndMillis(day - 1, timeZone: timeZone) + 1
    }

    /// Balken des Tagesverlaufs für `range` (letzter Tag heute oder gestern, sonst leer); Werte × `factor`.
    static func dailyBars(_ points: [PortfolioHistoryPoint], range: PortfolioWidgetRange, type: PriceChartType,
                          todayEpochDay: Int, timeZone: TimeZone = .current, factor: Double = 1) -> [PortfolioChartBar] {
        guard range.daily, factor > 0, factor.isFinite else { return [] }
        let sorted = points.filter { $0.value.isFinite }.sorted { $0.epochDay < $1.epochDay }
        guard let last = sorted.last?.epochDay, last <= todayEpochDay, last >= todayEpochDay - 1 else { return [] }
        let first = last - range.days
        var out: [PortfolioChartBar] = []
        switch type {
        case .line:
            out = sorted.filter { $0.epochDay >= first }.map { p in
                PortfolioChartBar.point(dayStart(p.epochDay, timeZone: timeZone), p.value * factor)
            }
        case .candles:
            let shown = PortfolioCandles.daily(sorted).filter { $0.firstDay >= first }
            let dayCount = shown.isEmpty ? 0 : (shown[shown.count - 1].lastDay - shown[0].firstDay + 1)
            out = PortfolioCandles.aggregate(shown, bucket: PortfolioCandles.bucket(dayCount: dayCount)).map { c in
                PortfolioChartBar(start: dayStart(c.firstDay, timeZone: timeZone),
                                  end: dayStart(c.lastDay + 1, timeZone: timeZone),
                                  open: c.open * factor, high: c.high * factor, low: c.low * factor,
                                  close: c.close * factor)
            }
        }
        return out.count >= 2 ? out : []
    }

    /// Gitter: 24 h je Stunde (über 26 h je Tag), 7 T je Tag, 30 T je Woche, 1 J je Monat.
    static func grid(_ range: PortfolioWidgetRange, bars: [PortfolioChartBar]) -> PriceChartRange {
        switch range {
        case .day:
            let end = bars.map(\.end).max() ?? 0
            let span = bars.isEmpty ? 0 : end - bars[0].start
            return span > hourlyGridMaxSpanMillis ? .week : .day
        case .week: return .week
        case .month: return .month
        case .year: return .year
        }
    }

    /// Was das Widget für `requested` zeigt (aus dem gezeigten Stand, `displayed`) — wie Android.
    static func plan(_ s: PortfolioWidgetSnapshot, requested: PortfolioWidgetRange, type: PriceChartType,
                     todayEpochDay: Int, timeZone: TimeZone = .current,
                     dayAxis: (from: Int64, to: Int64)? = nil) -> PortfolioChartPlan {
        if requested.daily {
            let factor = fxFactor(s) ?? 1
            let change = s.periodChanges?[String(requested.days)]
            let bars = dailyBars(s.dailyPoints, range: requested, type: type, todayEpochDay: todayEpochDay,
                                 timeZone: timeZone, factor: factor)
            if let change, change.amount.isFinite, bars.count >= 2 {
                return PortfolioChartPlan(range: requested, requested: requested, fallback: false, type: type,
                                          bars: bars, grid: grid(requested, bars: bars), axisFrom: nil, axisTo: nil,
                                          changeAmount: change.amount * factor,
                                          changePercent: change.percent.flatMap { $0.isFinite ? $0 : nil })
            }
        }
        let fallback = requested.daily
        let shownType: PriceChartType = fallback ? .line : type
        let points = s.valueHistory.filter { $0.value.isFinite }
        let enough = dayAxis != nil ? PortfolioWidgetSeries.drawableDay(points) : PortfolioWidgetSeries.drawable(points)
        let bars = enough ? hourlyBars(points, type: shownType) : []
        return PortfolioChartPlan(range: .day, requested: requested, fallback: fallback, type: shownType, bars: bars,
                                  grid: grid(.day, bars: bars), axisFrom: dayAxis?.from, axisTo: dayAxis?.to,
                                  changeAmount: s.changeAmount.flatMap { $0.isFinite ? $0 : nil },
                                  changePercent: s.changePercent.flatMap { $0.isFinite ? $0 : nil })
    }

    /// Richtung (+1 / −1 / 0) nach dem Vorzeichen des Betrags; nil ohne Betrag.
    static func direction(_ plan: PortfolioChartPlan) -> Int? {
        plan.changeAmount.map { PortfolioWidgetSeries.direction($0) }
    }

    /// Prozent der Betragszeile: «+2.31%» / «−0.50%» / «0.00%» — Text in der Kursfarbe hinter dem Betrag
    /// (keine Pille; der Pfeil steht vor dem Betrag); nil ohne Prozent. Wie Android `PortfolioWidgetChart.percentText`.
    static func percentText(_ plan: PortfolioChartPlan, locale: Locale = .current) -> String? {
        guard plan.changeAmount != nil, let percent = plan.changePercent else { return nil }
        let rounded = abs(percent)
        let sign = rounded < 0.005 ? "" : (percent > 0 ? "+" : "−")
        return BidiText.ltr(sign + String(format: "%.2f%%", locale: locale, rounded))
    }
}

// MARK: Geometrie

/// Reine Geometrie des Portfolio-Charts — wie `PortfolioWidgetChartGeometry` (Android): drei zarte
/// Hilfslinien, Kerzen (65 % ihrer Zeit) oder Linie, x nach der Zeit über die Achse, Hoch-Markierung.
struct PortfolioWidgetChartGeometry {
    let bars: [PortfolioChartBar]
    let type: PriceChartType
    let plotLeft: Double
    let plotRight: Double
    let plotTop: Double
    let plotBottom: Double
    let low: Double
    let high: Double
    /// Achse: `low`...`high`, aber mindestens `WidgetChartGeometry.minSpan` der Mitte hoch (wie
    /// Einzel-Widget und Aktionsblatt) — ein ruhiger Tag sieht so nicht wie ein Absturz aus.
    let axisLevels: WidgetChartGeometry.Levels
    let axisStart: Int64
    let axisEnd: Int64

    /// nil mit weniger als zwei Balken.
    init?(bars: [PortfolioChartBar], type: PriceChartType, axisFrom: Int64? = nil, axisTo: Int64? = nil,
          width: Double, height: Double, inset: Double, topInset: Double, bottomInset: Double) {
        guard bars.count >= 2 else { return nil }
        self.bars = bars
        self.type = type
        plotLeft = inset
        plotRight = Swift.max(inset + 1, width - inset)
        plotTop = topInset
        plotBottom = Swift.max(topInset + 1, height - bottomInset)
        switch type {
        case .candles:
            low = bars.map(\.low).min() ?? 0
            high = bars.map(\.high).max() ?? 0
        case .line:
            low = bars.map(\.close).min() ?? 0
            high = bars.map(\.close).max() ?? 0
        }
        axisLevels = WidgetChartGeometry.widen(WidgetChartGeometry.Levels(low: low, high: high),
                                               minFraction: WidgetChartGeometry.minSpan)
        if let from = axisFrom, let to = axisTo, to > from {
            axisStart = from
            axisEnd = to
        } else {
            axisStart = bars[0].start
            axisEnd = bars.map(\.end).max() ?? bars[0].start
        }
    }

    /// Gesamtrichtung (letzter Schluss ≥ erster Wert).
    var up: Bool {
        let first = type == .candles ? bars[0].open : bars[0].close
        return bars[bars.count - 1].close >= first
    }

    /// Zeitpunkt → x, auf die Fläche begrenzt; ohne Zeitspanne nach der Lage `index`.
    func x(_ time: Int64, index: Int = 0) -> Double {
        let span = axisEnd - axisStart
        // In Schritten mit festen Typen (für den Type-Checker)
        let f: Double
        if span > 0 {
            let offset = Double(time - axisStart)
            f = offset / Double(span)
        } else {
            let last = Double(bars.count - 1)
            f = Double(index) / last
        }
        let clamped: Double = Swift.min(1, Swift.max(0, f))
        let width: Double = plotRight - plotLeft
        return plotLeft + clamped * width
    }

    func y(_ value: Double) -> Double {
        let axis = axisLevels
        let span = axis.high - axis.low
        guard span > 0 else { return (plotTop + plotBottom) / 2 }
        return plotBottom - (value - axis.low) / span * (plotBottom - plotTop)
    }

    /// y der drei Hilfslinien: Hoch, Mitte, Tief der Achse.
    var levelYs: [Double] {
        let axis = axisLevels
        return [y(axis.high), y(axis.mid), y(axis.low)]
    }

    /// Punkte der Linie (Schluss am Beginn des Balkens).
    var linePoints: [(x: Double, y: Double)] {
        bars.enumerated().map { i, b in (x: x(b.start, index: i), y: y(b.close)) }
    }

    /// Kerze in Punkten: Mitte, Körperbreite, Körper oben/unten, Docht oben/unten.
    struct Candle {
        let x: Double
        let bodyWidth: Double
        let bodyTop: Double
        let bodyBottom: Double
        let wickTop: Double
        let wickBottom: Double
        let up: Bool
    }

    /// Kerzen: mittig in ihrer Zeit, Körper 65 % davon (mindestens `minSize` breit und hoch).
    func candles(minSize: Double = 1) -> [Candle] {
        bars.enumerated().map { i, b in
            let xs = x(b.start, index: i)
            let xe = x(b.end, index: i)
            let yo = y(b.open)
            let yc = y(b.close)
            var top = Swift.min(yo, yc)
            var bottom = Swift.max(yo, yc)
            if bottom - top < minSize {
                bottom = Swift.min(top + minSize, plotBottom)
                top = bottom - minSize
            }
            return Candle(x: (xs + xe) / 2, bodyWidth: Swift.max(minSize, (xe - xs) * 0.65),
                          bodyTop: top, bodyBottom: bottom, wickTop: y(b.high), wickBottom: y(b.low), up: b.up)
        }
    }

    /// Balken mit dem höchsten Wert (Kerzen: Hoch, Linie: Schluss; bei Gleichstand der erste).
    var highIndex: Int {
        var best = 0
        for i in bars.indices {
            let v = type == .candles ? bars[i].high : bars[i].close
            let b = type == .candles ? bars[best].high : bars[best].close
            if v > b { best = i }
        }
        return best
    }

    /// Lage der Hoch-Markierung.
    var highPoint: (x: Double, y: Double) {
        let i = highIndex
        let b = bars[i]
        switch type {
        case .candles: return (x: (x(b.start, index: i) + x(b.end, index: i)) / 2, y: y(high))
        case .line: return (x: x(b.start, index: i), y: y(b.close))
        }
    }

    /// x der senkrechten Gitterlinien (wie im geteilten Chart), nur innerhalb der Fläche.
    func gridXs(grid: PriceChartRange, timeZone: TimeZone = .current) -> [Double] {
        WidgetChartGeometry.gridTimes(firstOpen: axisStart, endMillis: axisEnd, range: grid, timeZone: timeZone)
            .map { x($0) }
            .filter { $0 > plotLeft + 0.5 && $0 < plotRight - 0.5 }
    }

    /// Linker Rand der «Hoch …»-Beschriftung: rechts der Markierung in der linken Hälfte, sonst links; ganz drin.
    static func labelLeft(markerX: Double, labelWidth: Double, left: Double, right: Double, gap: Double) -> Double {
        let rightSide = markerX < (left + right) / 2
        let raw = rightSide ? markerX + gap : markerX - gap - labelWidth
        return Swift.min(Swift.max(raw, left), Swift.max(left, right - labelWidth))
    }
}

// MARK: Tageskurse für das Widget

/// Tageskurse eines Coins (USDT) in der App Group — Spiegel dessen, was der Wertverlauf der App
/// (`PortfolioHistorySource`) geladen hat; das Widget liest sie, ohne selbst abzufragen.
struct PortfolioWidgetDailyCoin: Codable, Equatable, Sendable {
    var closes: [Int: Double]
    var ranges: [Int: PortfolioDayRange]?
    /// Reihe von der eigenen Börse (`PortfolioExchangeRoute.historyCloses`); nil = Binance.
    var own: Bool? = nil
    /// Erster eigener Tag, davor Binance angeglichen (`PortfolioExchangeRoute.stitch`); nil = keiner.
    var binanceBefore: Int? = nil
}

/// Tageskurse für 7 T / 30 T / 1 J im Widget. Android liest dafür direkt den Zwischenspeicher des
/// Wertverlaufs (`PortfolioHistorySource.cachedDailyPrices`); iOS spiegelt ihn in eine Datei der App
/// Group (`portfolio_widget_daily.json`), weil die Widget-Erweiterung den App-Zwischenspeicher nicht
/// sieht. Nur Geräte-Daten: vom Backup ausgenommen.
enum PortfolioWidgetDailyStore {
    /// So viele Tage vor heute bleiben je Coin (1 J + Puffer für den Vortag).
    static let keepDays = 400

    static var url: URL { SharedStorage.directory.appendingPathComponent("portfolio_widget_daily.json") }

    static func load() -> [String: PortfolioWidgetDailyCoin] {
        guard let data = try? Data(contentsOf: url),
              let map = try? JSONDecoder().decode([String: PortfolioWidgetDailyCoin].self, from: data)
        else { return [:] }
        return map
    }

    /// Neue Kurse übernehmen (je Coin ersetzt), andere Coins behalten; Tage älter als `keepDays` fallen weg.
    static func merge(_ stored: [String: PortfolioWidgetDailyCoin], closes: [String: [Int: Double]],
                      ranges: [String: [Int: PortfolioDayRange]], todayUtcDay: Int,
                      own: Set<String> = [], binanceBefore: [String: Int] = [:]) -> [String: PortfolioWidgetDailyCoin] {
        let from = todayUtcDay - keepDays
        var out = stored
        for (coin, byDay) in closes where !byDay.isEmpty {
            let kept = byDay.filter { $0.key >= from && $0.value > 0 && $0.value.isFinite }
            let keptRanges = ranges[coin]?.filter { $0.key >= from && $0.value.valid }
            out[coin] = PortfolioWidgetDailyCoin(closes: kept, ranges: keptRanges?.isEmpty == false ? keptRanges : nil,
                                                 own: own.contains(coin) ? true : nil,
                                                 binanceBefore: own.contains(coin) ? binanceBefore[coin] : nil)
        }
        // Coins ohne Kurs im ganzen Fenster nicht mitschleppen
        return out.filter { _, coin in coin.closes.keys.contains { $0 >= from } }
    }

    /// Vom Wertverlauf der App nach jedem Laden aufgerufen; Fehler bleiben still.
    static func save(closes: [String: [Int: Double]], ranges: [String: [Int: PortfolioDayRange]],
                     own: Set<String> = [], binanceBefore: [String: Int] = [:],
                     now: Int64 = TimeUtils.nowMillis) {
        guard !closes.isEmpty else { return }
        let merged = merge(load(), closes: closes, ranges: ranges,
                           todayUtcDay: PortfolioHistory.epochDay(utcMillis: now),
                           own: own, binanceBefore: binanceBefore)
        guard let data = try? JSONEncoder().encode(merged) else { return }
        do {
            try data.write(to: url, options: [.atomic])
            BackupExclusion.set(excluded: true, for: url)
        } catch {
            return
        }
    }

    /// Schlüsse und Hoch/Tief je Coin für `dailyData`.
    static func prices(_ map: [String: PortfolioWidgetDailyCoin]) -> (closes: [String: [Int: Double]],
                                                                    ranges: [String: [Int: PortfolioDayRange]]) {
        var closes: [String: [Int: Double]] = [:]
        var ranges: [String: [Int: PortfolioDayRange]] = [:]
        for (coin, entry) in map {
            closes[coin] = entry.closes
            if let r = entry.ranges, !r.isEmpty { ranges[coin] = r }
        }
        return (closes, ranges)
    }

    /// Coins mit Reihe der eigenen Börse (für `PortfolioExchangeRoute.historyCloses`).
    static func own(_ map: [String: PortfolioWidgetDailyCoin]) -> Set<String> {
        Set(map.filter { $0.value.own == true }.keys)
    }
}

// MARK: Ansichten «Übersicht» und «Liste»

/// Ansicht je Widget — wie `PortfolioWidgetView` (Android; hier anders benannt, weil `PortfolioWidgetView` die SwiftUI-Ansicht des Widgets ist): Übersicht (wie die Gesamtkarte im
/// Portfolio-Tab), Chart (bisher die einzige Ansicht, deshalb Standard) oder Liste der Positionen.
enum PortfolioWidgetMode: String, Sendable, CaseIterable {
    case overview, chart, list

    /// Neue und bestehende Widgets ohne Wahl: Chart wie bisher.
    static let standard: PortfolioWidgetMode = .chart

    /// Gespeicherter Name → Ansicht; unbekannt oder fehlend = `standard`.
    static func from(_ name: String?) -> PortfolioWidgetMode {
        name.flatMap { PortfolioWidgetMode(rawValue: $0) } ?? standard
    }

    /// Chart-Art (Kerzen / Linie) gilt nur für den Chart.
    var usesChartType: Bool { self == .chart }

    /// Zeitraum (24 h / 7 T / 30 T / 1 J) gilt nicht für die Liste (Einrichtung blendet ihn aus, die Wahl bleibt gespeichert).
    var usesRange: Bool { self != .list }
}

/// Kennzahl der Übersicht — wie die drei Zahlen der Gesamtkarte im Portfolio-Tab.
enum PortfolioFigureKind: Sendable, CaseIterable {
    case today, week, total
}

/// Eine Kennzahl: Betrag in der Anzeige-Einheit und Prozent; nil = unbekannt («—»).
struct PortfolioFigure: Equatable, Sendable {
    let kind: PortfolioFigureKind
    let amount: Double?
    let percent: Double?
}

/// Reine Regeln der Ansichten «Übersicht» und «Liste» — wie `PortfolioWidgetViews` (Android, dort
/// getestet in PortfolioWidgetViewsTest; hier in PortfolioWidgetChartTests).
enum PortfolioWidgetViews {
    /// Höchstens so viele Zeilen ohne bekannte Höhe.
    static let unknownRows = 3

    /// Heute · 7 T · Gesamt aus dem gezeigten Stand (`PortfolioWidgetChart.displayed`): «Heute» ist die
    /// Veränderung des Stands (schon in der Anzeige-Einheit), «7 T» und «Gesamt» (± seit Kauf) sind in
    /// USDT gespeichert und werden mit dem Kurs des Stands umgerechnet (`PortfolioWidgetChart.fxFactor`).
    static func figures(_ s: PortfolioWidgetSnapshot) -> [PortfolioFigure] {
        let factor = PortfolioWidgetChart.fxFactor(s) ?? 1
        func valid(_ value: Double?) -> Double? { value.flatMap { $0.isFinite ? $0 : nil } }
        let week = s.periodChanges?[String(PortfolioWidgetRange.week.days)]
        let total = s.unrealized
        return [
            PortfolioFigure(kind: .today, amount: valid(s.changeAmount),
                            percent: s.changeAmount == nil ? nil : valid(s.changePercent)),
            PortfolioFigure(kind: .week, amount: valid(week?.amount).map { $0 * factor },
                            percent: week == nil ? nil : valid(week?.percent)),
            PortfolioFigure(kind: .total, amount: valid(total?.amount).map { $0 * factor },
                            percent: total == nil ? nil : valid(total?.percent)),
        ]
    }

    /// Coins, die in der sichtbaren «Heute»-Zahl fehlen (kein Vergleichskurs, Kauf ohne Preis —
    /// `PortfolioWidgetSnapshot.todaySkipped`), alphabetisch — für den Hinweis «Heute ohne …» wie die
    /// Gesamtkarte. Leer, wenn das Widget keine «Heute»-Zahl zeigt: Übersicht (`overview`) mit Betrag,
    /// sonst nur beim Zeitraum 24 h (auch als Ersatz für 7 T ohne Tageskurse). Wie Android.
    static func todaySkipped(_ s: PortfolioWidgetSnapshot, plan: PortfolioChartPlan, overview: Bool) -> [String] {
        guard s.changeAmount != nil else { return [] }
        guard overview || (plan.range == .day && plan.changeAmount != nil) else { return [] }
        return Array(Set((s.todaySkipped ?? []).filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty })).sorted()
    }

    /// Zeitraum der sichtbaren Tages-Zahl («7 T», «30 T», «1 J») und die Coins, die darin fehlen — für «7 T ohne …» wie
    /// «Gewinn 7 T» in der App. Übersicht: die «7 T»-Zahl; sonst die des gezeigten Tages-Zeitraums. nil = keine solche
    /// Zahl oder kein Coin fehlt. Wie Android `periodSkipped`.
    static func periodSkipped(_ s: PortfolioWidgetSnapshot, plan: PortfolioChartPlan,
                              overview: Bool) -> (range: PortfolioWidgetRange, coins: [String])? {
        let range: PortfolioWidgetRange
        if overview {
            range = .week
        } else if plan.range.daily && plan.changeAmount != nil {
            range = plan.range
        } else {
            return nil
        }
        let coins = Array(Set((s.periodChanges?[String(range.days)]?.skipped ?? [])
            .filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty })).sorted()
        return coins.isEmpty ? nil : (range: range, coins: coins)
    }

    /// Wie die Positionen im Portfolio-Tab (Standard): absteigend nach Wert, gleicher Wert nach Kürzel.
    static func sortedByValue(_ positions: [PortfolioWidgetPosition]) -> [PortfolioWidgetPosition] {
        positions.filter { $0.value.isFinite }
            .sorted { $0.value != $1.value ? $0.value > $1.value : $0.symbol < $1.symbol }
    }

    /// Höhe einer Listenzeile (pt): einzeilig (breit) Logo 20 bzw. 13 pt; zweizeilig Logo 24 bzw.
    /// 13 + 11 pt; je 8 pt Abstand — wie Android (`rowHeightDp`, Schriftgrösse 1).
    static func rowHeight(wide: Bool, fontScale: Double = 1) -> Double {
        // In Schritten mit festen Typen (Literale, `max` und `?:` in einem Ausdruck sind für den Type-Checker teuer)
        if wide {
            let text: Double = 13 * 1.3 * fontScale
            let content: Double = Swift.max(20, text)
            return content + 8
        }
        let lines: Double = 13 + 11
        let text: Double = lines * 1.2 * fontScale + 2
        let content: Double = Swift.max(24, text)
        return content + 8
    }

    /// Zeilen und Fusszeile in `available` (pt für Zeilen und Fusszeile; nil = unbekannt): so viele der
    /// `count` Positionen wie ganz passen; die Fusszeile nur, wenn sie keine Zeile kostet, die sonst Platz
    /// hätte. Unbekannt: höchstens `unknownRows` Zeilen mit Fusszeile. Wie Android (`listRows`).
    static func listRows(available: Double?, row: Double, footer: Double, count: Int) -> (rows: Int, footer: Bool) {
        let wanted = Swift.max(0, count)
        guard let available else { return (Swift.min(wanted, unknownRows), true) }
        guard row > 0 else { return (0, true) }
        let without = Swift.min(wanted, Swift.max(0, Int((available / row).rounded(.down))))
        let with = Swift.min(wanted, Swift.max(0, Int(((available - footer) / row).rounded(.down))))
        return with >= without && available >= footer ? (with, true) : (without, false)
    }

    /// Anteil der Total-Zeile: das ganze Portfolio — wie Android `LIST_TOTAL_SHARE`.
    static let listTotalShare: Double = 100

    /// Total-Zeile der Liste, gestaltet wie die Listenzeilen ohne Logo (Build 46: breit einzeilig 13 pt, sonst
    /// zweizeilig 13 + 11 pt; je 8 pt Innenrand) und die Trennlinie darüber (1 pt) — wie Android `listTotalHeightDp`.
    static func listTotalHeight(fontScale: Double = 1, wide: Bool = false) -> Double {
        if wide {
            let line: Double = 13 * 1.3 * fontScale
            return line + 8 + 1
        }
        let lines: Double = 13 + 11
        let text: Double = lines * 1.2 * fontScale
        return text + 2 + 8 + 1
    }

    /// Zeilen der Liste in `height` (pt, ganze Fläche ohne Rand) unter Kopfzeile (`header`) und Abstand (`gap`):
    /// Total-Zeile und «Stand …» (`footer`, samt «Heute ohne …») stehen IMMER da und gehen den Zeilen vor —
    /// nie fällt «Stand …» weg, um eine Zeile mehr zu zeigen (nie negativ) — wie Android `listLayout`.
    static func listRowsBelowTotal(height: Double, header: Double, gap: Double, row: Double, footer: Double,
                                   count: Int, fontScale: Double = 1, wide: Bool = false) -> (rows: Int, footer: Bool) {
        guard row > 0 else { return (0, true) }
        let total: Double = listTotalHeight(fontScale: fontScale, wide: wide)
        let available = Swift.max(0, height - header - gap - total - footer)
        return (Swift.min(Swift.max(0, count), Int((available / row).rounded(.down))), true)
    }

    /// Gewinn/Verlust «Heute» bzw. 24 h des Gesamtwerts (`changeAmount`, schon um Käufe/Verkäufe berichtigt
    /// und nach der %-Basis geprüft) — die Summe der Zeilen (`rowPnl`); Richtung nach dem Betrag. nil ohne
    /// Betrag — dann bleibt die zweite Zeile rechts leer. Wie Android `listTotalChange`.
    static func listTotalChange(_ s: PortfolioWidgetSnapshot) -> PortfolioListTotalChange? {
        guard let amount = s.changeAmount, amount.isFinite else { return nil }
        return PortfolioListTotalChange(amount: amount, percent: s.changePercent.flatMap { $0.isFinite ? $0 : nil },
                                        direction: PortfolioWidgetSeries.direction(amount))
    }

    /// Gewinn/Verlust einer Position (`pnlAmount`/`pnlPercent`); nil = unbekannt (Kurs am Anfang oder Preis
    /// einer Transaktion fehlt, älterer Stand) → «—». Wie Android `rowPnl`.
    static func rowPnl(_ p: PortfolioWidgetPosition) -> PortfolioListTotalChange? {
        guard let amount = p.pnlAmount, amount.isFinite else { return nil }
        return PortfolioListTotalChange(amount: amount, percent: p.pnlPercent.flatMap { $0.isFinite ? $0 : nil },
                                        direction: PortfolioWidgetSeries.direction(amount))
    }

    /// Texte für Gewinn/Verlust, lang → kurz (bei knapper Breite geht der Betrag vor): «+1’250.00 CHF · +1.50%»,
    /// «+1’250.00 CHF». «Beträge verbergen»: Betrag «•••», das Prozent bleibt. Ohne Prozent nur der Betrag.
    /// Wie Android `pnlTexts`.
    static func pnlTexts(_ c: PortfolioListTotalChange, currency: String, hidden: Bool) -> [String] {
        let sign = abs(c.amount) < 0.005 ? "" : (c.amount > 0 ? "+" : "−")
        let amount = PortfolioInsights.mask(BidiText.ltr(sign + PriceFormat.valueWithCurrency(abs(c.amount), currency)),
                                            hidden: hidden)
        guard let percent = c.percent else { return [amount] }
        let p = PriceFormat.changePercent(percent) ?? PriceFormat.zeroPercent()
        return [amount + " · " + p, amount]
    }

    /// Wert einer Kennzahl als Text (keine Pille): Prozent mit Vorzeichen; der Pfeil davor folgt der Richtung
    /// (`ChangeArrowIcon`, bei 0 keiner) — wie Android `PortfolioWidgetViews.figureText`.
    static func figureDirection(_ f: PortfolioFigure) -> Double? {
        guard let percent = f.percent else { return nil }
        return Double(PortfolioWidgetSeries.direction(f.amount ?? percent))
    }

    /// Schriftgrössen der Betragszeile (pt): normal, dann kleiner, bevor ein Teil wegfällt
    /// (wie Android `WidgetTextFit.CHANGE_LINE_SIZES_SP`).
    /// J-18: nicht unter 11 pt — lieber fällt der Zeitraum bzw. Betrag weg.
    static let changeLineSizes: [CGFloat] = [12, 11]

    /// Betragszeile als Text (keine Pille): «−31’997.28 CHF  −27.85% · 1J» (Pfeil davor als Symbol) —
    /// Kandidaten vom längsten zum kürzesten: zuerst fällt der Zeitraum weg, dann der Betrag (das Prozent
    /// bleibt), zuletzt der Zeitraum hinter dem Prozent; ohne Prozent Betrag mit und ohne Zeitraum.
    /// Wie Android `WidgetTextFit.changeLineCandidates`.
    static func changeLineCandidates(amount: String, percent: String?, period: String?) -> [PortfolioChangeLine] {
        let period = (period?.isEmpty ?? true) ? nil : period
        guard let percent else {
            return [PortfolioChangeLine(amount: amount, percent: nil, period: period),
                    PortfolioChangeLine(amount: amount, percent: nil, period: nil)].uniqued()
        }
        return [PortfolioChangeLine(amount: amount, percent: percent, period: period),
                PortfolioChangeLine(amount: amount, percent: percent, period: nil),
                PortfolioChangeLine(amount: nil, percent: percent, period: period),
                PortfolioChangeLine(amount: nil, percent: percent, period: nil)].uniqued()
    }
}

/// MEIN Gewinn/Verlust einer Zeile bzw. des Totals über 24 h / seit Tagesbeginn: Betrag in der Anzeige-Einheit,
/// Prozent (nil = Basis ~0) und Richtung nach dem Betrag (+1 / −1 / 0) — wie Android.
struct PortfolioListTotalChange: Equatable, Sendable {
    let amount: Double
    let percent: Double?
    let direction: Int
}

/// Ein Kandidat der Betragszeile: Betrag und Prozent in der Kursfarbe, Zeitraum grau (nie rot/grün).
struct PortfolioChangeLine: Hashable, Sendable {
    let amount: String?
    let percent: String?
    let period: String?
}

private extension Array where Element == PortfolioChangeLine {
    /// Doppelte Kandidaten (etwa ohne Zeitraum) nur einmal, Reihenfolge bleibt.
    func uniqued() -> [PortfolioChangeLine] {
        var seen = Set<PortfolioChangeLine>()
        return filter { seen.insert($0).inserted }
    }
}

// MARK: Währung hinter Kurs und Betrag

/// Währung hinter einem Kurs oder Betrag in den Widgets: kleiner und grau wie der Börsenname der
/// Merkliste — wie `WidgetQuoteText` (Android, getestet in WidgetQuoteTextTest).
enum WidgetQuoteText {
    /// Kleinste Schriftgrösse der Währung (pt) — wie `WidgetPriceText`.
    static let minQuoteSize: Double = 11
    /// Währung ≈ 42 % der Kursgrösse.
    static let ratio: Double = 0.42

    /// Lage der Währung `quote` in `text`: das letzte Vorkommen mit Leerzeichen davor, ohne Buchstabe oder
    /// Ziffer dahinter und mit einer Zahl davor («1’968.40 CHF · 24h» → «CHF»); sonst nil («•••», «—»).
    static func range(in text: String, quote: String) -> Range<String.Index>? {
        guard !quote.trimmingCharacters(in: .whitespaces).isEmpty, !text.isEmpty else { return nil }
        var upper = text.endIndex
        while let found = text.range(of: quote, options: .backwards, range: text.startIndex..<upper) {
            let spaceBefore = found.lowerBound > text.startIndex
                && text[text.index(before: found.lowerBound)].isWhitespace
            let freeAfter = found.upperBound == text.endIndex
                || !(text[found.upperBound].isLetter || text[found.upperBound].isNumber)
            let numberBefore = text[text.startIndex..<found.lowerBound].contains { $0.isNumber }
            if spaceBefore && freeAfter && numberBefore { return found }
            upper = found.lowerBound
        }
        return nil
    }

    /// Text vor der Währung, die Währung und der Rest (ohne Währung: alles in `before`).
    static func split(_ text: String, quote: String) -> (before: String, quote: String?, after: String) {
        guard let r = range(in: text, quote: quote) else { return (text, nil, "") }
        return (String(text[..<r.lowerBound]), String(text[r]), String(text[r.upperBound...]))
    }

    /// Schriftgrösse der Währung zur Kursgrösse `size`: 42 %, mindestens `minQuoteSize`, höchstens `size`.
    static func quoteSize(for priceSize: Double) -> Double {
        guard priceSize > 0 else { return priceSize }
        return Swift.min(priceSize, Swift.max(minQuoteSize, priceSize * ratio))
    }
}
