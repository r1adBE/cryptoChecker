import Foundation

/// Wiederverwendete `DateFormatter`: einen anzulegen ist teuer (ICU lädt Muster und Kalender), und die Markt-Zeilen,
/// der Ticker und die Halving-Zeile formatierten bei jedem Neuzeichnen neu. Je Zweck, Sprache/Region und Zeitzone ein
/// Formatter — die Ausgabe bleibt dieselbe wie mit einem frischen. Wechselt die Systemsprache, das Zeitformat (12/24 h)
/// oder die Zeitzone, wird neu angelegt. Thread-sicher (Formatieren unter der Sperre).
enum DateFormatterCache {
    private struct State {
        var formatters: [String: DateFormatter] = [:]
        var observing = false
    }

    private static let state = LockedBox(State())

    /// `date` formatiert mit dem Formatter für `purpose` (eindeutiger Name des Zwecks); `configure` stellt ihn beim
    /// ersten Mal ein (Stil bzw. Vorlage) — `locale` und die aktuelle Zeitzone sind schon gesetzt.
    static func string(_ date: Date, purpose: String, locale: Locale = .current,
                       configure: (DateFormatter) -> Void) -> String {
        let timeZone = TimeZone.current
        let key = "\(purpose)|\(locale.identifier)|\(timeZone.identifier)"
        var text = ""
        var startObserving = false
        state.write { s in
            if !s.observing {
                s.observing = true
                startObserving = true
            }
            let f: DateFormatter
            if let known = s.formatters[key] {
                f = known
            } else {
                f = DateFormatter()
                f.locale = locale
                f.timeZone = timeZone
                configure(f)
                s.formatters[key] = f
            }
            text = f.string(from: date)
        }
        if startObserving { observeChanges() }
        return text
    }

    /// Systemsprache/Zeitformat oder Zeitzone geändert: alle verwerfen (der Schlüssel erkennt nicht jede Änderung,
    /// z. B. 24-Stunden-Zeit bei gleicher Sprache).
    private static func observeChanges() {
        let center = NotificationCenter.default
        for name in [NSLocale.currentLocaleDidChangeNotification, Notification.Name.NSSystemTimeZoneDidChange] {
            center.addObserver(forName: name, object: nil, queue: nil) { _ in
                state.write { $0.formatters.removeAll() }
            }
        }
    }
}
