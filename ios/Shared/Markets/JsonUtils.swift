import Foundation

// Kleine Helfer zum Lesen der Börsenantworten. Gegenstück auf Android: util/JsonUtils.kt — dort als
// `forEach…`-Schleifen, hier als Listen für `for … in`:
//   forEachJSONObject → allObjects()   forEachOptJSONObject → optObjects()   forEachJSONArray → allArrays()
//   forEachString → allStrings()       forEachName → allNamedObjects()       optStrings → optStrings(_:)
// `optText`/`getText` heissen hier `optString`/`string` (JObject, beide behandeln JSON null als fehlend).

extension JArray {
    /// Jeder Eintrag als Objekt; einer vom falschen Typ wirft — ein geändertes Format soll auffallen.
    func allObjects() throws -> [JObject] {
        try (0..<count).map { position in try object(position) }
    }

    /// Nur die Einträge, die Objekte sind; andere werden übergangen. Nur wo die Börse fremde Einträge mitschickt,
    /// die kein Fehler sind — sonst `allObjects()`.
    func optObjects() -> [JObject] {
        raw.compactMap { entry in (entry as? [String: Any]).map(JObject.init) }
    }

    /// Jeder Eintrag als Liste; einer vom falschen Typ wirft.
    func allArrays() throws -> [JArray] {
        try (0..<count).map { position in try array(position) }
    }

    /// Jeder Eintrag als Text; null wirft.
    func allStrings() throws -> [String] {
        try (0..<count).map { position in try string(position) }
    }

    /// Objekt an Stelle `i` oder nil (wie `optJSONObject(i)`).
    func optObject(_ i: Int) -> JObject? { try? object(i) }

    /// Liste an Stelle `i` oder nil (wie `optJSONArray(i)`).
    func optArray(_ i: Int) -> JArray? { try? array(i) }
}

extension JObject {
    /// Jedes Feld mit seinem Namen; alle Werte müssen Objekte sein, sonst wird geworfen. Leeres Objekt: nichts.
    func allNamedObjects() throws -> [(name: String, object: JObject)] {
        try keys.map { field -> (name: String, object: JObject) in
            let value = try object(field)
            return (name: field, object: value)
        }
    }

    /// Die nicht leeren Texte eines Listenfelds («["TradFi", "Pre-IPO"]»); andere Einträge fallen weg. Ohne Liste:
    /// leer.
    func optStrings(_ key: String) -> [String] {
        guard let list = optArray(key) else { return [] }
        return list.raw.compactMap { entry in entry as? String }.filter { !$0.isEmpty }
    }
}
