import Foundation

/// Verteilt die beobachteten Paar-Kennungen einer gefilterten Sammelabfrage («…?pair=A,B,C») so auf
/// mehrere Adressen, dass keine `maxURLLength` Zeichen erreicht. Gleiche Regeln wie `BulkPairChunks.kt`
/// (dieselben Fälle in `BulkPairChunksTest.kt`).
enum BulkPairChunks {
    /// Längere Adressen weisen viele Server und Proxys ab.
    static let maxURLLength = 2000

    /// Zeichen, die unkodiert in einer Query stehen können (Kraken «XXBTZUSD», Bitfinex «tDOGE:USD»).
    private static let safeCharacters = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._:~-")

    private static func isSafe(_ id: String) -> Bool { id.allSatisfy { safeCharacters.contains($0) } }

    /// Gruppen aus sortierten, eindeutigen Kennungen — eine Anfrage je Gruppe. nil, wenn sich nicht filtern
    /// lässt (keine Kennung oder eine mit Zeichen, die kodiert werden müssten); dann gilt die ungefilterte Abfrage.
    static func chunks(prefix: String, pairIds: [String], maxURLLength: Int = BulkPairChunks.maxURLLength) -> [[String]]? {
        let ids = Set(pairIds).subtracting([""]).sorted()
        guard !ids.isEmpty, ids.allSatisfy(isSafe) else { return nil }

        let base = prefix.count
        var groups: [[String]] = []
        var group: [String] = []
        var used = base
        for id in ids {
            // Ab der zweiten Kennung kommt ein Komma dazu
            let cost = group.isEmpty ? id.count : id.count + 1
            if !group.isEmpty, used + cost >= maxURLLength {
                groups.append(group)
                group.removeAll()
                used = base + id.count
            } else {
                used += cost
            }
            group.append(id)
        }
        if !group.isEmpty { groups.append(group) }
        return groups
    }

    /// Adresse einer Gruppe: Präfix und die Kennungen mit Komma getrennt.
    static func url(prefix: String, chunk: [String]) -> String {
        prefix + chunk.joined(separator: ",")
    }
}
