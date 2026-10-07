import Foundation
import UserNotifications

/// Alarmton (#202). iOS spielt Mitteilungstöne nur aus dem App-Bundle ab —
/// eigene Audiodateien sind nicht möglich. Deshalb eine kleine Auswahl
/// mitgelieferter Töne (`App/Resources/Sounds/*.wav`, je unter 2 s).
enum AlarmSounds {
    struct Tone: Identifiable, Hashable, Sendable {
        /// Dateiname ohne Endung, zugleich gespeicherter Wert.
        let id: String
        /// Eigenname, wird nicht übersetzt.
        let name: String
    }

    static let tones: [Tone] = [
        Tone(id: "cc_chime", name: "Chime"),
        Tone(id: "cc_dingdong", name: "Ding-Dong"),
        Tone(id: "cc_pulse", name: "Pulse"),
        Tone(id: "cc_crystal", name: "Crystal"),
    ]

    static func tone(_ id: String?) -> Tone? {
        guard let id else { return nil }
        return tones.first { $0.id == id }
    }

    /// Ton für eine Alarm-Mitteilung; unbekannt oder nil = Standardton.
    static func notificationSound(_ id: String?) -> UNNotificationSound {
        guard let tone = tone(id) else { return .default }
        return UNNotificationSound(named: UNNotificationSoundName("\(tone.id).wav"))
    }
}
