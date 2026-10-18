import Foundation

/// Abstand zwischen Geld (Kauf) und Brief (Verkauf) in Prozent der Mitte. Ab `widePercent` zeigt das Kursblatt einen
/// Hinweis: Wer jetzt kauft oder verkauft, zahlt deutlich mehr bzw. bekommt deutlich weniger als der letzte Kurs
/// vermuten lässt (dünner Markt). Nur im Blatt, nie in der Zeile. Wie `BidAskSpread.kt`.
enum BidAskSpread {
    /// Ab diesem Abstand (in % der Mitte) gilt er als gross.
    static let widePercentLimit = 2.0

    /// Abstand in % der Mitte; nil, wenn Geld oder Brief fehlen, nicht endlich oder verkehrt sind.
    static func percentOfMid(bid: Double, ask: Double) -> Double? {
        guard bid.isFinite, ask.isFinite, bid > 0, ask > 0, ask >= bid else { return nil }
        let mid = (ask + bid) / 2
        return (ask - bid) / mid * 100
    }

    /// Abstand, wenn er gross ist (über `widePercentLimit`); sonst nil.
    static func widePercent(bid: Double, ask: Double) -> Double? {
        guard let percent = percentOfMid(bid: bid, ask: ask), percent > widePercentLimit else { return nil }
        return percent
    }
}
