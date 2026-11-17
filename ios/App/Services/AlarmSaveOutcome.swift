import Foundation

/// Ausgang von `AppData.saveAlarm` — wie Android `AlarmSaveResult`: nur `.full` meldet «Grenze erreicht»,
/// `.refused` (gesperrt, nicht bestätigt, Paar fehlt) meldet nichts.
enum AlarmSaveOutcome: Equatable {
    case saved(firstAlarm: Bool)
    case full
    case refused

    /// Gespeichert: ob es der erste Alarm war; «Paar voll» (`onLimit`) nur bei `.full`, abgelehnt bzw. abgebrochen still
    /// (`nil`). Wie Android `AlarmSaveResult.savedOr`.
    func savedOr(onLimit: () -> Void) -> Bool? {
        switch self {
        case .saved(let firstAlarm): return firstAlarm
        case .full:
            onLimit()
            return nil
        case .refused: return nil
        }
    }
}
