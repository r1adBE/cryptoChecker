import Foundation

/// Paare (Watch-Ids), deren letzter Kurs der Mark-Preis statt des Mittelkurses war (`Ticker.markFallback`, Hyperliquid
/// bei dünnem Orderbuch). Zeile und Aktionsblatt nennen den Kurs dann «Mark», die Alarm-Karte «Wartet auf aktuellen
/// Kurs». In den gemeinsamen Einstellungen (App, Hintergrund) statt im Stand, wie `DexLiquidityStore` — abgeleiteter
/// Marktwert, nicht in der Sicherung; jede Abfrage setzt ihn neu. Wie `MarkFallbackStore.kt`.
enum MarkFallbackStore {
    private static let defaultsKey = "mark_fallback"

    static var ids: Set<Int64> {
        Set((SharedStorage.defaults.array(forKey: defaultsKey) as? [NSNumber] ?? []).map(\.int64Value))
    }

    static func contains(_ watchId: Int64) -> Bool { ids.contains(watchId) }

    /// Ergebnis der letzten Abfrage von `watchId`.
    static func put(_ watchId: Int64, _ markFallback: Bool) {
        var current = ids
        guard current.contains(watchId) != markFallback else { return }
        if markFallback { current.insert(watchId) } else { current.remove(watchId) }
        SharedStorage.defaults.set(current.sorted().map { NSNumber(value: $0) }, forKey: defaultsKey)
    }
}

/// Wie das Aktionsblatt den Kurs benennt, wenn er kein letzter Handel ist (reine Logik, wie `PriceKindLabel.kt`):
/// `mark` (Mark-Preis mangels Mittelkurs — geht vor, darauf lösen auch keine Alarme aus), `mid` (`Market.priceIsMid`),
/// `hourly` (Schluss der jüngsten Stundenkerze, `Market.priceIsHourlyClose`, WOO X Spot). nil = kein Zusatz.
enum PriceKindLabel: Equatable {
    case mark, mid, hourly

    static func of(priceIsMid: Bool, markFallback: Bool, hourlyClose: Bool) -> PriceKindLabel? {
        if markFallback { return .mark }
        if priceIsMid { return .mid }
        if hourlyClose { return .hourly }
        return nil
    }
}
