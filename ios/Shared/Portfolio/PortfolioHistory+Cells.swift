import Foundation

// Teile von `PortfolioHistory.build` (Verlauf): geprüfte Kerzen, Wert je Coin und Tag, Marktkurs eines Tags und
// Gewinn/Verlust-Zuflüsse je Coin — wie `PortfolioHistoryCells.kt` (Android).

extension PortfolioHistory {
    /// Exakter Bestand zum Zeitpunkt `atMillis` (einschliesslich) aus `holdingsTimelineExact`.
    static func holdingsAtExact(_ timeline: [(time: Int64, holdings: Decimal)], _ atMillis: Int64) -> Decimal {
        var result: Decimal = 0
        for entry in timeline {
            if entry.time > atMillis { break }
            result = entry.holdings
        }
        return result
    }

    /// Je Coin die Kerzen aufsteigend (USDT braucht keine). Identitätsprüfung: liegt der jüngste Schluss um mehr als
    /// Faktor 3 neben dem aktuellen Kurs, zählt die Reihe nicht (grosse echte Tagesbewegungen bleiben drin; der heutige
    /// Punkt nimmt den aktuellen Kurs).
    static func verifiedCloses(_ held: [String], timelines: [String: [(time: Int64, holdings: Double)]],
                               closes: [String: [Int: Double]],
                               livePrices: [String: Double]) -> [String: [(day: Int, close: Double)]] {
        var sortedCloses: [String: [(day: Int, close: Double)]] = [:]
        for coin in held where PortfolioStables.needsQuote(coin) {
            let series = (closes[coin] ?? [:])
                .filter { $0.value > 0 && $0.value.isFinite }
                .sorted { $0.key < $1.key }
                .map { (day: $0.key, close: $0.value) }
            // Heute gehaltener Coin ohne aktuellen Kurs: Token nicht bestätigt → fehlt (strenge Prüfung);
            // ohne heutigen Bestand gibt es keinen Kurs zum Vergleichen, die Reihe bleibt.
            let live = livePrices[coin].flatMap { $0 > 0 && $0.isFinite ? $0 : nil }
            let heldToday = (timelines[coin]?.last?.holdings ?? 0) > 0
            sortedCloses[coin] = (live == nil && !heldToday) || PricePlausibility.closesMatchLive(series, live: live)
                ? series : []
        }
        return sortedCloses
    }

    /// Je Coin und Tag der Wert samt Eröffnung/Hoch/Tief (nil = nicht gehalten oder ohne Kurs) und die Coins, die
    /// gehalten an einem Tag ohne Kurs waren (vor der ersten Kerze: Liefergrenze der Börse, z. B. Kraken 720 Tage,
    /// oder gekauft vor dem Listing; siehe «Einstieg» in `build`, wie Android).
    static func dayCells(_ included: [String], exactTimelines: [String: [(time: Int64, holdings: Decimal)]],
                         sortedCloses: [String: [(day: Int, close: Double)]], livePrices: [String: Double],
                         ranges: [String: [Int: PortfolioDayRange]], start: Int, dayCount: Int,
                         todayEpochDay: Int) -> (cells: [String: [DayCell?]], unpriced: Set<String>) {
        var cells: [String: [DayCell?]] = [:]
        var unpriced: Set<String> = []
        for coin in included {
            let timeline = exactTimelines[coin] ?? []
            let series = sortedCloses[coin] ?? []
            var row = [DayCell?](repeating: nil, count: dayCount)
            for index in 0..<dayCount {
                let day = start + index
                let isToday = day == todayEpochDay
                let amount = isToday ? (timeline.last?.holdings ?? 0) : holdingsAtExact(timeline, utcDayEnd(day))
                guard amount > 0 else { continue }
                let dayMarket: Double?
                if isToday, let live = livePrices[coin], live > 0, live.isFinite {
                    dayMarket = live
                } else {
                    dayMarket = closeOnOrBefore(series, day: day)
                }
                // Stablecoin-Regel: USDT 1, andere Stablecoins Kurs des Tags, sonst kein Wert
                guard let price = PortfolioStables.price(coin, market: dayMarket) else {
                    unpriced.insert(coin)
                    continue
                }
                // Kerze genau dieses Tags (nicht fortgeschrieben); Hoch/Tief schliessen den Tageswert ein
                // Menge × Kurs exakt (Kurs über seine kürzeste Darstellung); Double erst in der Summe je Tag (`sumCells`)
                func times(_ p: Double) -> Decimal { PortfolioDecimal.multiply(amount, PortfolioDecimal.of(p) ?? 0) }
                if PortfolioStables.needsQuote(coin), !series.isEmpty, let r = ranges[coin]?[day], r.valid {
                    row[index] = DayCell(value: times(price), open: times(r.open),
                                         high: times(Swift.max(r.high, price)), low: times(Swift.min(r.low, price)),
                                         withRange: true)
                } else {
                    let v = times(price)
                    row[index] = DayCell(value: v, open: v, high: v, low: v, withRange: false)
                }
            }
            cells[coin] = row
        }
        return (cells, unpriced)
    }

