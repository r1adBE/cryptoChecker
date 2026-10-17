import Foundation

/// Hoch, Tief und — falls vorhanden — ein zweites Hoch/Tief (Doppel-Top/-Bottom) eines Zyklus.
struct CycleExtremesResult: Equatable, Sendable {
    var top: CycleMarker? = nil
    var bottom: CycleMarker? = nil
    var secondTop: CycleMarker? = nil
    var secondBottom: CycleMarker? = nil
}

/// Markante Punkte eines Zyklus aus Tagesschlusskursen — wie `CycleExtremes.kt`.
///
/// - Hoch: höchster Kurs in den ersten `topWindowDays` Tagen. Kurz vor dem nächsten
///   Halving kann der Kurs schon höher stehen (2020er-Zyklus: März 2024 über dem Hoch
///   von Nov. 2021) — das gehört zum nächsten Zyklus.
/// - Tief: tiefster Kurs nach dem Hoch, nur wenn mindestens `bearThreshold` darunter.
/// - Doppel-Top/-Bottom: zweites Hoch bzw. Tief, bei dem der tiefere der beiden Punkte
///   höchstens `doubleTolerance` unter dem höheren liegt, mindestens `doubleMinGapDays`
///   Tage entfernt, mit einer Gegenbewegung von mindestens `doubleTolerance` dazwischen.
///   Beispiel 2020er-Zyklus: April/Nov. 2021 und Juni/Nov. 2022.
enum CycleExtremes {
    static let topWindowDays = 1000
    static let bearThreshold = 0.30
    static let doubleTolerance = 0.20
    static let doubleMinGapDays = 90

    static func find(_ daily: [(day: Int, price: Double)], halving: LocalDay, startPrice: Double) -> CycleExtremesResult {
        guard startPrice > 0, !daily.isEmpty else { return CycleExtremesResult() }
        let topRange = daily.indices.filter { daily[$0].day <= topWindowDays }
        // Erstes Maximum (wie maxByOrNull)
        var topIndex: Int?
        for i in topRange where topIndex == nil || daily[i].price > daily[topIndex!].price { topIndex = i }
        guard let topIndex else { return CycleExtremesResult() }
        let topPrice = daily[topIndex].price

        func marker(_ i: Int, _ change: Double) -> CycleMarker {
            CycleMarker(day: daily[i].day, date: halving.plusDays(daily[i].day), priceUsd: daily[i].price,
                        multiple: daily[i].price / startPrice, change: change)
        }

        var result = CycleExtremesResult()
        result.top = marker(topIndex, topPrice / startPrice - 1)
        if let i = second(daily, main: topIndex, range: topRange, isTop: true) {
            result.secondTop = marker(i, daily[i].price / startPrice - 1)
        }

        let afterTop = Array((topIndex + 1)..<daily.count)
        // Erstes Minimum nach dem Hoch
        var bottomIndex: Int?
        for i in afterTop where bottomIndex == nil || daily[i].price < daily[bottomIndex!].price { bottomIndex = i }
        if let b = bottomIndex, daily[b].price <= topPrice * (1 - bearThreshold) {
            result.bottom = marker(b, daily[b].price / topPrice - 1)
            if let i = second(daily, main: b, range: afterTop, isTop: false) {
                result.secondBottom = marker(i, daily[i].price / topPrice - 1)
            }
        }
        return result
    }

    /// Index des zweiten Hochs/Tiefs zu `main` innerhalb von `range`, sonst nil.
    private static func second(_ daily: [(day: Int, price: Double)], main: Int, range: [Int], isTop: Bool) -> Int? {
        let mainEntry = daily[main]
        let candidates = range.filter { i in
            let e = daily[i]
            guard abs(e.day - mainEntry.day) >= doubleMinGapDays else { return false }
            // Der tiefere der beiden Punkte liegt höchstens doubleTolerance unter dem höheren
            return isTop ? e.price >= mainEntry.price * (1 - doubleTolerance)
                         : mainEntry.price >= e.price * (1 - doubleTolerance)
        }
        // Extremster Kandidat zuerst (stabil sortiert wie in Kotlin)
        let ordered = candidates.enumerated().sorted { l, r in
            let a = daily[l.element].price, b = daily[r.element].price
            if a != b { return isTop ? a > b : a < b }
            return l.offset < r.offset
        }.map { $0.element }
        return ordered.first { i in
            let lo = min(i, main) + 1, hi = max(i, main)
            guard lo < hi else { return false }
            let between = daily[lo..<hi].map { $0.price }
            return isTop ? between.min()! <= daily[i].price * (1 - doubleTolerance)
                         : between.max()! >= daily[i].price * (1 + doubleTolerance)
        }
    }
}
