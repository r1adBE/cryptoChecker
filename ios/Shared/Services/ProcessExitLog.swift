import Foundation

/// Ein Ende bzw. Absturz der App laut iOS (MetricKit): Zeit (ms), Art und Kennung als Code, nie Text. Wie Android
/// `ProcessExit` (ApplicationExitInfo) für den Abschnitt «Process exits» im Diagnosebericht.
struct ProcessExitEntry: Codable, Equatable, Sendable {
    let at: Int64
    /// CRASH, HANG, CPU_EXCEPTION, DISK_WRITE_EXCEPTION, SLOW_LAUNCH oder ein Ende-Grund (z. B. MEMORY_LIMIT).
    let kind: String
    /// Kennung: Ausnahme und Signal, Dauer (s) oder Anzahl; «-» = keine.
    let detail: String
}

/// Letzte Enden der App (höchstens `maxEntries`, neueste zuerst), gespeichert nur auf dem Gerät. Die Umwandlung aus
/// den MetricKit-Berichten steht hier (testbar), das Abonnieren in der App (`MetricKitCollector`).
enum ProcessExitLog {
    static let maxEntries = 5
    private static let key = "process_exits_v1"

    /// Ausnahme-Typen (Mach) als Namen; unbekannte als Zahl.
    private static let exceptions: [Int: String] = [
        1: "EXC_BAD_ACCESS", 2: "EXC_BAD_INSTRUCTION", 3: "EXC_ARITHMETIC", 4: "EXC_EMULATION", 5: "EXC_SOFTWARE",
        6: "EXC_BREAKPOINT", 7: "EXC_SYSCALL", 8: "EXC_MACH_SYSCALL", 9: "EXC_RPC_ALERT", 10: "EXC_CRASH",
        11: "EXC_RESOURCE", 12: "EXC_GUARD", 13: "EXC_CORPSE_NOTIFY"
    ]

    /// Signale als Namen; unbekannte als Zahl.
    private static let signals: [Int: String] = [
        4: "SIGILL", 5: "SIGTRAP", 6: "SIGABRT", 8: "SIGFPE", 9: "SIGKILL", 10: "SIGBUS", 11: "SIGSEGV", 13: "SIGPIPE",
        15: "SIGTERM"
    ]

    /// Absturz: «EXC_BAD_ACCESS SIGSEGV» (fehlt beides: «-»).
    static func crash(at: Int64, exceptionType: Int?, signal: Int?) -> ProcessExitEntry {
        let parts = [exceptionType.map { exceptions[$0] ?? "exc_\($0)" }, signal.map { signals[$0] ?? "sig_\($0)" }]
        let detail = parts.compactMap { $0 }.joined(separator: " ")
        return ProcessExitEntry(at: at, kind: "CRASH", detail: detail.isEmpty ? "-" : detail)
    }

    /// Hänger, Start oder Ressourcen-Ausnahme mit Dauer in Sekunden («3.2 s»).
    static func timed(at: Int64, kind: String, seconds: Double?) -> ProcessExitEntry {
        let detail = seconds.map { String(format: "%.1f s", locale: Locale(identifier: "en_US_POSIX"), $0) } ?? "-"
        return ProcessExitEntry(at: at, kind: kind, detail: detail)
    }

    /// Ende-Gründe eines Tages (Zähler aus `MXAppExitMetric`): je Grund mit Anzahl > 0 ein Eintrag; normale Enden
    /// zählen nicht. `counts`: Grund → Anzahl, `scope`: «foreground» bzw. «background».
    static func exits(at: Int64, scope: String, counts: [String: Int]) -> [ProcessExitEntry] {
        counts.filter { $0.value > 0 }.sorted { $0.key < $1.key }.map {
            ProcessExitEntry(at: at, kind: $0.key, detail: "\(scope) \($0.value)")
        }
    }

    /// Neue Einträge vor die gespeicherten setzen, gleiche nur einmal, höchstens `maxEntries`, neueste zuerst.
    static func merged(_ new: [ProcessExitEntry], into old: [ProcessExitEntry]) -> [ProcessExitEntry] {
        var seen = Set<String>()
        return (new + old).sorted { $0.at > $1.at }.filter { seen.insert("\($0.at)|\($0.kind)|\($0.detail)").inserted }
            .prefix(maxEntries).map { $0 }
    }

    static func load(_ defaults: UserDefaults = .standard) -> [ProcessExitEntry] {
        guard let data = defaults.data(forKey: key) else { return [] }
        return (try? JSONDecoder().decode([ProcessExitEntry].self, from: data)) ?? []
    }

    static func add(_ new: [ProcessExitEntry], _ defaults: UserDefaults = .standard) {
        guard !new.isEmpty, let data = try? JSONEncoder().encode(merged(new, into: load(defaults))) else { return }
        defaults.set(data, forKey: key)
    }
}
