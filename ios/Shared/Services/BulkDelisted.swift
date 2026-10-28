import Foundation

/// Nicht mehr gehandelte Paare aus der GEFILTERTEN Sammelabfrage heraushalten. Binance verwirft `symbols=[…]` mit
/// einem delisteten Symbol ganz (HTTP 400 «Invalid symbol») — dann lud jede Aktualisierung die ungefilterte Liste
/// (~2 MB). Welches Symbol, sagt Binance nicht: erkannt an der vollständigen Ersatzliste (angefragt, aber nicht
/// darin). Diese Paare und die schon als «nicht mehr gehandelt» gespeicherten (`NotTraded`) bleiben aus der Anfrage;
/// sie fehlen dann in der vollständigen Liste und laufen über die bestehende Logik. Alle `recheckMillis` einmal
/// alle Paare (wieder gelistet?). Der Stand bleibt über Prozess-Starts erhalten (`BulkDelistedMemory`).
/// Wie `BulkDelisted.kt` (testdata/parity/bulk_delisted.json).
enum BulkDelisted {
    static let recheckMillis: Int64 = 6 * 60 * 60_000

    /// `delisted`: zuletzt in der vollständigen Liste fehlende Paare; `recheckedAt`: letzter Lauf mit allen Paaren.
    struct State: Equatable, Sendable {
        var delisted: Set<String>
        var recheckedAt: Int64
    }

    /// `all`: beobachtete Paare; `request`: davon anzufragen; `recheck`: Lauf mit allen Paaren.
    struct Plan: Equatable, Sendable {
        var all: [String]
        var request: [String]
        var recheck: Bool
    }

    /// - Parameter lowMemory: Widget-Erweiterung. Dort nie eine Neuprüfung (auch nicht bei altem Stand): ohne
    ///   ungefilterten Ersatz scheiterte die gefilterte Anfrage mit dem delisteten Symbol, und der Fehler überschriebe
    ///   die Markierung «nicht mehr gehandelt». Die Neuprüfung macht die App (der Stand liegt in der App-Gruppe). Nur iOS.
    static func plan(state: State?, pairIds: [String], notTraded: Set<String>, now: Int64,
                     lowMemory: Bool = false) -> Plan {
        // Ohne Stand (Erstinstallation, gelöschte Daten) nicht gleich alles laden: die Frist läuft ab jetzt. Ausnahme:
        // alle Paare schon «nicht mehr gehandelt» — ohne Abfrage entstünde nie ein Stand, die Neuprüfung käme nie.
        var recheck = !lowMemory && !pairIds.isEmpty && pairIds.allSatisfy { notTraded.contains($0) }
        if let state, !lowMemory {
            let age = now - state.recheckedAt
            recheck = age < 0 || age >= recheckMillis
        }
        if recheck { return Plan(all: pairIds, request: pairIds, recheck: true) }
        let skip = (state?.delisted ?? []).union(notTraded)
        return Plan(all: pairIds, request: pairIds.filter { !skip.contains($0) }, recheck: false)
    }

    /// Stand nach der Abfrage; nur eine vollständige Liste (`complete`) sagt etwas über fehlende Paare — und nur über
    /// Paare, deren Teilabfrage geklappt hat (`incomplete`: z. B. Alpha gescheitert, Spot nicht).
    static func learn(state: State?, plan: Plan, tickers: Set<String>, failed: Set<String>, complete: Bool,
                      now: Int64, incomplete: Set<String> = []) -> State {
        let base = state ?? State(delisted: [], recheckedAt: now)
        guard complete else { return base }
        let all = Set(plan.all)
        let requested = Set(plan.request)
        let kept = base.delisted.filter { all.contains($0) && !tickers.contains($0) && !requested.contains($0) }
        let missing = plan.request.filter { !tickers.contains($0) && !failed.contains($0) && !incomplete.contains($0) }
        return State(delisted: kept.union(missing), recheckedAt: plan.recheck ? now : base.recheckedAt)
    }

