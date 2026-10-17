import Foundation

/// Umgerechneter Wertverlauf; `approximate` = mit dem heutigen Kurs statt mit Tageskursen.
struct PortfolioHistoryConverted: Equatable, Sendable {
    let series: PortfolioHistorySeries
    let approximate: Bool
    /// Mit Tageskursen umgerechnet: Änderung und Gewinn/Verlust enthalten die Bewegung des Devisenkurses — «7 T»/«1 J»
    /// im Kopf rechnen dann mit denselben Tageskursen (`PortfolioPeriodChange.gain` `fx`, `rateFunction`); die reine
    /// USDT-Zahl steht separat. Kopf «inkl. Devisenkurs». Wie Android.
    var includesFx: Bool { !approximate }
    /// Mit USDT/USD-Marktkursen (`UsdtUsdRates`) umgerechnet, aber an mindestens einem Tag ohne Kurs: dort USDT = 1.
    var usdtApproximate: Bool = false
}

/// Wertverlauf in einer anderen Währung als USD mit historischen Tageskursen — Spiegel von
/// `PortfolioHistoryFx.kt`:
///  - Jeder Punkt (Wert in USDT) × Kurs USD → Zielwährung SEINES Tags (× USDT/USD-Tagesschluss, wenn `UsdtUsdRates`
///    mitgegeben: USDT zum Marktkurs, Tag ohne Schluss = 1 als Näherung, `PortfolioHistoryConverted.usdtApproximate`;
///    ohne sie USDT = USD wie bis Build 58). Wochenenden und
///    Feiertage (keine EZB-Fixierung) nehmen den Kurs des letzten Geschäftstags davor (`rateOn`).
///  - Der heutige Punkt nimmt den aktuellen Kurs (wie die Umrechnungszeile im Kopf).
///  - Liegt ein Tag vor dem ersten Tageskurs, gilt der erste (die Abfrage beginnt ohnehin
///    `leadDays` Tage früher).
///  - Ohne Tageskurse (Abfrage fehlgeschlagen): alle Punkte mit dem aktuellen Kurs und
///    `approximate` — die Karte zeigt dann «Umgerechnet mit heutigem Kurs».
///  - Veränderung und Prozent werden aus den umgerechneten Punkten neu gerechnet.
///  - Gewinn/Verlust: Werte und Zuflüsse je mit dem Kurs ihres Tags.
enum PortfolioHistoryFx {
    /// So viele Tage vor dem ersten Punkt beginnt die Kursabfrage (deckt lange Feiertage ab).
    static let leadDays = 7

    private static func valid(_ v: Double?) -> Double? {
        guard let v, v > 0, v.isFinite else { return nil }
        return v
    }

    /// Zeitraum der Kursabfrage für einen Verlauf, der `days` Tage vor `today` beginnt.
    static func requestRange(days: Int, today: LocalDay) -> (from: LocalDay, to: LocalDay) {
        (today.plusDays(-(max(0, days) + leadDays)), today)
    }

    /// Anzeigewährung ≠ USD/USDT: Beträge in USDT werden mit dem Kurs USD → `currency` umgerechnet (USDT = 1 USD) —
    /// Hinweis «USDT zum USD-Kurs umgerechnet». Wie Android.
    static func convertsUsdtAsUsd(_ currency: String) -> Bool {
        let code = CurrencyConversion.normalize(currency)
        return code != "USD" && code != "USDT"
    }

    /// Werden USDT-Beträge für `currency` umgerechnet? Mit USDT zum Marktkurs (`usdtMarket`) auch für USD (USDT/USD ≠ 1);
    /// sonst wie `convertsUsdtAsUsd`. Nie für USDT selbst. Wie Android.
    static func convertsUsdt(_ currency: String, usdtMarket: Bool) -> Bool {
        let code = CurrencyConversion.normalize(currency)
        return code != "USDT" && (usdtMarket || code != "USD")
    }

    /// Tageskurse USD → USD für `convert` in USD: an jedem Tag 1 (keine Devisen-Abfrage, nie «mit heutigem Kurs»).
    static func usdParity(fromEpochDay: Int, toEpochDay: Int) -> [Int: Double] {
        guard fromEpochDay <= toEpochDay else { return [:] }
        var out: [Int: Double] = [:]
        for day in fromEpochDay...toEpochDay { out[day] = 1 }
        return out
    }

