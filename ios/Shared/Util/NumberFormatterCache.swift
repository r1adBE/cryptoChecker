import Foundation

/// Zahlenformate, zwischengespeichert je Zweck und Sprache/Region (Vorbild `DateFormatterCache`): ein
/// `NumberFormatter` zu bauen kostet, Listen und Karten formatieren je Zeile und Neuzeichnen. Thread-sicher
/// (`LockedBox`, Formatieren unter der Sperre). Sprache/Region oder Zahlenformat des Systems geändert: alle verworfen.
enum NumberFormatterCache {
    private struct State {
        var formatters: [String: NumberFormatter] = [:]
        var observing = false
    }

    private static let state = LockedBox(State())

    /// `number` formatiert mit dem Formatter für `purpose` (eindeutiger Name samt aller Einstellungen, die `configure`
    /// setzt); `configure` stellt ihn beim ersten Mal ein — `locale` ist schon gesetzt. nil = nicht formatierbar.
    static func string(_ number: NSNumber, purpose: String, locale: Locale,
                       configure: (NumberFormatter) -> Void) -> String? {
        let key = "\(purpose)|\(locale.identifier)"
        var text: String?
        var startObserving = false
        state.write { s in
            if !s.observing {
                s.observing = true
                startObserving = true
            }
            let f: NumberFormatter
            if let known = s.formatters[key] {
                f = known
            } else {
                f = NumberFormatter()
                f.locale = locale
                configure(f)
                s.formatters[key] = f
            }
            text = f.string(from: number)
        }
        if startObserving { observeChanges() }
        return text
    }

    /// Systemsprache oder Region geändert: alle verwerfen (der Schlüssel erkennt nicht jede Änderung der Region).
    private static func observeChanges() {
        NotificationCenter.default.addObserver(forName: NSLocale.currentLocaleDidChangeNotification, object: nil,
                                               queue: nil) { _ in
            state.write { $0.formatters.removeAll() }
        }
    }

    /// Anzahl zwischengespeicherter Formatter (Tests).
    static var count: Int { state.read { $0.formatters.count } }

    /// Alle verwerfen — wie nach einem Sprachwechsel (Tests).
    static func clear() { state.write { $0.formatters.removeAll() } }
}

/// ISO-8601-Zeitpunkte lesen (mit und ohne Sekundenbruchteile) mit zwei geteilten Formattern statt zwei neuen je Aufruf;
/// unabhängig von Sprache und Region. Thread-sicher (`LockedBox`).
enum IsoDates {
    private static let formatters: LockedBox<(fraction: ISO8601DateFormatter, plain: ISO8601DateFormatter)> = {
        let fraction = ISO8601DateFormatter()
        fraction.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return LockedBox((fraction: fraction, plain: ISO8601DateFormatter()))
    }()

    /// Zuerst mit Sekundenbruchteilen, sonst ohne; nil, wenn ungültig.
    static func date(_ text: String) -> Date? {
        formatters.read { f in f.fraction.date(from: text) ?? f.plain.date(from: text) }
    }
}
