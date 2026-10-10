import Foundation

/// Plausibilitätsprüfung für Kerzenkurse im Portfolio (Wertverlauf und Stichtag-Export) — Spiegel
/// von `PricePlausibility.kt`. Gleiche Schwelle und Idee wie die Prozent-Pille
/// (`DayChange.maxPriceGap`): Weicht ein Kurs um mehr als 25 % vom Bezugskurs ab, gehören Kerzen
/// und Kurs wohl nicht zum selben Coin (gleiches Kürzel, anderer Token) — dann wird der
/// Kerzenkurs verworfen statt still verwendet.
///  - Wertverlauf (`closesMatchLive`): jüngster Tagesschluss gegen den aktuellen Kurs. Passt er
///    nicht, zählt die ganze Kerzenreihe des Coins nicht (Coin unter «ohne …»; Stablecoins → 1).
///  - Stichtag (`acceptSourceClose`): Kurs einer Ausweich-Quelle (Futures, Binance.US, Coinbase)
///    nur, wenn dieselbe Quelle den Coin heute zum aktuellen Portfolio-Kurs führt; sonst nächste
///    Quelle. Binance-Spot liefert die aktuellen Kurse selbst und braucht die Prüfung nicht.
///  - Ohne Bezugskurs (aktueller Kurs unbekannt) ist keine Prüfung möglich — der Kurs gilt.
enum PricePlausibility {
    /// Grösste erlaubte Abweichung (Anteil) — dieselbe wie bei der Prozent-Pille.
    static let maxGap = DayChange.maxPriceGap

    private static func valid(_ v: Double?) -> Double? {
        guard let v, v > 0, v.isFinite else { return nil }
        return v
    }

    /// Passt `value` zum Bezug `reference` (|value / reference − 1| ≤ `maxGap`)?
    /// Ungültiger Wert: nein. Ohne gültigen Bezug: ja (nicht prüfbar).
    static func matches(_ value: Double?, reference: Double?) -> Bool {
        guard let v = valid(value) else { return false }
        guard let r = valid(reference) else { return true }
        return abs(v / r - 1) <= maxGap
    }

    /// Wertverlauf: gehört die Kerzenreihe (aufsteigend nach Tag) zum aktuellen Kurs `live`?
    /// Leere Reihe oder kein aktueller Kurs: ja.
    static func closesMatchLive(_ sortedCloses: [(day: Int, close: Double)], live: Double?) -> Bool {
        guard let last = sortedCloses.last?.close else { return true }
        guard valid(live) != nil else { return true }
        return matches(live, reference: last)
    }

    /// Stichtag: Tagesschluss `close` einer Quelle übernehmen?
    /// - Parameters:
    ///   - trusted: Quelle der aktuellen Portfolio-Kurse selbst (Binance-Spot) — keine Prüfung
    ///   - sourceLatest: jüngster Schluss derselben Quelle für dasselbe Symbol (nil = unbekannt)
    ///   - current: aktueller Portfolio-Kurs des Coins in USDT (nil = unbekannt)
    static func acceptSourceClose(_ close: Double?, trusted: Bool, sourceLatest: Double?, current: Double?) -> Bool {
        guard valid(close) != nil else { return false }
        if trusted || valid(current) == nil { return true }
        // Prüfung nötig, aber die Quelle nennt keinen heutigen Kurs: lieber verwerfen
        guard let latest = valid(sourceLatest) else { return false }
        return matches(current, reference: latest)
    }
}
