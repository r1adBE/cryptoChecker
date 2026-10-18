import Foundation
import os

/// Systemprotokoll (Konsole.app / `log stream`) für Abläufe, die ohne offene App laufen und sonst nicht
/// nachvollziehbar wären: Hintergrund-Aufgabe, Widget-Neuladen, Speicher, Alarm-Lease.
///
/// Keine persönlichen Daten: keine Paare, Beträge, Adressen oder Dateipfade. Als `.public` nur Zahlen
/// (Anzahl, Dauer in ms) und feste Kennungen — alles andere bleibt in der Standardeinstellung `.private`.
enum AppLog {
    /// Gleiche Kennung in App und Widget-Erweiterung, damit ein Filter beide zeigt.
    static let subsystem = "com.cryptochecker.app"

    static let background = Logger(subsystem: subsystem, category: "background")
    static let widget = Logger(subsystem: subsystem, category: "widget")
    static let storage = Logger(subsystem: subsystem, category: "storage")
    static let lease = Logger(subsystem: subsystem, category: "lease")
}
