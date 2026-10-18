import Foundation

// Regeln des Portfolio-Charts — Spiegel von `PortfolioWidgetChart.kt` (Android, dort getestet in
// PortfolioWidgetChartTest; hier in PortfolioWidgetChartTests). Ohne Widget-Typen, damit App, Widget und Tests sie teilen.

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
        // In USDT gelten die USDT-Werte (`unrealized`, `periodChanges`)
        copy.unrealizedDisplay = nil
        copy.weekDisplay = nil
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
        // «Seit Kauf»/«7 T» der alten Währung gelten nicht (Einstand zum Kurs der Erwerbstage): bis zum nächsten Stand
        // «—» bzw. «7 T» in USDT
        copy.unrealizedDisplay = nil
        copy.weekDisplay = nil
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
