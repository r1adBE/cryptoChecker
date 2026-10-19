import Foundation
import UserNotifications

/// Alarmton (#202). iOS sucht einen Mitteilungston (`UNNotificationSound(named:)`) nur an drei Orten: `Library/Sounds`
/// im Container der App, `Library/Sounds` im Container einer ihrer App Groups und im Bundle der App. Eigene Audiodateien
/// des Nutzers sind darum nicht vorgesehen, sondern eine kleine Auswahl mitgelieferter Töne
/// (`App/Resources/Sounds/*.wav`, je unter 2 s). Die Widget-Erweiterung meldet Kursalarme selbst; ob iOS dabei auch im
/// Bundle der Erweiterung sucht (die Töne liegen dort ebenfalls, `tools/gen_xcodeproj.py`), ist nicht zugesichert —
/// darum kopiert die App sie beim Start zusätzlich nach `Library/Sounds` der App Group (`installSharedSounds`), einem
/// Ort, den iOS für App und Erweiterung gleich durchsucht.
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
        return UNNotificationSound(named: UNNotificationSoundName(fileName(tone)))
    }

    static func fileName(_ tone: Tone) -> String { "\(tone.id).wav" }

    /// `Library/Sounds` in einem Container (App Group): dort sucht iOS Mitteilungstöne ausser im Bundle.
    static func soundsDirectory(in container: URL) -> URL {
        container.appendingPathComponent("Library", isDirectory: true).appendingPathComponent("Sounds", isDirectory: true)
    }

    /// Töne aus dem Bundle der App nach `Library/Sounds` der App Group (beim Start der App, abseits des Haupt-Threads).
    /// Ohne App Group nichts zu tun (dann gibt es auch keine Widget-Daten).
    static func installSharedSounds(bundle: Bundle = .main) {
        guard let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: SharedStorage.appGroup)
        else { return }
        let sources = tones.compactMap { tone in bundle.url(forResource: tone.id, withExtension: "wav") }
        installSounds(sources, into: soundsDirectory(in: container))
    }

    /// Kopiert jede Datei aus `sources` unter gleichem Namen nach `directory` — beliebig oft wiederholbar: Eine schon
    /// vorhandene gleiche Datei bleibt, eine abweichende (geänderter Ton in einer neuen Version) wird atomar ersetzt.
    /// Die Kopien sind aus dem Bundle jederzeit wiederherstellbar und darum nicht im Geräte-Backup.
    /// - Returns: Anzahl geschriebener Dateien.
    @discardableResult
    static func installSounds(_ sources: [URL], into directory: URL) -> Int {
        let fm = FileManager.default
        guard (try? fm.createDirectory(at: directory, withIntermediateDirectories: true)) != nil else { return 0 }
        var written = 0
        for source in sources {
            guard let data = try? Data(contentsOf: source) else { continue }
            let target = directory.appendingPathComponent(source.lastPathComponent)
            if let existing = try? Data(contentsOf: target), existing == data { continue }
            guard (try? data.write(to: target, options: [.atomic])) != nil else { continue }
            BackupExclusion.set(excluded: true, for: target)
            written += 1
        }
        return written
    }
}
