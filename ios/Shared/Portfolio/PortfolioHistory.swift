import Foundation

/// Zeitraum des Wertverlaufs im Portfolio-Tab — wie `PortfolioHistoryRange` (Android).
/// `days` = so viele Tage vor heute beginnt er höchstens.
enum PortfolioHistoryRange: String, CaseIterable, Identifiable, Codable, Sendable {
    case week
    case month
    case year
    /// «Seit 1. Kauf»: ab der ersten Transaktion, höchstens `PortfolioHistory.sinceFirstMaxDays` Tage zurück.
    case sinceFirst

    var id: String { rawValue }

    var days: Int {
        switch self {
        case .week: return 7
        case .month: return 30
        case .year: return 365
        case .sinceFirst: return PortfolioHistory.sinceFirstMaxDays
        }
    }
}

/// Ein Tag des Verlaufs: Wert am Tagesende (heute: jetzt). `epochDay` = Tage seit 1970-01-01.
struct PortfolioHistoryPoint: Equatable, Sendable {
    let epochDay: Int
    let value: Double
}

/// Fertiger Verlauf eines Zeitraums — wie `PortfolioHistorySeries` (Android).
struct PortfolioHistorySeries: Equatable, Sendable {
    /// Aufsteigend nach Tag; leer, wenn kein Coin einen Verlauf hat.
    let points: [PortfolioHistoryPoint]
    /// Coins mit Bestand im Zeitraum, aber ohne Tageskurse (fehlen im Chart, «ohne …»).
    let skipped: [String]
    /// Letzter minus erster Wert; nil mit weniger als zwei Punkten.
    let change: Double?
    /// Änderung in Prozent des ersten Werts; nil ohne Ausgangswert.
    let changePercent: Double?
    /// Im Zeitraum (nach dem ersten Tag) wurde gekauft oder verkauft.
    let tradesInRange: Bool
    /// «Seit 1. Kauf» reicht nicht bis zum ersten Kauf zurück (auf `PortfolioHistory.sinceFirstMaxDays` begrenzt).
    var capped: Bool = false

    /// Genug für eine Linie.
    var hasChart: Bool { points.count >= 2 }

    static let empty = PortfolioHistorySeries(points: [], skipped: [], change: nil, changePercent: nil, tradesInRange: false)
}

/// Wertverlauf des Portfolios aus den Transaktionen — Spiegel von `PortfolioHistory.kt`:
///  - Bestand je Coin und Tag = alle Käufe/Verkäufe bis zum Tagesende (lokale Zeitzone, über
///    `dayEndMillis`); Verkäufe werden wie in `PortfolioCalculator` beim Bestand gekappt.
///  - Wert = Σ Bestand × Tagesschlusskurs (UTC-Tageskerze desselben Kalendertags) × `fxRate`.
///    Fehlt an einem Tag die Kerze, gilt der letzte bekannte Schluss davor; vor der ersten
///    Kerze zählt der Coin nicht.
///  - Heute: Bestand nach allen Transaktionen × aktueller Kurs (sonst letzter Schluss).
///  - Stablecoins = 1 ohne Kerzen. Coins ganz ohne Kerzen fehlen und stehen in `skipped`.
///  - Beginn = erster Transaktionstag oder Beginn des Zeitraums, je nachdem, was später ist
///    (`startDay`); «Seit 1. Kauf» höchstens `sinceFirstMaxDays` Tage zurück (`isCapped`).
///  - Tageskerzen: mindestens `maxDays` je Coin, für «Seit 1. Kauf» so viele wie nötig
///    (`candleDays`), abgefragt in Stücken von höchstens `maxCandlesPerRequest` (`candleChunks`).
enum PortfolioHistory {
    /// Tageskerzen je Coin mindestens (eine Abfrage mit diesem Limit deckt «1 J» samt heute ab).
    static let maxDays = 366
    /// «Seit 1. Kauf» reicht höchstens 5 Jahre zurück (5 × 365 + 1 Schalttag).
    static let sinceFirstMaxDays = 1826
    /// Höchstens so viele Tageskerzen liefert die Kerzen-Quelle je Abfrage.
    static let maxCandlesPerRequest = 1000

    private static let dayMillis: Int64 = 86_400_000

    /// Stablecoins mit Kurs 1 — gleiche Liste wie `PortfolioCutoff` / `CutoffExport.STABLES`.
    static let stables: Set<String> = ["USDT", "USDC", "BUSD", "FDUSD", "TUSD", "USDP", "DAI", "USD"]

    static func isStable(_ coin: String) -> Bool { stables.contains(PortfolioCalculator.normalizeCoin(coin)) }

    /// UTC-Tag einer Kerzen-Eröffnungszeit (ms), auch vor 1970 korrekt abgerundet.
    static func epochDay(utcMillis millis: Int64) -> Int {
        var days = millis / dayMillis
        if millis % dayMillis < 0 { days -= 1 }
        return Int(days)
    }

