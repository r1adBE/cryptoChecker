import Foundation
import os

/// Wiederverwendete `DateFormatter`: einen anzulegen ist teuer (ICU lädt Muster und Kalender), und die Markt-Zeilen,
/// der Ticker und die Halving-Zeile formatierten bei jedem Neuzeichnen neu. Je Zweck, Sprache/Region und Zeitzone ein
/// Formatter — die Ausgabe bleibt dieselbe wie mit einem frischen. Wechselt die Systemsprache, das Zeitformat (12/24 h)
/// oder die Zeitzone, wird neu angelegt. Thread-sicher (Formatieren unter der Sperre; die Formatter, nicht `Sendable`,
/// verlassen sie nie — heraus kommt nur der Text).
enum DateFormatterCache {
    private struct State {
        var formatters: [String: DateFormatter] = [:]
        var observing = false
    }

    private static let state = OSAllocatedUnfairLock(uncheckedState: State())

    /// `date` formatiert mit dem Formatter für `purpose` (eindeutiger Name des Zwecks); `configure` stellt ihn beim
    /// ersten Mal ein (Stil bzw. Vorlage) — `locale` und die Zeitzone (Vorgabe: die aktuelle) sind schon gesetzt.
    static func string(_ date: Date, purpose: String, locale: Locale = .current, timeZone: TimeZone = .current,
                       configure: @Sendable (DateFormatter) -> Void) -> String {
        let key = "\(purpose)|\(locale.identifier)|\(timeZone.identifier)"
        let (text, startObserving) = state.withLock { s -> (String, Bool) in
            let startObserving = !s.observing
            s.observing = true
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
            return (f.string(from: date), startObserving)
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
                state.withLock { $0.formatters.removeAll() }
            }
        }
    }
}
