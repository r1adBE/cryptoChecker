import Foundation

// «Zuletzt gesucht» von «Paar hinzufügen»: höchstens 8 Begriffe, jüngster vorne, nur lokal gespeichert.
// Eigene Datei wie `ExplorerRecentSearches.kt`.

// MARK: - Zuletzt gesucht

/// «Zuletzt gesucht» unter dem leeren Suchfeld: die letzten Suchbegriffe, mit denen ein Paar
/// hinzugefügt oder die Suche bestätigt wurde — wie `RecentSearches` in `ExplorerRecentSearches.kt`.
enum ExplorerRecentSearches {
    /// Höchstens so viele Begriffe.
    static let max = 8

    /// Nur auf dem Gerät (`UserDefaults.standard`, nicht die App-Gruppe der Widgets);
    /// nicht in der App-Sicherung, wird nirgends hin gesendet.
    static let defaultsKey = "explorer_recent_searches"

    /// Getrimmt, Leerräume zusammengefasst, gross geschrieben (wie das Suchfeld); leer = nil.
    static func normalize(_ query: String) -> String? {
        let words = query.split(whereSeparator: { $0.isWhitespace }).map(String.init)
        let joined = words.joined(separator: " ").uppercased()
        return joined.isEmpty ? nil : joined
    }

    /// Neuer Begriff vorne; derselbe Begriff (auch anders geschrieben) nur einmal; höchstens `max`.
    static func add(_ list: [String], _ query: String) -> [String] {
        guard let q = normalize(query) else { return Array(list.prefix(max)) }
        let rest = list.filter { normalize($0) != q }
        return Array(([q] + rest).prefix(max))
    }

    /// Gespeicherte Liste bereinigen (leer/doppelt weg, höchstens `max`).
    static func sanitize(_ list: [String]) -> [String] {
        var seen = Set<String>()
        let cleaned = list.compactMap { normalize($0) }.filter { seen.insert($0).inserted }
        return Array(cleaned.prefix(max))
    }

    static func load(_ defaults: UserDefaults = .standard) -> [String] {
        sanitize(defaults.stringArray(forKey: defaultsKey) ?? [])
    }

    static func save(_ list: [String], _ defaults: UserDefaults = .standard) {
        if list.isEmpty {
            defaults.removeObject(forKey: defaultsKey)
        } else {
            defaults.set(list, forKey: defaultsKey)
        }
    }
}
