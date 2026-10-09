import Foundation

/// Was der Portfolio-Bereich gerade zeigen darf — wie `PortfolioAccess` (Android).
enum PortfolioAccess: Equatable, Sendable {
    /// Einstellungen noch nicht gelesen und eine Sperre wäre fällig: nichts zeigen.
    case pending
    /// Portfolio-Sperre an und in dieser Sitzung noch nicht entsperrt: ruhiger Sperr-Zustand.
    case locked
    /// Frei: Sperre aus oder in dieser Sitzung entsperrt.
    case open
}

/// Regeln der Portfolio-Sperre — Spiegel von `PortfolioLockPolicy.kt`.
///
/// Gesperrt ist nur das Portfolio (Tab, Detailansicht, Erfassen-Blätter, Stichtag-Export,
/// Sicherung mit Portfolio-Daten, Portfolio-Widget). Merkliste, Hinzufügen, Zyklus,
/// Optionen und die übrigen Widgets sind immer frei. Entsperrt wird einmal je Sitzung;
/// nach mehr als `backgroundLimitSeconds` im Hintergrund wird wieder gesperrt.
enum PortfolioLockPolicy {
    /// Länger im Hintergrund → beim Zurückkehren wieder sperren (wie bisher die App-Sperre).
    static let backgroundLimitSeconds: TimeInterval = 60

    /// Zustand des Portfolio-Bereichs. `lockSetting` nil = noch nicht gelesen.
    static func access(lockSetting: Bool?, lockRequested: Bool) -> PortfolioAccess {
        guard lockRequested else { return .open }
        guard let lockSetting else { return .pending }
        return lockSetting ? .locked : .open
    }

    /// Portfolio gerade gesperrt (Einstellung an und Sitzung nicht entsperrt)?
    static func isLocked(lockSetting: Bool, lockRequested: Bool) -> Bool {
        lockSetting && lockRequested
    }

    /// Nach der Rückkehr aus dem Hintergrund (seit `backgroundSince`, nil = nie) wieder sperren?
    static func relockAfterBackground(backgroundSince: Date?, now: Date,
                                      limit: TimeInterval = backgroundLimitSeconds) -> Bool {
        guard let backgroundSince else { return false }
        return now.timeIntervalSince(backgroundSince) > limit
    }

    /// Sichern verlangt Entsperren, wenn die Datei Portfolio-Daten enthält und gesperrt ist.
    static func backupExportNeedsUnlock(locked: Bool, hasPortfolioData: Bool) -> Bool {
        locked && hasPortfolioData
    }

    /// Wiederherstellen verlangt Entsperren, solange gesperrt (die Sicherung kann die Sperre ausschalten).
    static func restoreNeedsUnlock(locked: Bool) -> Bool { locked }

    /// Ausschalten der Sperre verlangt Entsperren, solange gesperrt.
    static func disableNeedsUnlock(locked: Bool) -> Bool { locked }

    /// «Zum Portfolio hinzufügen» aus der Merkliste (Blatt zeigt Bestände) verlangt Entsperren, solange gesperrt.
    static func quickAddNeedsUnlock(locked: Bool) -> Bool { locked }

    /// Zeile «Portfolio-Sperre» in den Optionen nur mit eingeschaltetem Portfolio
    /// (der Wert bleibt beim Ausblenden erhalten).
    static func showSetting(portfolioEnabled: Bool) -> Bool { portfolioEnabled }

    /// Fenster schützen, solange die Sperre an ist und Portfolio-Beträge sichtbar sein können
    /// (Android: FLAG_SECURE). iOS deckt stattdessen die ganze App ab, sobald die Szene nicht
    /// aktiv ist und die Sperre an ist (`AppLock`, Sichtschutz-Fenster) — einfacher und strenger.
    static func secureWindow(lockSetting: Bool, portfolioVisible: Bool) -> Bool { lockSetting && portfolioVisible }

    /// Portfolio-Widget: ohne App-Sitzung, also gesperrt, solange die Einstellung an ist.
    static func widgetLocked(lockSetting: Bool) -> Bool { lockSetting }
}
