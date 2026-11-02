import Foundation

/// Reihenfolge und Grenzen des Sammel-Abrufs der Logos (gleich in Android `CoinLogoQueue.kt`, gemeinsame Testfälle
/// `testdata/parity/coin_logos.json` › `queue`):
///
/// - `ordered`: Reihenfolge der Liste — für alle gleich, nie nach Merkliste oder Portfolio (sonst verrieten die Abrufe,
///   was jemand beobachtet): bei Binance gelistete Kürzel in der Reihenfolge der Symbolliste, dann die Rangliste von
///   CoinGecko (`CoinMeta.merge`). Geholt wird immer die ganze Liste.
/// - `nextStartable` / `run`: höchstens `maxParallel` Abrufe gleichzeitig, je Bild-Server höchstens
///   `perHostParallel` — kein Ansturm auf einen Server; die Pausen gebremster Server (`CoinLogoRetry`) gelten
///   unverändert.
enum CoinLogoQueue {
    /// Bild-Abrufe gleichzeitig insgesamt.
    static let maxParallel = 6
    /// Bild-Abrufe gleichzeitig je Server (CoinGecko, Binance).
    static let perHostParallel = 4

    /// Schlüssel von `map` in der Reihenfolge `order` (erste Nennung; `CoinMeta.Lists.order` oder die Zeilen der
    /// gespeicherten Zuordnung, `mapFileOrder`), übrige danach nach Schlüssel sortiert. Android braucht das nicht: Dort
    /// behält die Zuordnung (`LinkedHashMap`) ihre Reihenfolge.
    static func orderedKeys(_ map: [String: String], order: [String]) -> [String] {
        var seen = Set<String>()
        let first = order.filter { map[$0] != nil && seen.insert($0).inserted }
        return first + map.keys.filter { !seen.contains($0) }.sorted()
    }

    /// Einträge von `map` in der Reihenfolge `orderedKeys` — die Liste für `CoinLogos.downloadJobs`.
    static func ordered(_ map: [String: String], order: [String]) -> [(String, String)] {
        orderedKeys(map, order: order).compactMap { key in map[key].map { (key, $0) } }
    }

    /// Schlüssel einer gespeicherten Zuordnung (`CoinLogos.encode`: «Kürzel⇥Adresse» je Zeile) in Datei-Reihenfolge.
    static func mapFileOrder(_ text: String?) -> [String] {
        (text ?? "").split(separator: "\n").map { line in
            String(line.prefix { $0 != "\t" }).trimmingCharacters(in: .whitespaces)
        }
    }

    /// Nächster startbarer Abruf: Stelle des ersten in `hosts` (Server der wartenden Abrufe, in ihrer Reihenfolge),
    /// dessen Server weniger als `perHostParallel` laufende Abrufe hat (`running`); nil, wenn insgesamt schon
    /// `maxParallel` laufen oder jeder wartende Server voll ist.
    static func nextStartable(hosts: [String], running: [String: Int]) -> Int? {
        guard running.values.reduce(0, +) < maxParallel else { return nil }
        return hosts.firstIndex { (running[$0] ?? 0) < perHostParallel }
    }

    /// Führt `work` für alle `jobs` aus — in ihrer Reihenfolge, so früh es die Grenzen (`nextStartable`) erlauben;
    /// `done` läuft nach jedem Abruf nacheinander (nie gleichzeitig) — wie Android `CoinLogoQueue.run`.
    static func run<Job: Sendable, Output: Sendable>(_ jobs: [Job], host: (Job) -> String,
                                                     work: @escaping @Sendable (Job) async -> Output,
                                                     done: (Job, Output) async -> Void) async {
        guard !jobs.isEmpty else { return }
        var waiting = jobs
        var hosts = jobs.map(host)
        var running: [String: Int] = [:]
        await withTaskGroup(of: (job: Job, host: String, output: Output).self) { group in
            var active = 0
            repeat {
                while let i = nextStartable(hosts: hosts, running: running) {
                    let job = waiting.remove(at: i)
                    let server = hosts.remove(at: i)
                    running[server, default: 0] += 1
                    active += 1
                    group.addTask { (job, server, await work(job)) }
                }
                guard let finished = await group.next() else { break }
                running[finished.host, default: 1] -= 1
                active -= 1
                await done(finished.job, finished.output)
            } while !waiting.isEmpty || active > 0
        }
    }
}
