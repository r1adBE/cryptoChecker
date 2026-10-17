import Foundation

/// Entscheide der Hintergrund-Prüfung (Build 46) — wie `BackgroundSchedule.kt`. iOS nutzt nur die Sperre
/// gegen doppelte Läufe (`shouldSkip`); Wecker «Pünktlich prüfen» und Akku-Hinweis gibt es nur auf
/// Android (iOS entscheidet allein, wann geprüft wird). Der Rest steht für gleiche Tests auf beiden Seiten.
enum BackgroundSchedule {
    /// Abstand der pünktlichen Prüfung (Android); über dem Doze-Minimum (rund 9 Min.).
    static let exactIntervalMillis: Int64 = 15 * 60_000

    /// Lief eine Prüfung mit mindestens einem Kurs vor weniger als so langer Zeit, entfällt die nächste.
    static let dedupeWindowMillis: Int64 = 5 * 60_000

    /// Zustand des Schalters «Pünktlich prüfen» (Android).
    enum ExactState: Equatable {
        case off, active, permissionMissing, nothingToCheck
    }

    static func exactState(enabled: Bool, permitted: Bool, hasWork: Bool) -> ExactState {
        if !enabled { return .off }
        if !permitted { return .permissionMissing }
        if !hasWork { return .nothingToCheck }
        return .active
    }

    static func shouldArmExact(enabled: Bool, permitted: Bool, hasWork: Bool) -> Bool {
        exactState(enabled: enabled, permitted: permitted, hasWork: hasWork) == .active
    }

    static func nextExactAt(now: Int64) -> Int64 { now + exactIntervalMillis }

    /// Wecker neu stellen? Bei `force`, ohne gestellten, abgelaufen oder unplausibel weit weg (Uhr verstellt).
    static func needsArm(armedFor: Int64, now: Int64, force: Bool = false) -> Bool {
        force || armedFor <= now || armedFor > now + exactIntervalMillis
    }

    /// Prüfung überspringen? Nur die vom System geplanten Läufe, wenn die letzte Prüfung mit mindestens
    /// einem Kurs keine `dedupeWindowMillis` her ist. Widget und App laufen immer. Uhr verstellt: nicht.
    static func shouldSkip(trigger: CheckTrigger, lastFinishedAt: Int64, now: Int64) -> Bool {
        guard trigger.scheduled, lastFinishedAt > 0 else { return false }
        let age = now - lastFinishedAt
        return age >= 0 && age < dedupeWindowMillis
    }

    /// Volle Prüfung für `shouldSkip`? Alle abfragbaren Paare (`attempted`) versucht und mehr als die Hälfte
    /// mit Kurs. Live-Takte und Teil-Läufe zählen nicht — sie setzen nur die Uhrzeit im Widget. Wie Android.
    static func isFullCheck(attempted: Int, failed: Int) -> Bool {
        attempted > 0 && failed >= 0 && failed < attempted && (attempted - failed) * 2 > attempted
    }

    /// Nur iOS: Vormerken der Hintergrund-Aufgabe gescheitert — in «Letzte Prüfungen» eintragen? Nicht, wenn der
    /// neueste Eintrag schon so ein Fehler ist (fehlgeschlagen, Auslöser `bgTask`, Dauer 0), sonst füllte jeder
    /// Wechsel in den Hintergrund die Liste (z. B. mit ausgeschalteter Hintergrundaktualisierung).
    static func shouldLogSubmitError(newest: CheckRun?) -> Bool {
        guard let newest else { return true }
        return !(newest.trigger == .bgTask && newest.result == .failed && newest.durationMillis == 0)
    }

    static func showBatteryHint(batteryRestricted: Bool, hasWork: Bool) -> Bool { batteryRestricted && hasWork }
}