    /// Hängt an einem der Tage `days` die Umrechnung an USDT = 1 (kein USDT/USD-Kurs)? false ohne `usdt`.
    static func usdtApproximate(_ usdt: UsdtUsdRates?, days: [Int], todayEpochDay: Int) -> Bool {
        guard let usdt else { return false }
        return days.contains { usdt.on($0, todayEpochDay: todayEpochDay).approximate }
    }

    /// Verlauf in `currency` mit Tageskursen enthält die Devisenbewegung, ebenso «7 T»/«1 J» im Kopf, sobald dieselben
    /// Tageskurse geladen sind (`rateFunction`) — Kopf der Karte «inkl. Devisenkurs». false für USD/USDT oder mit dem
    /// heutigen Kurs (`approximate`). Wie Android.
    static func includesFxMovement(_ currency: String, approximate: Bool) -> Bool {
        convertsUsdtAsUsd(currency) && !approximate
    }

    /// Währungen für die Abfrage: `currency`, bei BGN zusätzlich EUR (eine Abfrage für beide).
    static func requestCurrencies(_ currency: String) -> [String] {
        let code = CurrencyConversion.normalize(currency)
        return code == "BGN" ? ["BGN", "EUR"] : [code]
    }

    /// Tageskurse aus der Antwort der Zeitreihe (Datum «yyyy-MM-dd» → Währung → Kurs) für
    /// `currency`: Tag (epochDay) → Kurs. Ungültige Daten und Kurse fallen weg. Fehlt BGN ab dem
    /// 1.1.2026, gilt EUR × 1.95583 (`FxRateSource.derivedBgn`).
    static func ratesByDay(_ currency: String, byDate: [String: [String: Double]]) -> [Int: Double] {
        let code = CurrencyConversion.normalize(currency)
        var out: [Int: Double] = [:]
        for (date, rates) in byDate {
            guard date.count == 10, let day = LocalDay(iso: date) else { continue }
            let rate = valid(rates[code])
                ?? FxRateSource.derivedBgn(currency: code, usdToEur: rates["EUR"], dayIso: day.description)
            if let rate { out[day.epochDay] = rate }
        }
        return out
    }

