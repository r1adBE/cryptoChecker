import Foundation

/// Eine abgerechnete Funding Rate: Zeitpunkt (Epoch-ms) und Satz in % je Periode.
struct FundingPoint: Equatable, Sendable {
    let time: Int64
    let ratePercent: Double
}

/// Abgerechnete Funding Rates (älteste zuerst) und ihre Quelle («Binance Futures», «Bybit», «OKX»).
struct FundingHistory: Equatable, Sendable {
    let points: [FundingPoint]
    let source: String
}

/// Richtung der Funding Rate. Rohwerte = Kotlin-Enum-Namen.
enum FundingTrendDirection: String, Sendable, Equatable {
    case RISING, FALLING, STABLE
}

/// Verlauf der Funding Rate über `hours` Stunden.
struct FundingTrend: Equatable, Sendable {
    let direction: FundingTrendDirection
    let hours: Int
}

/// Verlauf der Funding Rate — wie `FundingTrendRules` in Android: aus den letzten abgerechneten
/// Sätzen (je Börse höchstens 4, beim Öffnen von «Warum?» geladen) plus dem aktuellen Satz, wenn er
/// von derselben Quelle stammt. Nur Punkte der letzten `windowHours` Stunden; mindestens
/// `minPoints` Punkte über mindestens `minSpanHours` Stunden.
///
///  - stabil: alle Sätze liegen innerhalb von `minDeltaPercent` (Prozentpunkte)
///  - steigend/fallend: Unterschied erster → letzter Punkt mindestens `minDeltaPercent` und kein
///    Schritt geht um mehr als `minDeltaPercent` in die Gegenrichtung
///  - sonst (hin und her): kein Verlauf
enum FundingTrendRules {
    /// So weit zurück zählen Punkte.
    static let windowHours = 25
    static let minPoints = 3
    static let minSpanHours = 2
    /// Kleinste Veränderung in Prozentpunkten, die zählt (0,005 = halber Grundsatz von 0,01 %).
    static let minDeltaPercent = 0.005
    /// So viele abgerechnete Sätze werden je Börse geladen.
    static let limit = 4

    /// Rundungsreserve, damit genau 0,005 Prozentpunkte (aus Strings mal 100) zählen.
    private static let eps = 1e-9
    private static let futureToleranceMillis: Int64 = 60_000

    static func trend(_ history: FundingHistory?, current: Double?, currentSource: String?, now: Int64,
                      currentIntervalHours: Int? = nil) -> FundingTrend? {
        guard let history else { return nil }
        let hour = ActivityAnalyzer.hourMillis
        var seen = Set<Int64>()
        var points: [FundingPoint] = []
        for p in history.points
        where p.ratePercent.isFinite && p.time - now <= futureToleranceMillis && now - p.time <= Int64(windowHours) * hour {
            // Je Zeitpunkt der erste (wie `distinctBy` in Kotlin)
            if seen.insert(p.time).inserted { points.append(p) }
        }
        // Auf 8 Stunden umgerechnet vergleichen (stündliche Sätze, Hyperliquid, zählen achtfach)
        // Intervall: das gemeldete der aktuellen Rate (gleiche Quelle = gleicher Kontrakt), sonst das der Quelle
        let reported = currentSource == history.source ? currentIntervalHours : nil
        let interval = ActivityAnalyzer.fundingIntervalHours(history.source, reported: reported)
        points = points.enumerated()
            .sorted { $0.element.time != $1.element.time ? $0.element.time < $1.element.time : $0.offset < $1.offset }
            .map { FundingPoint(time: $0.element.time,
                                ratePercent: ActivityAnalyzer.fundingPer8h($0.element.ratePercent, intervalHours: interval)) }
        // Aktueller Satz nur von derselben Börse (sonst vergliche man zwei Märkte)
        if let current, current.isFinite, let currentSource, currentSource == history.source {
            points.append(FundingPoint(time: now, ratePercent: ActivityAnalyzer.fundingPer8h(current, intervalHours: interval)))
        }
        guard points.count >= minPoints, let first = points.first, let last = points.last else { return nil }
        let spanMillis = last.time - first.time
        guard spanMillis >= Int64(minSpanHours) * hour else { return nil }
        let hours = Int((Double(spanMillis) / Double(hour)).rounded(.toNearestOrAwayFromZero))
        let rates = points.map(\.ratePercent)
        guard let maxRate = rates.max(), let minRate = rates.min() else { return nil }
        if maxRate - minRate < minDeltaPercent - eps { return FundingTrend(direction: .STABLE, hours: hours) }
        let delta = last.ratePercent - first.ratePercent
        var steps: [Double] = []
        for i in 1..<rates.count { steps.append(rates[i] - rates[i - 1]) }
        if delta >= minDeltaPercent - eps && !steps.contains(where: { $0 < -minDeltaPercent }) {
            return FundingTrend(direction: .RISING, hours: hours)
        }
        if delta <= -minDeltaPercent + eps && !steps.contains(where: { $0 > minDeltaPercent }) {
            return FundingTrend(direction: .FALLING, hours: hours)
        }
        return nil
    }

