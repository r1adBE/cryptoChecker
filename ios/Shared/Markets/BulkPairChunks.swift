import Foundation

/// Teilt die beobachteten Paar-Kennungen für eine gefilterte Sammelabfrage («…?pair=A,B,C»)
/// so auf, dass jede URL kürzer als `maxURLLength` Zeichen bleibt. Spiegel von
/// `BulkPairChunks.kt` (gleiche Fälle in `BulkPairChunksTest.kt`).
enum BulkPairChunks {
    /// Viele Server und Proxys lehnen längere URLs ab.
    static let maxURLLength = 2000

    /// Nur Zeichen, die in einer Query unverändert stehen dürfen (Kraken «XXBTZUSD», Bitfinex «tDOGE:USD»).
    private static let safeCharacters = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._:~-")

    /// Sortierte, doppelfreie Gruppen von Kennungen, je Gruppe eine Anfrage.
    /// nil: nicht filterbar (keine Kennungen oder eine mit Zeichen, die kodiert werden
    /// müssten) — dann gilt die ungefilterte Abfrage.
    static func chunks(prefix: String, pairIds: [String], maxURLLength: Int = BulkPairChunks.maxURLLength) -> [[String]]? {
        let ids = Array(Set(pairIds.filter { !$0.isEmpty })).sorted()
        if ids.isEmpty || ids.contains(where: { id in !id.allSatisfy { safeCharacters.contains($0) } }) { return nil }

        var result: [[String]] = []
        var current: [String] = []
        var length = prefix.count
        for id in ids {
            let added = current.isEmpty ? id.count : id.count + 1
            if !current.isEmpty && length + added >= maxURLLength {
                result.append(current)
                current = []
                length = prefix.count
            }
            length += current.isEmpty ? id.count : id.count + 1
            current.append(id)
        }
        if !current.isEmpty { result.append(current) }
        return result
    }

    /// URL einer Gruppe: Präfix plus kommagetrennte Kennungen.
    static func url(prefix: String, chunk: [String]) -> String {
        prefix + chunk.joined(separator: ",")
    }
}
