import Foundation

/// Entscheide der Hintergrund-Prüfung — wie `BackgroundSchedule.kt`. iOS nutzt nur die Sperre
/// gegen doppelte Läufe (`shouldSkip`); Wecker «Pünktlich prüfen» und Akku-Hinweis gibt es nur auf
/// Android (iOS entscheidet allein, wann geprüft wird). Der Rest steht für gleiche Tests auf beiden Seiten.
enum BackgroundSchedule {
    /// Kürzester Abstand der pünktlichen Prüfung (Android); über dem Doze-Minimum (rund 9 Min.).
    static let exactIntervalMillis: Int64 = 15 * 60_000

    /// Abstand der pünktlichen Prüfung = gewähltes Intervall der Hintergrund-Aktualisierung, nie unter
    /// `exactIntervalMillis` — wie Android `exactIntervalMillis`.
    static func exactIntervalMillis(backgroundIntervalMinutes: Int) -> Int64 {
        max(Int64(max(backgroundIntervalMinutes, 0)) * 60_000, exactIntervalMillis)
    }

    /// Lief eine Prüfung mit mindestens einem Kurs vor weniger als so langer Zeit, entfällt die nächste.
    static let dedupeWindowMillis: Int64 = 5 * 60_000

    /// Toleranz des Sperrfensters unter dem Wecker (`dedupeWindowMillis(trigger:…)`): Dauer eines Laufs samt Spielraum.
    static let dedupeToleranceMillis: Int64 = 2 * 60_000

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

    static func nextExactAt(now: Int64, intervalMillis: Int64 = exactIntervalMillis) -> Int64 { now + intervalMillis }

    /// Wecker neu stellen? Bei `force`, ohne gestellten, abgelaufen oder unplausibel weit weg (Uhr verstellt).
    static func needsArm(armedFor: Int64, now: Int64, force: Bool = false, intervalMillis: Int64 = exactIntervalMillis) -> Bool {
        force || armedFor <= now || armedFor > now + intervalMillis
    }

    /// Prüfung überspringen? Nur die vom System geplanten Läufe, wenn die letzte Prüfung mit mindestens
    /// einem Kurs keine `windowMillis` her ist. Widget und App laufen immer. Uhr verstellt: nicht.
    static func shouldSkip(trigger: CheckTrigger, lastFinishedAt: Int64, now: Int64,
                           windowMillis: Int64 = dedupeWindowMillis) -> Bool {
        guard trigger.scheduled, lastFinishedAt > 0 else { return false }
        let age = now - lastFinishedAt
        return age >= 0 && age < windowMillis
    }

    /// Sperrfenster für `shouldSkip` (Android): Mit gestelltem Wecker «Pünktlich prüfen» ist WorkManager nur das Netz
    /// darunter und entfällt, solange die letzte volle Prüfung kein Intervall her ist; sonst `dedupeWindowMillis`.
    /// iOS (`bgTask`) nutzt immer `dedupeWindowMillis`. Wie Android `dedupeWindowMillis`.
    /// Das Fenster ist um `dedupeToleranceMillis` kürzer als das Intervall (nie unter `dedupeWindowMillis`): Die letzte
    /// volle Prüfung wird am Ende eines Laufs vermerkt, der nächste Lauf beginnt ein Intervall nach dem Beginn des
    /// vorigen — sonst fiele bei ausbleibendem Wecker jeder zweite Lauf weg.
    static func dedupeWindowMillis(trigger: CheckTrigger, exactArmed: Bool, intervalMillis: Int64) -> Int64 {
        trigger == .worker && exactArmed
            ? max(intervalMillis - dedupeToleranceMillis, dedupeWindowMillis) : dedupeWindowMillis
    }

    /// Zählt ein Lauf als «Prüfung mit allen Alarmen» (iOS `lastAlarmCheckAt`, Android `lastFullCheck`)? Nur eine volle
    /// Prüfung (`isFullCheck`), und warten Alarme mit Zusatzdaten auf einen späteren Schritt (`deferredWaiting`: Volumen,
    /// Funding/OI, Hoch/Tief, Umrechnung — iOS-Hintergrund-Aufgabe), erst wenn auch sie geprüft sind
    /// (`deferredChecked`). Sonst bleibt der alte Wert: Die Sperre gegen den nächsten Lauf und «Zuletzt geprüft»
    /// wirkten sonst frisch, obwohl ein Teil der Alarme offen ist. Wie Android `marksAlarmCheck`.
    static func marksAlarmCheck(fullCheck: Bool, deferredWaiting: Bool, deferredChecked: Bool) -> Bool {
        fullCheck && (!deferredWaiting || deferredChecked)
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