    /// Letzte Millisekunde des Tags `epochDay` in der Zeitzone `timeZone`.
    static func dayEndMillis(_ epochDay: Int, timeZone: TimeZone = .current) -> Int64 {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        let next = LocalDay(epochDay: epochDay + 1)
        let start = cal.date(from: DateComponents(year: next.year, month: next.month, day: next.day)) ?? Date()
        return Int64((start.timeIntervalSince1970 * 1000).rounded()) - 1
    }

    /// Bestand eines Coins nach jeder Transaktion: (Zeit, Bestand danach), zeitlich aufsteigend.
    static func holdingsTimeline(_ coin: String, trades: [PortfolioTx]) -> [(time: Int64, holdings: Double)] {
        let symbol = PortfolioCalculator.normalizeCoin(coin)
        let own = trades.filter { PortfolioCalculator.normalizeCoin($0.coin) == symbol }
            .sorted { $0.time != $1.time ? $0.time < $1.time : $0.id < $1.id }
        var holdings = 0.0
        var out: [(time: Int64, holdings: Double)] = []
        out.reserveCapacity(own.count)
        for t in own {
            let amount = t.amount
            guard amount > 0, !amount.isInfinite else { continue }
            switch t.type {
            case .BUY: holdings += amount
            case .SELL: holdings -= min(amount, holdings)
            }
            if holdings <= PortfolioCalculator.eps { holdings = 0 }
            out.append((time: t.time, holdings: holdings))
        }
        return out
    }

    /// Bestand zum Zeitpunkt `atMillis` (einschliesslich).
    static func holdingsAt(_ timeline: [(time: Int64, holdings: Double)], _ atMillis: Int64) -> Double {
        var result = 0.0
        for entry in timeline {
            if entry.time > atMillis { break }
            result = entry.holdings
        }
        return result
    }

    /// Verlauf für `range` bis `todayEpochDay` — Parameter wie in Android.
    static func build(trades: [PortfolioTx],
                      closes: [String: [Int: Double]],
                      livePrices: [String: Double],
                      range: PortfolioHistoryRange,
                      todayEpochDay: Int,
                      dayEndMillis: (Int) -> Int64,
                      fxRate: Double = 1) -> PortfolioHistorySeries {
        let valid = trades.filter { $0.amount > 0 && !$0.amount.isInfinite }
        guard let firstTime = valid.map(\.time).min() else { return .empty }
        let rate = fxRate > 0 && fxRate.isFinite ? fxRate : 1

        let start = startDay(range: range, firstTradeMillis: firstTime, todayEpochDay: todayEpochDay,
                             dayEndMillis: dayEndMillis)
        let capped = isCapped(range: range, firstTradeMillis: firstTime, todayEpochDay: todayEpochDay,
                              dayEndMillis: dayEndMillis)

        let coins = Array(Set(valid.map { PortfolioCalculator.normalizeCoin($0.coin) })).sorted()
        var timelines: [String: [(time: Int64, holdings: Double)]] = [:]
        for coin in coins { timelines[coin] = holdingsTimeline(coin, trades: valid) }

        let startMillis = dayEndMillis(start - 1) + 1
        let held = coins.filter { heldWithin(timelines[$0] ?? [], from: startMillis) }
        let skipped = held.filter { !isStable($0) && (closes[$0]?.isEmpty ?? true) }
        let included = held.filter { !skipped.contains($0) }
        guard !included.isEmpty else {
            return PortfolioHistorySeries(points: [], skipped: skipped, change: nil, changePercent: nil,
                                          tradesInRange: false, capped: capped)
        }

        var sortedCloses: [String: [(day: Int, close: Double)]] = [:]
        for coin in included where !isStable(coin) {
            sortedCloses[coin] = (closes[coin] ?? [:])
                .filter { $0.value > 0 && $0.value.isFinite }
                .sorted { $0.key < $1.key }
                .map { (day: $0.key, close: $0.value) }
        }

        var points: [PortfolioHistoryPoint] = []
        points.reserveCapacity(max(1, todayEpochDay - start + 1))
        for day in start...max(start, todayEpochDay) {
            let isToday = day == todayEpochDay
            let end = dayEndMillis(day)
            var total = 0.0
            for coin in included {
                let timeline = timelines[coin] ?? []
                let amount = isToday ? (timeline.last?.holdings ?? 0) : holdingsAt(timeline, end)
                guard amount > 0 else { continue }
                let price: Double?
                if isStable(coin) {
                    price = 1
                } else if isToday, let live = livePrices[coin], live > 0, live.isFinite {
                    price = live
                } else {
                    price = closeOnOrBefore(sortedCloses[coin] ?? [], day: day)
                }
                guard let price else { continue }
                total += amount * price
            }
            points.append(PortfolioHistoryPoint(epochDay: day, value: total * rate))
        }

        let first = points.first?.value ?? 0
        let last = points.last?.value ?? 0
        let change: Double? = points.count >= 2 ? last - first : nil
        let percent: Double? = change.flatMap { first > PortfolioCalculator.eps ? $0 / first * 100 : nil }
        let firstDayEnd = dayEndMillis(start)
        let tradesInRange = valid.contains { $0.time > firstDayEnd }
        return PortfolioHistorySeries(points: points, skipped: skipped, change: change,
                                      changePercent: percent, tradesInRange: tradesInRange, capped: capped)
    }

