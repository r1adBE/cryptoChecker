import Foundation
import os

/// Zahlenformate, zwischengespeichert je Zweck und Sprache/Region (Vorbild `DateFormatterCache`): ein
/// `NumberFormatter` zu bauen kostet, Listen und Karten formatieren je Zeile und Neuzeichnen. Thread-sicher
/// (`OSAllocatedUnfairLock`, Formatieren unter der Sperre). Die Formatter (nicht `Sendable`) verlassen die Sperre nie:
/// nur `withLock` fasst sie an, heraus kommt der Text. Sprache/Region oder Zahlenformat des Systems geändert: alle
/// verworfen.
enum NumberFormatterCache {
    private struct State {
        var formatters: [String: NumberFormatter] = [:]
        var observing = false
    }

    private static let state = OSAllocatedUnfairLock(uncheckedState: State())

    /// `number` formatiert mit dem Formatter für `purpose` (eindeutiger Name samt aller Einstellungen, die `configure`
    /// setzt); `configure` stellt ihn beim ersten Mal ein — `locale` ist schon gesetzt. nil = nicht formatierbar. Die Zahl
    /// entsteht erst unter der Sperre (nur Sendable-Werte gehen hinein).
    static func string(_ number: @autoclosure @Sendable () -> NSNumber, purpose: String, locale: Locale,
                       configure: @Sendable (NumberFormatter) -> Void) -> String? {
        let key = "\(purpose)|\(locale.identifier)"
        let (text, startObserving) = state.withLock { s -> (String?, Bool) in
            let startObserving = !s.observing
            s.observing = true
            let f: NumberFormatter
            if let known = s.formatters[key] {
                f = known
            } else {
                f = NumberFormatter()
                f.locale = locale
                configure(f)
                s.formatters[key] = f
            }
            return (f.string(from: number()), startObserving)
        }
        if startObserving { observeChanges() }
        return text
    }

    /// Systemsprache oder Region geändert: alle verwerfen (der Schlüssel erkennt nicht jede Änderung der Region).
    private static func observeChanges() {
        NotificationCenter.default.addObserver(forName: NSLocale.currentLocaleDidChangeNotification, object: nil,
                                               queue: nil) { _ in
            state.withLock { $0.formatters.removeAll() }
        }
    }

    /// Anzahl zwischengespeicherter Formatter (Tests).
    static var count: Int { state.withLock { $0.formatters.count } }

    /// Alle verwerfen — wie nach einem Sprachwechsel (Tests).
    static func clear() { state.withLock { $0.formatters.removeAll() } }
}

/// ISO-8601-Zeitpunkte lesen (mit und ohne Sekundenbruchteile) mit zwei geteilten Formattern statt zwei neuen je Aufruf;
/// unabhängig von Sprache und Region. Thread-sicher (Lesen unter der Sperre; die Formatter verlassen sie nie).
enum IsoDates {
    private static let formatters: OSAllocatedUnfairLock<(fraction: ISO8601DateFormatter, plain: ISO8601DateFormatter)> = {
        let fraction = ISO8601DateFormatter()
        fraction.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return OSAllocatedUnfairLock(uncheckedState: (fraction: fraction, plain: ISO8601DateFormatter()))
    }()

    /// Zuerst mit Sekundenbruchteilen, sonst ohne; nil, wenn ungültig.
    static func date(_ text: String) -> Date? {
        formatters.withLock { f in f.fraction.date(from: text) ?? f.plain.date(from: text) }
    }
}