    /// Kurs am Tag `day` oder am letzten Tag davor (`sorted` aufsteigend); nil davor.
    static func rateOn(_ sorted: [(day: Int, rate: Double)], day: Int) -> Double? {
        var lo = 0
        var hi = sorted.count - 1
        var found = -1
        while lo <= hi {
            let mid = (lo + hi) / 2
            if sorted[mid].day <= day {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found >= 0 ? sorted[found].rate : nil
    }

    /// Rechnet `series` (USDT) in die Zielwährung um.
    /// - Parameters:
    ///   - dailyRates: Tag (epochDay) → Einheiten Zielwährung je USD; nil = nicht geladen
    ///   - currentRate: aktueller Kurs USD → Zielwährung (für heute und als Ausweich)
    ///   - todayEpochDay: heutiger Tag (lokal)
    /// Kurs USD → Zielwährung je Tag, wie `convert` ihn nutzt: ab `todayEpochDay` (und ohne Tageskurse) der aktuelle
    /// `currentRate`, sonst der Kurs des Tags bzw. des letzten Geschäftstags davor (`rateOn`), vor dem ersten der erste.
    /// Auch für «7 T»/«1 J» in der Anzeigewährung (`PortfolioPeriodChange.gain` `fx`) — damit Kopf und Kurve dieselbe
    /// Zahl zeigen. Mit `usdt`: × USDT/USD des Tags (`UsdtUsdRates.on`) — Faktor USDT → Zielwährung. Wie Android.
    static func rateFunction(dailyRates: [Int: Double]?, currentRate: Double,
                             todayEpochDay: Int, usdt: UsdtUsdRates? = nil) -> @Sendable (Int) -> Double {
        let current = valid(currentRate) ?? 1
        let sorted = (dailyRates ?? [:])
            .compactMap { entry -> (day: Int, rate: Double)? in valid(entry.value).map { (day: entry.key, rate: $0) } }
            .sorted { $0.day < $1.day }
        return { day in
            let fx: Double
            if day >= todayEpochDay || sorted.isEmpty {
                fx = current
            } else {
                fx = rateOn(sorted, day: day) ?? sorted[0].rate
            }
            guard let usdt else { return fx }
            return fx * usdt.on(day, todayEpochDay: todayEpochDay).rate
        }
    }

    /// «7 T»/«1 J» in der Anzeigewährung (`change`, aus `PortfolioPeriodChange.gain` mit `fx`) als Gegenwert zum heutigen
    /// Kurs `currentRate` für die Gesamtkarte, die Beträge mit dem heutigen Kurs anzeigt (Betrag ÷ Kurs × Kurs = Betrag
    /// der Kurve). Prozent unverändert. Wie Android `forCard`.
    static func forCard(_ change: PortfolioChange, currentRate: Double) -> PortfolioChange {
        guard let rate = valid(currentRate) else { return change }
        var out = change
        out.amount = change.amount / rate
        out.rewards = change.rewards / rate
        return out
    }

    /// `usdt`: Marktkurse USDT/USD; nil = USDT gilt 1 USD (bisherige Rechnung).
    static func convert(_ series: PortfolioHistorySeries, dailyRates: [Int: Double]?,
                        currentRate: Double, todayEpochDay: Int,
                        usdt: UsdtUsdRates? = nil) -> PortfolioHistoryConverted {
        let approximate = !(dailyRates ?? [:]).values.contains { valid($0) != nil }
        guard !series.points.isEmpty else { return PortfolioHistoryConverted(series: series, approximate: approximate) }
        let rateFor = rateFunction(dailyRates: dailyRates, currentRate: currentRate, todayEpochDay: todayEpochDay,
                                   usdt: usdt)
        let usdtApprox = usdtApproximate(
            usdt,
            days: series.points.map(\.epochDay) + (series.cashFlows ?? []).map(\.epochDay)
                + series.pnlValues.map(\.epochDay) + series.pnlFlows.map(\.epochDay),
            todayEpochDay: todayEpochDay)
        func convertPoint(_ p: PortfolioHistoryPoint) -> PortfolioHistoryPoint {
            let rate = rateFor(p.epochDay)
            // Eröffnung/Hoch/Tief mit demselben Tageskurs wie der Wert
            return PortfolioHistoryPoint(epochDay: p.epochDay, value: p.value * rate, open: p.open.map { $0 * rate },
                                         high: p.high.map { $0 * rate }, low: p.low.map { $0 * rate })
        }
        let points = series.points.map(convertPoint)
        let first = points.first?.value ?? 0
        let last = points.last?.value ?? 0
        let change: Double? = points.count >= 2 ? last - first : nil
        let percent: Double? = change.flatMap { first > PortfolioCalculator.eps ? $0 / first * 100 : nil }
        // Zuflüsse mit dem Kurs ihres Tags (wie die Punkte)
        let flows = series.cashFlows?.map { PortfolioCashFlow(epochDay: $0.epochDay, amount: $0.amount * rateFor($0.epochDay)) }
        // Gewinn/Verlust: Werte und Zuflüsse je mit dem Kurs ihres Tags (ein Kauf zählt zum Kurs des Kauftags)
        let pnlFlows = series.pnlFlows.map { PortfolioCashFlow(epochDay: $0.epochDay, amount: $0.amount * rateFor($0.epochDay)) }
        let converted = PortfolioHistorySeries(points: points, skipped: series.skipped, change: change,
                                               changePercent: percent, tradesInRange: series.tradesInRange,
                                               capped: series.capped, cashFlows: flows,
                                               pnlValues: series.pnlValues.map(convertPoint), pnlFlows: pnlFlows,
                                               pnlSkipped: series.pnlSkipped, pnlSinceFirst: series.pnlSinceFirst,
                                               withdrawalsInRange: series.withdrawalsInRange)
        return PortfolioHistoryConverted(series: converted, approximate: approximate, usdtApproximate: usdtApprox)
    }
}