    /// Erster Tag des Verlaufs: erster Transaktionstag (lokal: kleinster Tag, dessen Ende nicht
    /// vor `firstTradeMillis` liegt) oder Beginn des Zeitraums, je nachdem, was später ist; nie
    /// nach `todayEpochDay`. `dayEndMillis` steigt mit dem Tag (binäre Suche).
    static func startDay(range: PortfolioHistoryRange, firstTradeMillis: Int64, todayEpochDay: Int,
                         dayEndMillis: (Int) -> Int64) -> Int {
        var lo = todayEpochDay - range.days
        var hi = todayEpochDay
        while lo < hi {
            let mid = lo + (hi - lo) / 2
            if dayEndMillis(mid) < firstTradeMillis {
                lo = mid + 1
            } else {
                hi = mid
            }
        }
        return lo
    }

    /// «Seit 1. Kauf» und der erste Kauf liegt vor dem ältesten gezeigten Tag («nur die letzten 5 Jahre»).
    static func isCapped(range: PortfolioHistoryRange, firstTradeMillis: Int64, todayEpochDay: Int,
                         dayEndMillis: (Int) -> Int64) -> Bool {
        guard range == .sinceFirst else { return false }
        return firstTradeMillis <= dayEndMillis(todayEpochDay - range.days - 1)
    }

    /// Tageskerzen je Coin für `range`: `maxDays` für 7 T, 30 T und 1 J (eine gemeinsame Abfrage);
    /// «Seit 1. Kauf» bis zum Starttag samt einem Tag davor (Zeitzone), mindestens `maxDays`.
    /// Ohne Transaktion (`firstTradeMillis` nil) `maxDays`.
    static func candleDays(range: PortfolioHistoryRange, firstTradeMillis: Int64?, todayEpochDay: Int,
                           dayEndMillis: (Int) -> Int64) -> Int {
        guard range == .sinceFirst, let firstTradeMillis else { return maxDays }
        let start = startDay(range: range, firstTradeMillis: firstTradeMillis, todayEpochDay: todayEpochDay,
                             dayEndMillis: dayEndMillis)
        return max(maxDays, todayEpochDay - start + 2)
    }

    /// Abfragen für die letzten `days` UTC-Tage bis einschliesslich `todayUtcDay`: Stücke von
    /// höchstens `perRequest` Tagen, aufsteigend und lückenlos; das letzte endet heute, ein
    /// kürzeres Stück steht vorn. Leer bei `days` ≤ 0.
    static func candleChunks(days: Int, todayUtcDay: Int, perRequest: Int = maxCandlesPerRequest) -> [ClosedRange<Int>] {
        guard days > 0, perRequest > 0 else { return [] }
        let first = todayUtcDay - days + 1
        var out: [ClosedRange<Int>] = []
        var end = todayUtcDay
        while end >= first {
            let start = max(first, end - perRequest + 1)
            out.append(start...end)
            end = start - 1
        }
        return out.reversed()
    }

    /// Bestand > 0 irgendwann ab `from` (Bestand davor oder eine spätere Transaktion mit Bestand).
    private static func heldWithin(_ timeline: [(time: Int64, holdings: Double)], from: Int64) -> Bool {
        if holdingsAt(timeline, from - 1) > 0 { return true }
        return timeline.contains { $0.time >= from && $0.holdings > 0 }
    }

    /// Punkt unter dem Finger beim Ziehen über den Wertverlauf: Punkt i liegt bei
    /// `left` + `width`·i/(count−1) → der nächstgelegene, an den Rändern der erste/letzte.
    /// nil ohne Punkte oder bei ungültigem x (wie Android `PortfolioHistory.scrubIndex`).
    static func scrubIndex(x: Double, left: Double, width: Double, count: Int) -> Int? {
        guard count > 0, !x.isNaN else { return nil }
        guard count > 1, width > 0 else { return 0 }
        let raw = ((x - left) / width * Double(count - 1)).rounded()
        let clamped = Swift.min(Swift.max(raw, 0), Double(count - 1))
        return Int(clamped)
    }

    /// Schluss am Tag `day` oder der letzte davor; nil vor der ersten Kerze.
    static func closeOnOrBefore(_ sorted: [(day: Int, close: Double)], day: Int) -> Double? {
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
        return found >= 0 ? sorted[found].close : nil
    }
}
