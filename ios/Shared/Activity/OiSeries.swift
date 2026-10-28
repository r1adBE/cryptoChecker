import Foundation

/// Open-Interest-Verlauf je Paar für «Ungewöhnliche Aktivität» und «Warum?» — wie `OiSeries.kt`:
/// statt einer einzigen gespeicherten Messung die letzten `maxSamples` Messungen, mindestens
/// `minSpacingMillis` auseinander und höchstens `maxAgeMillis` alt. Verglichen wird der aktuelle
/// Wert mit dem MEDIAN der Messungen, die mindestens `minAgeMillis` alt sind — eine einzelne
/// Ausreisser-Messung kippt so nichts. Die Richtung gilt als einheitlich, wenn der aktuelle Wert
/// über (bzw. unter) JEDER dieser Messungen liegt. Messungen einer anderen Futures-Quelle zählen
/// nicht (andere Börse = anderer Bestand).
enum OiSeries {
    /// So viele Messungen bleiben höchstens.
    static let maxSamples = 5
    /// Neue Messung frühestens so lange nach der letzten.
    static let minSpacingMillis: Int64 = 30 * 60_000
    /// Ältere Messungen fallen weg.
    static let maxAgeMillis: Int64 = 12 * ActivityAnalyzer.hourMillis
    /// Verglichen wird nur mit Messungen, die mindestens so alt sind.
    static let minAgeMillis: Int64 = 30 * 60_000
    /// Zeitpunkt bis so weit in der Zukunft gilt noch (Uhren gehen nicht genau gleich).
    static let futureToleranceMillis: Int64 = 60_000

    /// Ergebnis des Vergleichs: Veränderung gegenüber dem Median in % (nil ohne Vergleichswert),
    /// Minuten seit der ältesten verglichenen Messung, einheitliche Richtung, Anzahl verglichener Messungen.
    struct Result: Equatable, Sendable {
        let changePercent: Double?
        let minutes: Int?
        let consistent: Bool
        let samples: Int
    }

    private static let none = Result(changePercent: nil, minutes: nil, consistent: false, samples: 0)

    private static func valid(_ s: OiSample) -> Bool { s.units.isFinite && s.units > 0 }

    /// Aufräumen: ungültige, zu alte und künftige Messungen weg, nach Zeit sortiert, ausgedünnt auf
    /// `minSpacingMillis` Abstand (von der ältesten an), die jüngsten `maxSamples`.
    static func prune(_ series: [OiSample], now: Int64) -> [OiSample] {
        // Stabil nach Zeit sortiert (wie `sortedBy` in Kotlin)
        let sorted = series.enumerated()
            .filter { valid($0.element) && now - $0.element.time <= maxAgeMillis && $0.element.time - now <= futureToleranceMillis }
            .sorted { $0.element.time != $1.element.time ? $0.element.time < $1.element.time : $0.offset < $1.offset }
            .map(\.element)
        var kept: [OiSample] = []
        for s in sorted {
            if let last = kept.last, s.time - last.time < minSpacingMillis { continue }
            kept.append(s)
        }
        return Array(kept.suffix(maxSamples))
    }

    /// Neue Messung anhängen, wenn die letzte mindestens `minSpacingMillis` älter ist. Messungen
    /// einer anderen Quelle fallen dabei weg (der Verlauf beginnt neu).
    static func append(_ series: [OiSample], _ sample: OiSample, now: Int64) -> [OiSample] {
        let base = prune(series, now: now).filter { $0.source == sample.source }
        guard valid(sample) else { return base }
        let add = base.last.map { sample.time - $0.time >= minSpacingMillis } ?? true
        return Array((add ? base + [sample] : base).suffix(maxSamples))
    }

    /// Vergleich des aktuellen Werts `currentUnits` (Quelle `source`) mit dem Verlauf.
    static func change(_ series: [OiSample], currentUnits: Double?, source: String?, now: Int64) -> Result {
        guard let currentUnits, currentUnits.isFinite, currentUnits > 0 else { return none }
        let comparable = prune(series, now: now).filter {
            $0.source == source && now - $0.time >= minAgeMillis
        }
        guard let oldest = comparable.first else { return none }
        let reference = BroadMarket.median(comparable.map(\.units))
        let change = (currentUnits / reference - 1) * 100
        let consistent = comparable.allSatisfy { s in
            if change > 0 { return currentUnits >= s.units }
            if change < 0 { return currentUnits <= s.units }
            return true
        }
        return Result(
            changePercent: change,
            minutes: Int((now - oldest.time) / 60_000),
            consistent: consistent,
            samples: comparable.count
        )
    }
}
