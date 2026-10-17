import Foundation

/// Ein CSV-Importlauf für die Liste «Importe» — wie `PortfolioImportBatchInfo` (Android).
struct PortfolioImportBatchInfo: Sendable, Equatable {
    /// Kennung (`PortfolioImportBatch.newId`), so in `importBatch` gespeichert.
    let id: String
    /// Format des Imports (`CsvImportFormat`-Name, z. B. «RELAI»); nil = unlesbar.
    let format: String?
    /// Zeitpunkt des Imports (ms); nil = unlesbar.
    let time: Int64?
    /// Anzahl Transaktionen dieses Laufs, die noch da sind.
    let count: Int
}

/// Kennung eines CSV-Importlaufs — wie `PortfolioImportBatch.kt`: `FORMAT:Zeit(ms):Hash`, Hash = FNV-1a (32 Bit, 8 Hex-Zeichen)
/// über die Zeilenschlüssel. Alle Transaktionen eines Laufs tragen dieselbe Kennung; «Import rückgängig» löscht genau sie.
enum PortfolioImportBatch {
    /// Höchstlänge (gespeichert, Sicherung).
    static let maxLength = 64

    static func newId(format: String, time: Int64, keys: [String]) -> String {
        let cleaned = String(format.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            .filter { ($0.isASCII && ($0.isLetter || $0.isNumber)) || $0 == "_" }.prefix(20))
        return "\(cleaned.isEmpty ? "CSV" : cleaned):\(time):\(hash(keys))"
    }

    /// FNV-1a 32 Bit über die UTF-8-Bytes der Schlüssel (getrennt durch «\n»), 8 Hex-Zeichen klein.
    static func hash(_ keys: [String]) -> String {
        var h: UInt32 = 0x811C_9DC5
        for b in keys.joined(separator: "\n").utf8 {
            h ^= UInt32(b)
            h = h &* 0x0100_0193
        }
        let hex = String(h, radix: 16)
        return String(repeating: "0", count: max(0, 8 - hex.count)) + hex
    }

    /// Gespeicherte Kennung bereinigt: getrimmt, höchstens `maxLength` Zeichen (länger = ungültig); leer = nil.
    static func clean(_ raw: String?) -> String? {
        guard let t = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty, t.count <= maxLength else { return nil }
        return t
    }

    /// Format und Zeit aus der Kennung; unlesbar = nil.
    static func parse(_ id: String) -> (format: String?, time: Int64?) {
        let parts = id.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 3 else { return (nil, nil) }
        return (parts[0].isEmpty ? nil : parts[0], Int64(parts[1]))
    }

    /// Importläufe aus den Kennungen aller importierten Transaktionen: Anzahl je Lauf, neueste zuerst (dann Kennung).
    static func summarize(_ batches: [String?]) -> [PortfolioImportBatchInfo] {
        var counts: [String: Int] = [:]
        for raw in batches {
            if let id = clean(raw) { counts[id, default: 0] += 1 }
        }
        return counts.map { id, count in
            let p = parse(id)
            return PortfolioImportBatchInfo(id: id, format: p.format, time: p.time, count: count)
        }.sorted { a, b in
            let x = a.time ?? Int64.min, y = b.time ?? Int64.min
            if x != y { return x > y }
            return a.id < b.id
        }
    }
}
