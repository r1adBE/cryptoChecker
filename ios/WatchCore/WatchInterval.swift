import Foundation

/// Takt der Uhr (Uhr-App offen und Komplikation): das Intervall der Kurs-Aktualisierung des iPhones
/// (`backgroundIntervalMinutes`, übertragen in `WatchSettings.interval`), begrenzt auf `minMinutes`…`maxMinutes`.
///
/// Grenzen: Unter 15 Minuten aktualisiert keine der Uhren verlässlich — watchOS lädt Zeitleisten nur im Rahmen seines
/// Tagesbudgets (Zeitleiste `.after`, etwa alle 15 Minuten), Wear OS fragt die Komplikation höchstens im Takt aus dem
/// Manifest (900 s). Über 60 Minuten (grösste Wahl des iPhones; aus einer Sicherung kann mehr kommen) lädt die Uhr
/// trotzdem stündlich. Die Komplikation lädt, sobald ein Kurs älter ist als das Intervall weniger 5 Minuten
/// (`refetchAfterMillis`: 15 → 10 Min., 30 → 25 Min., 60 → 55 Min.). Wie Android `WatchInterval` (`Parity/watch_link.json`).
enum WatchInterval {
    static let defaultMinutes = 15
    static let minMinutes = 15
    static let maxMinutes = 60
    private static let earlyMinutes = 5

    /// Intervall des iPhones auf das für die Uhr Verlässliche begrenzt.
    static func clamp(_ minutes: Int) -> Int { min(max(minutes, minMinutes), maxMinutes) }

    /// Ab diesem Alter lädt die Uhr einen Kurs selbst neu (ohne Kurse vom iPhone).
    static func refetchAfterMillis(_ minutes: Int) -> Int64 { Int64(clamp(minutes) - earlyMinutes) * 60_000 }
}
