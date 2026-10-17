import UIKit

/// Kurze Arbeiten der App (Aktualisierung, Alarme melden, Sicherung, CSV-Import, Wiederherstellen) zu Ende
/// führen, auch wenn die App währenddessen in den Hintergrund geht: `beginBackgroundTask` meldet sie bei iOS an,
/// sonst hält iOS die App womöglich mitten im Melden oder Schreiben an. Nur im App-Ziel — die Widget-Erweiterung
/// kennt `UIApplication.shared` nicht.
@MainActor
final class BackgroundTaskGuard {
    private var id: UIBackgroundTaskIdentifier = .invalid

    private init() {}

    /// Anmelden; mit `end()` wieder abmelden (läuft die Zeit vorher ab, meldet sich die Aufgabe selbst ab).
    static func begin(_ name: String) -> BackgroundTaskGuard {
        let task = BackgroundTaskGuard()
        task.id = UIApplication.shared.beginBackgroundTask(withName: name) {
            // Zeit abgelaufen: abmelden, sonst beendet iOS die App
            MainActor.assumeIsolated { task.end() }
        }
        return task
    }

    /// Abmelden (mehrfacher Aufruf schadet nicht).
    func end() {
        guard id != .invalid else { return }
        UIApplication.shared.endBackgroundTask(id)
        id = .invalid
    }
}