    // MARK: Speicherform (UserDefaults / SharedPreferences)

    private static func usable(_ text: String) -> Bool {
        !text.isEmpty && !text.unicodeScalars.contains { CharacterSet.whitespacesAndNewlines.contains($0) }
    }

    /// Stände je Börse als kurzer Text: je Börse eine Zeile «Börse⇥recheckedAt⇥Paar Paar …», sortiert. Paare oder
    /// Börsen mit Trennzeichen (Leerraum) fallen weg — ein vergessenes Paar wird nur einmal mehr angefragt.
    static func encode(_ states: [String: State]) -> String {
        states.filter { usable($0.key) }.sorted { $0.key < $1.key }.map { key, s in
            let pairs = s.delisted.filter(usable).sorted().joined(separator: " ")
            return "\(key)\t\(s.recheckedAt)\t\(pairs)"
        }.joined(separator: "\n")
    }

    /// Unlesbare Einträge fallen weg (die Börse gilt dann als ohne Stand).
    static func decode(_ text: String?) -> [String: State] {
        guard let text, !text.isEmpty else { return [:] }
        var result: [String: State] = [:]
        for entry in text.split(separator: "\n", omittingEmptySubsequences: false) {
            let parts = entry.split(separator: "\t", omittingEmptySubsequences: false)
            guard parts.count == 3, !parts[0].isEmpty, let at = Int64(parts[1]) else { continue }
            let pairs = Set(parts[2].split(separator: " ").map(String.init))
            result[String(parts[0])] = State(delisted: pairs, recheckedAt: at)
        }
        return result
    }
}

/// Stand von `BulkDelisted` je Börse, dauerhaft in den Einstellungen der App-Gruppe (App, Hintergrund und Widget).
/// Nur im Speicher hätte jeder Prozess-Start keinen Stand — bei lauter «nicht mehr gehandelt»-Paaren folgte dann
/// jedes Mal eine Neuprüfung, bei Binance mit der ungefilterten Liste (~2 MB). Wie `BulkDelistedStore.kt`.
final class BulkDelistedMemory: @unchecked Sendable { // @unchecked: veränderlicher Zustand nur unter `lock` (NSLock)
    static let shared = BulkDelistedMemory()

    static let defaultsKey = "bulk_delisted"

    private let lock = NSLock()
    private let defaults: () -> UserDefaults
    /// Beim ersten Zugriff aus den Einstellungen geladen; danach massgebend für diesen Prozess.
    private var states: [String: BulkDelisted.State]?

    init(defaults: @escaping () -> UserDefaults = { SharedStorage.defaults }) {
        self.defaults = defaults
    }

    private func loaded() -> [String: BulkDelisted.State] {
        if let states { return states }
        let decoded = BulkDelisted.decode(defaults().string(forKey: Self.defaultsKey))
        states = decoded
        return decoded
    }

    func plan(_ exchangeKey: String, pairIds: [String], notTraded: Set<String>, now: Int64,
              lowMemory: Bool = false) -> BulkDelisted.Plan {
        lock.lock(); defer { lock.unlock() }
        return BulkDelisted.plan(state: loaded()[exchangeKey], pairIds: pairIds, notTraded: notTraded, now: now,
                                 lowMemory: lowMemory)
    }

    func learn(_ exchangeKey: String, plan: BulkDelisted.Plan, result: BulkTickers, now: Int64) {
        lock.lock(); defer { lock.unlock() }
        var all = loaded()
        let next = BulkDelisted.learn(state: all[exchangeKey], plan: plan, tickers: Set(result.tickers.keys),
                                      failed: result.failed, complete: result.complete, now: now,
                                      incomplete: result.incomplete)
        // Nur bei einer Änderung schreiben
        guard all[exchangeKey] != next else { return }
        all[exchangeKey] = next
        states = all
        defaults().set(BulkDelisted.encode(all), forKey: Self.defaultsKey)
    }
}
