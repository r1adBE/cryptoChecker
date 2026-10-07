import Foundation

// Entspricht util/JsonUtils.kt: strenge Iteration wie forEachJSONObject /
// forEachJSONArray / forEachString / forEachName — ein Element vom falschen
// Typ wirft (wie getJSONObject in org.json), statt still übersprungen zu werden.

extension JArray {
    /// Wie `forEachJSONObject`: jedes Element muss ein Objekt sein.
    func allObjects() throws -> [JObject] {
        var result: [JObject] = []
        result.reserveCapacity(count)
        for i in 0..<count { try result.append(object(i)) }
        return result
    }

    /// Wie `forEachJSONArray`: jedes Element muss ein Array sein.
    func allArrays() throws -> [JArray] {
        var result: [JArray] = []
        result.reserveCapacity(count)
        for i in 0..<count { try result.append(array(i)) }
        return result
    }

    /// Wie `forEachString`: jedes Element muss vorhanden (nicht null) sein.
    func allStrings() throws -> [String] {
        var result: [String] = []
        result.reserveCapacity(count)
        for i in 0..<count { try result.append(string(i)) }
        return result
    }

    /// Wie `optJSONObject(i)`.
    func optObject(_ i: Int) -> JObject? { try? object(i) }

    /// Wie `optJSONArray(i)`.
    func optArray(_ i: Int) -> JArray? { try? array(i) }
}

extension JObject {
    /// Wie `forEachName`: alle Werte müssen Objekte sein.
    func allNamedObjects() throws -> [(name: String, object: JObject)] {
        var result: [(name: String, object: JObject)] = []
        for name in keys {
            let o = try object(name)
            result.append((name: name, object: o))
        }
        return result
    }
}
