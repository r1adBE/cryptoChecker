import Foundation

/// Glocke im Kopf der Merkliste pulsiert einmal, wenn bei offener App ein Alarm auslöst —
/// wie `AlarmPulse.kt` (Android). Grundlage ist die letzte Auslösung aller Alarme
/// (`lastTriggeredAt`, Epoch-ms). Getestet in AlarmPulseTests.
enum AlarmPulse {
    /// Dauer des Pulses (grösser und zurück).
    static let seconds = 0.24

    /// Grösste Vergrösserung der Glocke.
    static let scale: CGFloat = 1.18

    /// Neue Auslösung seit dem zuletzt gesehenen Stand? `previous` nil = erster Stand
    /// (beim Öffnen schon vorhanden) → nein; zurückgesetzt (kleiner) → nein.
    static func isNew(previous: Int64?, current: Int64?) -> Bool {
        guard let previous, let current else { return false }
        return current > 0 && current > previous
    }
}