    // MARK: Antworten der Börsen

    /// Binance `/fapi/v1/fundingRate`: `[{"fundingTime":…,"fundingRate":"0.0001"},…]`.
    static func parseBinance(_ text: String) throws -> [FundingPoint] {
        let array = try JArray(string: text)
        var out: [FundingPoint] = []
        for i in 0..<array.count {
            guard let o = try? array.object(i) else { continue }
            if let p = point(time: o.optLong("fundingTime"), rate: o.optString("fundingRate")) { out.append(p) }
        }
        return sortedByTime(out)
    }

    /// Bybit `/v5/market/funding/history`: `{"result":{"list":[{"fundingRate","fundingRateTimestamp"}]}}`, neueste zuerst.
    static func parseBybit(_ text: String) throws -> [FundingPoint] {
        let list = try JObject(string: text).object("result").array("list")
        var out: [FundingPoint] = []
        for i in 0..<list.count {
            guard let o = try? list.object(i) else { continue }
            let time = Int64(o.optString("fundingRateTimestamp").trimmingCharacters(in: .whitespaces)) ?? 0
            if let p = point(time: time, rate: o.optString("fundingRate")) { out.append(p) }
        }
        return sortedByTime(out)
    }

    /// OKX `/api/v5/public/funding-rate-history`: `{"code":"0","data":[{"fundingRate","fundingTime"}]}`, neueste zuerst.
    static func parseOkx(_ text: String) throws -> [FundingPoint] {
        let root = try JObject(string: text)
        guard root.optString("code") == "0" else { throw JSONError(message: "OKX-Fehler: \(root.optString("msg"))") }
        let data = try root.array("data")
        var out: [FundingPoint] = []
        for i in 0..<data.count {
            guard let o = try? data.object(i) else { continue }
            // «realizedRate» ist der tatsächlich abgerechnete Satz; sonst «fundingRate»
            let realized = o.optString("realizedRate")
            let rate = realized.trimmingCharacters(in: .whitespaces).isEmpty ? o.optString("fundingRate") : realized
            let time = Int64(o.optString("fundingTime").trimmingCharacters(in: .whitespaces)) ?? 0
            if let p = point(time: time, rate: rate) { out.append(p) }
        }
        return sortedByTime(out)
    }

    private static func point(time: Int64, rate: String) -> FundingPoint? {
        guard let raw = Double(rate.trimmingCharacters(in: .whitespacesAndNewlines)) else { return nil }
        let value = raw * 100
        guard time > 0, value.isFinite else { return nil }
        return FundingPoint(time: time, ratePercent: value)
    }

    private static func sortedByTime(_ points: [FundingPoint]) -> [FundingPoint] {
        points.enumerated()
            .sorted { $0.element.time != $1.element.time ? $0.element.time < $1.element.time : $0.offset < $1.offset }
            .map(\.element)
    }
}
