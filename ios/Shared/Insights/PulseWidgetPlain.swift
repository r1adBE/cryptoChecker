import CoreGraphics
import Foundation

/// Grösse des Widgets «Was gerade auffällt» für «Kurz gesagt» — wie Android `PulseWidgetSize`:
/// klein bleibt unverändert, mittel ersetzt den Leitsatz durch den ersten Satz, gross zeigt unter dem
/// bisherigen Inhalt den Block «Kurz gesagt».
enum PulseWidgetSize: Equatable, Sendable {
    case small, medium, large
}

/// «Kurz gesagt» im Widget «Was gerade auffällt» (rein, getestet in `PulseWidgetPlainTests`) — wie
/// Android `PulseWidgetPlain`. Die Sätze kommen unverändert aus `MarketPlainSummary`, mit denselben
/// Frische-Regeln wie der Block im Markt-Tab.
///
/// Das Widget nutzt nur, was es auf beiden Plattformen hat: die Veränderung des Gesamtmarkts aus dem
/// Puls (Satz 1, App Group) und Fear & Greed (`FearGreedShared`, Satz 2). «Heute auffällig» und die
/// Karte «Krypto-Markt» liegen nur im Zwischenspeicher der App (`CycleCache`, kein App-Group-Ordner) —
/// darum entfällt Satz 3 im Widget, und Satz 1 kommt nur aus dem Puls.
enum PulseWidgetPlain {

    /// Gültigkeit wie in der App (`CycleCachePolicy.pulse` / `.fearGreed`, nur im App-Ziel) — so gilt
    /// dieselbe Regel «veraltet» (`DataFreshness.isStale`).
    static let pulseTTL: Int64 = 5 * 60_000
    static let fearGreedTTL: Int64 = 60 * 60_000

    /// Schriftgrössen im Block (pt): Titel, Sätze, Hinweis; Leitsatz (der erste Satz im mittleren Widget ebenso).
    static let titleSize: CGFloat = 12
    static let sentenceSize: CGFloat = 13
    static let footnoteSize: CGFloat = 11
    static let leadSize: CGFloat = 12

    /// Abstand über dem Block, vor jedem Satz und vor dem Hinweis (pt) — wie Android.
    static let blockTop: CGFloat = 8
    static let sentenceGap: CGFloat = 4
    static let footnoteGap: CGFloat = 4

    /// Zeilen des Leitsatzes im mittleren Widget (`lineLimit(2)`).
    static let leadLines = 2

    /// Höchstens so viele Sätze (wie die Karte).
    static let maxSentences = 3

    /// Eingaben wie im Markt-Tab, aus dem, was das Widget hat: Puls (`pulseMarketChange24h`, gespeichert
    /// `pulseSavedAt`) und Fear & Greed aus der App Group (`fearGreed`, gespeichert `fearGreedSavedAt`). Der
    /// Zeitpunkt des Index-Werts selbst ist dort nicht abgelegt (unbekannt).
    static func input(now: Int64, pulseMarketChange24h: Double?, pulseSavedAt: Int64?,
                      fearGreed: Int?, fearGreedSavedAt: Int64?) -> PlainSummaryInput {
        PlainSummaryInput(
            now: now,
            pulseMarketChange24h: pulseMarketChange24h,
            pulseStamp: pulseSavedAt.map { DataStamp(provider: nil, savedAt: $0, ttl: pulseTTL) },
            fearGreed: fearGreed,
            fearGreedTime: nil,
            fearGreedStamp: fearGreedSavedAt.map { DataStamp(provider: nil, savedAt: $0, ttl: fearGreedTTL) }
        )
    }

    /// Wie viele Sätze (von vorne, je ganz, nie abgeschnitten) samt Titel und Hinweis in `available`
    /// passen; die übrigen entfallen von hinten. 0 = der Block entfällt. Das Widget prüft dasselbe mit
    /// `ViewThatFits` über `candidateCounts` (die Coins darüber behalten ihren Platz).
    static func sentenceCount(available: CGFloat, title: CGFloat, footnote: CGFloat, sentenceHeights: [CGFloat]) -> Int {
        guard available > 0, !sentenceHeights.isEmpty else { return 0 }
        var used = blockTop + title + footnoteGap + footnote
        var count = 0
        for height in sentenceHeights.prefix(maxSentences) {
            let need = sentenceGap + height
            if used + need > available { break }
            used += need
            count += 1
        }
        return count
    }

    /// Reihenfolge der Versuche im grossen Widget: alle Sätze, dann einer weniger … bis keiner (Block weg).
    static func candidateCounts(_ sentences: Int) -> [Int] {
        Array((0...min(max(sentences, 0), maxSentences)).reversed())
    }

    /// Mittleres Widget: der erste Satz statt des Leitsatzes — nur, wenn er ganz in die Zeilen des
    /// Leitsatzes passt; sonst bleibt der Leitsatz. `sentenceLines` nil = kein Satz.
    static func sentenceAsLead(sentenceLines: Int?, leadLines: Int) -> Bool {
        guard let sentenceLines else { return false }
        return sentenceLines >= 1 && sentenceLines <= leadLines
    }

    /// Quellen der ersten `shownCount` Sätze für den Hinweis im grossen Widget («Daten: CoinGecko,
    /// alternative.me · …»): Richtung aus CoinGecko `/global`, Stimmung von alternative.me, was heraussticht
    /// aus CoinGecko-Daten (wie die Karte). Reihenfolge wie die Sätze, ohne Doppel; nil = keine. Wie Android.
    static func sources(_ summary: PlainSummary, shownCount: Int) -> String? {
        let perSentence = [summary.direction.map { _ in DataFreshness.coinGecko },
                           summary.mood.map { _ in DataFreshness.alternativeMe },
                           summary.standout.map { _ in DataFreshness.coinGecko }].compactMap { $0 }
        var names: [String] = []
        for name in perSentence.prefix(max(shownCount, 0)) where !names.contains(name) { names.append(name) }
        return names.isEmpty ? nil : names.joined(separator: ", ")
    }

    /// Zeitpunkt, ab dem ein gezeigter Satz auf veralteten Daten stünde (frühester; nil = kein Satz).
    /// Dann folgt ein weiterer Eintrag der Timeline ohne diesen Satz.
    static func expiresAt(_ input: PlainSummaryInput, _ summary: PlainSummary) -> Int64? {
        func staleAt(_ stamp: DataStamp?) -> Int64? {
            guard let stamp, stamp.savedAt > 0, stamp.ttl > 0 else { return nil }
            return stamp.savedAt + DataFreshness.staleFactor * stamp.ttl + 1
        }
        var times: [Int64] = []
        if summary.direction != nil, let at = staleAt(input.pulseStamp) { times.append(at) }
        if summary.mood != nil {
            if let at = staleAt(input.fearGreedStamp) { times.append(at) }
            if let time = input.fearGreedTime { times.append(time + MarketPlainSummary.fearGreedMaxAgeMillis + 1) }
        }
        return times.min()
    }
}