    /// Marktkurs eines Tags (Auszahlung/Belohnung ohne Preis, Adress-Änderung, Gebühr im dritten Coin ohne Preis): heute
    /// der aktuelle Kurs, sonst der Schluss. Ein Gebühr-Coin ohne Bestand im Zeitraum: seine Kerzen mit derselben
    /// Identitätsprüfung.
    static func marketAt(sortedCloses: [String: [(day: Int, close: Double)]], closes: [String: [Int: Double]],
                         livePrices: [String: Double], todayEpochDay: Int) -> (String, Int) -> Double? {
        { coin, day in
            let live = livePrices[coin].flatMap { $0 > 0 && $0.isFinite ? $0 : nil }
            let series: [(day: Int, close: Double)]
            if let known = sortedCloses[coin] {
                series = known
            } else {
                let raw = (closes[coin] ?? [:]).filter { $0.value > 0 && $0.value.isFinite }
                    .sorted { $0.key < $1.key }.map { (day: $0.key, close: $0.value) }
                series = live == nil || PricePlausibility.closesMatchLive(raw, live: live) ? raw : []
            }
            if day == todayEpochDay, let live { return PortfolioStables.price(coin, market: live) }
            return PortfolioStables.price(coin, market: closeOnOrBefore(series, day: day))
        }
    }

    /// Gewinn/Verlust je Coin (`pnlFlows`): Coins mit bekannten Zuflüssen, ausgelassene Coins und alle Zuflüsse.
    static func pnlParts(_ drawn: [String], eventsOf: [String: [PortfolioLedgerEvent]], from: Int, todayEpochDay: Int,
                         dayEndMillis: (Int) -> Int64, rate: Double, sinceFirst: Bool,
                         market: (String, Int) -> Double?, method: CostMethod,
                         entryFlows: [String: PortfolioCashFlow])
        -> (coins: [String], skipped: [String], flows: [PortfolioCashFlow]) {
        var pnlFlowList: [PortfolioCashFlow] = []
        var pnlSkipped: [String] = []
        var pnlCoins: [String] = []
        for coin in drawn {
            let coinFlows = pnlFlows(coin, events: eventsOf[coin] ?? [], start: from, todayEpochDay: todayEpochDay,
                                     dayEndMillis: dayEndMillis, rate: rate, sinceFirst: sinceFirst, market: market,
                                     method: method,
                                     entry: entryFlows[coin].flatMap { sinceFirst || $0.epochDay > from ? $0 : nil })
            if let coinFlows {
                pnlCoins.append(coin)
                pnlFlowList += coinFlows
            } else {
                pnlSkipped.append(coin)
            }
        }
        return (pnlCoins, pnlSkipped, pnlFlowList)
    }
}
