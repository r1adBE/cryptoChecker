import Foundation

/// Ein neuer Alarm je Editor: Ein zweiter Tipp auf «Speichern» bzw. eine Vorlage, während das Blatt noch zugeht, legte
/// sonst einen zweiten, gleichen Alarm an (id 0 → neuer Eintrag). Bearbeiten (feste id) bleibt ungebremst — es
/// überschreibt nur denselben Eintrag. Seit Build 98; wie `AlarmCreateGuard` (Android).
struct AlarmCreateGuard {
    private(set) var used = false

    /// true = weiter; false = in diesem Editor wurde schon ein Alarm angelegt (Tipp ignorieren).
    mutating func begin(creating: Bool) -> Bool {
        guard creating else { return true }
        guard !used else { return false }
        used = true
        return true
    }
}
