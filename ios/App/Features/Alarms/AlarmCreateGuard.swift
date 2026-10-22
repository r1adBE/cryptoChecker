import Foundation

/// Ein neuer Alarm je Editor: Ein zweiter Tipp auf «Speichern» bzw. eine Vorlage, während das Blatt noch zugeht, legte
/// sonst einen zweiten, gleichen Alarm an (id 0 → neuer Eintrag). Seit Build 98; wie `AlarmCreateGuard` (Android).
/// Bearbeiten (feste id) sperrt ebenfalls (IOS6-01): sonst speicherte ein Doppeltipp zweimal (Haptik, Bestätigung doppelt).
struct AlarmCreateGuard {
    private(set) var used = false

    /// true = weiter; false = in diesem Editor läuft bzw. lief schon ein Speichern (Tipp ignorieren).
    /// `creating` (neu bzw. bearbeiten) sperrt gleich; der Name bleibt für die Lesbarkeit der Aufrufer.
    mutating func begin(creating: Bool) -> Bool {
        guard !used else { return false }
        used = true
        return true
    }

    /// Seit Build 106 (UX4-06): «Abbrechen» in der Rückfrage F-08 — nichts angelegt, der nächste Tipp zählt wieder.
    mutating func reset() {
        used = false
    }
}
