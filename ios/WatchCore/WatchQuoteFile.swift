import Foundation

/// Kurse der Uhr in einer Datei (App-Gruppe), gelesen und zusammengeführt über `NSFileCoordinator`: Uhr-App und
/// Komplikation sind zwei Prozesse und schreiben dieselbe Datei. Der Koordinator sperrt Lesen, Zusammenführen und
/// Schreiben auch prozessübergreifend als einen Schritt, sodass kein Prozess auf einem veralteten Stand zusammenführt
/// und Coins des anderen zurücksetzt. Kurse und Zeiten liegen zusammen in einem JSON (`[WatchCached]`). Wie Android
/// `WatchStore.mergeQuotes` (dort ein Prozess, eine Sperre).
struct WatchQuoteFile: Sendable {
    let url: URL

    /// Gespeicherte Kurse je Coin; fehlende oder unlesbare Datei = keine, Einträge ohne Zeit fallen weg. nil = die
    /// Datei war nicht zugänglich (Koordination fehlgeschlagen).
    func read() -> [String: WatchCached]? {
        var result: [String: WatchCached]?
        var error: NSError?
        NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &error) { url in
            result = Self.decode(url)
        }
        return result
    }

    /// Neue Kurse eines Abrufs, der zur Zeit `startedAt` begann, übernehmen (`WatchCache.merge`, nur Coins aus `keep`)
    /// und schreiben; Ergebnis = neuer Stand, nil = die Datei war nicht zugänglich (nichts geschrieben).
    @discardableResult
    func merge(_ fresh: [String: WatchQuote], startedAt: Int64, keep: [String]) -> [String: WatchCached]? {
        var result: [String: WatchCached]?
        var error: NSError?
        NSFileCoordinator().coordinate(writingItemAt: url, options: .forMerging, error: &error) { url in
            let merged = WatchCache.merge(Self.decode(url), fresh: fresh, startedAt: startedAt, keep: keep)
            guard let data = try? JSONEncoder().encode(Array(merged.values)),
                  (try? data.write(to: url, options: .atomic)) != nil else { return }
            // Kurse sind nur ein Zwischenspeicher: nicht sichern (atomares Schreiben legt die Datei jedes Mal neu an)
            var stored = url
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? stored.setResourceValues(values)
            result = merged
        }
        return result
    }

    private static func decode(_ url: URL) -> [String: WatchCached] {
        guard let data = try? Data(contentsOf: url),
              let list = try? JSONDecoder().decode([WatchCached].self, from: data) else { return [:] }
        return Dictionary(list.filter { $0.time > 0 }.map { ($0.quote.symbol, $0) }, uniquingKeysWith: { a, _ in a })
    }
}
