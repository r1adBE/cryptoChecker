import Foundation

/// Paare (Watch-Ids), deren letzter Kurs der Mark-Preis statt des Mittelkurses war (`Ticker.markFallback`, Hyperliquid
/// bei dünnem Orderbuch). Zeile und Aktionsblatt nennen den Kurs dann «Mark», die Alarm-Karte «Wartet auf aktuellen
/// Kurs». In den gemeinsamen Einstellungen (App, Hintergrund) statt im Stand, wie `DexLiquidityStore` — abgeleiteter
/// Marktwert, nicht in der Sicherung; jede Abfrage setzt ihn neu. Wie `MarkFallbackStore.kt`.
enum MarkFallbackStore {
    /// Ein Schlüssel je Watch-Id (`PerKeyDefaults`); in älteren Versionen eine Liste unter «mark_fallback».
    static let storage = PerKeyDefaults(prefix: "mark_fallback.", legacyKey: "mark_fallback")
    /// Einmal je Prozess vor dem ersten Zugriff (thread-sicher, `static let`).
    private static let migration: Void = storage.migrateLegacy(SharedStorage.defaults)

    static func contains(_ watchId: Int64, defaults: UserDefaults = SharedStorage.defaults) -> Bool {
        _ = migration
        return defaults.bool(forKey: storage.key(String(watchId)))
    }

    /// Ergebnis der letzten Abfrage von `watchId`. Schreibt nur den eigenen Eintrag.
    static func put(_ watchId: Int64, _ markFallback: Bool, defaults: UserDefaults = SharedStorage.defaults) {
        guard contains(watchId, defaults: defaults) != markFallback else { return }
        if markFallback {
            defaults.set(true, forKey: storage.key(String(watchId)))
        } else {
            defaults.removeObject(forKey: storage.key(String(watchId)))
        }
    }
}

/// Wie das Aktionsblatt den Kurs benennt, wenn er kein letzter Handel ist (reine Logik, wie `PriceKindLabel.kt`):
/// `mark` (Mark-Preis mangels Mittelkurs — geht vor, darauf lösen auch keine Alarme aus), `mid` (`Market.priceIsMid`),
/// `hourly` (Schluss der jüngsten Stundenkerze, `Market.priceIsHourlyClose`, WOO X Spot). nil = kein Zusatz.
enum PriceKindLabel: Equatable {
    case mark, mid, hourly
    /// Täglicher Referenzkurs (EZB, `Market.priceIsDailyReference`): «Referenzkurs, täglich».
    case dailyReference

    static func of(priceIsMid: Bool, markFallback: Bool, hourlyClose: Bool,
                   dailyReference: Bool = false) -> PriceKindLabel? {
        if markFallback { return .mark }
        if priceIsMid { return .mid }
        if hourlyClose { return .hourly }
        if dailyReference { return .dailyReference }
        return nil
    }
}
