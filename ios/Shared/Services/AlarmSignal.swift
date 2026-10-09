import Foundation
import UserNotifications

/// «Alarm-Signal» — wie `AlarmSignal.kt`: gleicher Schlüssel («alarmSignal») und gleiche
/// Namen, damit Sicherungen zwischen Android und iOS passen.
///
/// iOS lässt Apps die Vibration nicht getrennt vom Ton steuern: sie richtet sich nach
/// Töne & Haptik. Deshalb bietet iOS nur `SYSTEM` (Ton der Einstellung «Alarmton») und
/// `SILENT` (nur Mitteilung, ohne Ton). Android-Werte werden beim Lesen auf den
/// nächsten iOS-Wert abgebildet (`ios`).
enum AlarmSignal: String, CaseIterable, Codable, Sendable {
    case SYSTEM
    case SOUND_VIBRATE
    case SOUND
    case VIBRATE
    case SILENT

    static let `default`: AlarmSignal = .SYSTEM

    /// Die Werte, die iOS anbietet.
    static let iosChoices: [AlarmSignal] = [.SYSTEM, .SILENT]

    /// Gespeicherter Name → Signal; fehlend oder unbekannt (neuere Version) → `SYSTEM`.
    static func from(name: String?) -> AlarmSignal {
        guard let name, let signal = AlarmSignal(rawValue: name) else { return .default }
        return signal
    }

    /// Nächster Wert, den iOS umsetzen kann: alles mit Ton → `SYSTEM`; ohne Ton
    /// («Nur Vibration», «Lautlos») → `SILENT` — eine Vibration ohne Ton gibt es auf iOS nicht.
    var ios: AlarmSignal {
        switch self {
        case .SYSTEM, .SOUND_VIBRATE, .SOUND: return .SYSTEM
        case .VIBRATE, .SILENT: return .SILENT
        }
    }

    /// Spielt ein Alarm mit diesem Signal (iOS) einen Ton? `alarmSound` = Schalter «Ton» des Alarms.
    func playsSound(alarmSound: Bool = true) -> Bool {
        ios == .SYSTEM && alarmSound
    }

    /// Ton der Alarm-Mitteilung: gewählter Alarmton oder nil (nur Mitteilung).
    func notificationSound(alarmSound: Bool = true, tone: String?) -> UNNotificationSound? {
        playsSound(alarmSound: alarmSound) ? AlarmSounds.notificationSound(tone) : nil
    }

    /// Schlüssel des Anzeigenamens (wie Android).
    var labelKey: String {
        switch self {
        case .SYSTEM: return "alarm_signal_system"
        case .SOUND_VIBRATE: return "alarm_signal_sound_vibrate"
        case .SOUND: return "alarm_signal_sound"
        case .VIBRATE: return "alarm_signal_vibrate"
        case .SILENT: return "alarm_signal_silent"
        }
    }
}
