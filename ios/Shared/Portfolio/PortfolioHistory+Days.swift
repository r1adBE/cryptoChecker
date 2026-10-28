import Foundation

// Tage des Wertverlaufs (Teil von `PortfolioHistory`): erster Tag, Begrenzung, nötige Tageskerzen und Abfrage-Stücke,
// Punkt unter dem Finger, Schluss an einem Tag — wie `PortfolioHistoryDays.kt` (Android).

extension PortfolioHistory {
    /// Erster Tag des Verlaufs: erster Transaktionstag (UTC: kleinster Tag, dessen Ende nicht
    /// vor `firstTradeMillis` liegt) oder Beginn des Zeitraums, je nachdem, was später ist; nie
    /// nach `todayEpochDay`. «Seit 1. Kauf»: Beginn = erster Transaktionstag, frühestens
    /// `dataStartEpochDay`. Tage nach UTC (`utcDayEnd`, wie `build`). Wie Android.
    static func startDay(range: PortfolioHistoryRange, firstTradeMillis: Int64, todayEpochDay: Int) -> Int {
        var lo: Int
        if range == .sinceFirst {
            // Der lokale Tag liegt höchstens einen Tag neben dem UTC-Tag (Zeitzonen −12 h … +14 h)
            lo = min(todayEpochDay, max(dataStartEpochDay, epochDay(utcMillis: firstTradeMillis) - 2))
        } else {
            lo = todayEpochDay - range.days
        }
        var hi = todayEpochDay
        while lo < hi {
            let mid = lo + (hi - lo) / 2
            if utcDayEnd(mid) < firstTradeMillis {
                lo = mid + 1
            } else {
                hi = mid
            }
        }
        return lo
    }

    /// «Seit 1. Kauf» und der erste Kauf liegt vor dem ersten Tag mit Kursdaten (`dataStartEpochDay`).
    static func isCapped(range: PortfolioHistoryRange, firstTradeMillis: Int64, todayEpochDay: Int) -> Bool {
        guard range == .sinceFirst, dataStartEpochDay <= todayEpochDay else { return false }
        return firstTradeMillis <= utcDayEnd(dataStartEpochDay - 1)
    }

    /// Tageskerzen je Coin für `range`: `maxDays` für 7 T, 30 T und 1 J (eine gemeinsame Abfrage);
    /// «Seit 1. Kauf» bis zum Starttag samt einem Tag davor (Zeitzone), mindestens `maxDays`.
    /// Ohne Transaktion (`firstTradeMillis` nil) `maxDays`.
    static func candleDays(range: PortfolioHistoryRange, firstTradeMillis: Int64?, todayEpochDay: Int) -> Int {
        guard range == .sinceFirst, let firstTradeMillis else { return maxDays }
        let start = startDay(range: range, firstTradeMillis: firstTradeMillis, todayEpochDay: todayEpochDay)
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
