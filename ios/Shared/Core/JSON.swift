import Foundation

/// Fehler beim Lesen einer Börsen-Antwort.
struct JSONError: LocalizedError {
    /// Technische Ursache (Protokoll, Fehlersuche) — wird nicht angezeigt.
    let message: String
    /// Anzeige: übersetzter Hinweis statt der deutschen/englischen Technik-Meldung.
    var errorDescription: String? { L("ios_error_unexpected_response") }
}

/// Dünne Hülle um `JSONSerialization`, die sich wie `org.json` verhält:
/// `getX` wirft bei fehlendem/falschem Wert, `optX` liefert einen Ersatzwert.
/// Zahlen dürfen — wie bei org.json — auch als Text kommen ("123.45").
struct JObject {
    let raw: [String: Any]

    init(_ raw: [String: Any]) { self.raw = raw }

    init(string: String) throws {
        guard let data = string.data(using: .utf8),
              let any = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]),
              let dict = any as? [String: Any]
        else { throw JSONError(message: "Kein JSON-Objekt") }
        self.raw = dict
    }

    var keys: [String] { Array(raw.keys) }
    func has(_ key: String) -> Bool { raw[key] != nil && !(raw[key] is NSNull) }
    func isNull(_ key: String) -> Bool { raw[key] == nil || raw[key] is NSNull }

    func object(_ key: String) throws -> JObject {
        guard let d = raw[key] as? [String: Any] else { throw JSONError(message: "\(key) fehlt") }
        return JObject(d)
    }

    func optObject(_ key: String) -> JObject? { (raw[key] as? [String: Any]).map(JObject.init) }

    func array(_ key: String) throws -> JArray {
        guard let a = raw[key] as? [Any] else { throw JSONError(message: "\(key) fehlt") }
        return JArray(a)
    }

    func optArray(_ key: String) -> JArray? { (raw[key] as? [Any]).map(JArray.init) }

    func string(_ key: String) throws -> String {
        guard let v = raw[key], !(v is NSNull) else { throw JSONError(message: "\(key) fehlt") }
        return JSONValue.string(v)
    }

    func optString(_ key: String, _ fallback: String = "") -> String {
        guard let v = raw[key], !(v is NSNull) else { return fallback }
        return JSONValue.string(v)
    }

    func double(_ key: String) throws -> Double {
        guard let v = raw[key], let d = JSONValue.double(v) else { throw JSONError(message: "\(key) ist keine Zahl") }
        return d
    }

    func optDouble(_ key: String, _ fallback: Double = .nan) -> Double {
        guard let v = raw[key], let d = JSONValue.double(v) else { return fallback }
        return d
    }

    /// Wie `optDoubleNoData` in der Android-Fassung.
    func optDoubleNoData(_ key: String) -> Double { optDouble(key, Ticker.noData) }

    func long(_ key: String) throws -> Int64 {
        guard let v = raw[key], let d = JSONValue.double(v) else { throw JSONError(message: "\(key) ist keine Zahl") }
        return JSONValue.int64(d)
    }

    func optLong(_ key: String, _ fallback: Int64 = 0) -> Int64 {
        guard let v = raw[key], let d = JSONValue.double(v) else { return fallback }
        return JSONValue.int64(d)
    }

    func int(_ key: String) throws -> Int { Int(try long(key)) }
    func optInt(_ key: String, _ fallback: Int = 0) -> Int { Int(optLong(key, Int64(fallback))) }

    func bool(_ key: String) throws -> Bool {
        guard let v = raw[key] else { throw JSONError(message: "\(key) fehlt") }
        // Wie org.json: nur echte Wahrheitswerte oder der Text "true"/"false". `v as? Bool` taugt
        // nicht – auf Apple-Plattformen wird auch die Zahl 1/0 von JSONSerialization zu Bool.
        if let n = v as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() { return n.boolValue }
        if let s = v as? String {
            if s.lowercased() == "true" { return true }
            if s.lowercased() == "false" { return false }
        }
        throw JSONError(message: "\(key) ist kein Wahrheitswert")
    }

    func optBool(_ key: String, _ fallback: Bool = false) -> Bool { (try? bool(key)) ?? fallback }

    /// Alle Einträge, deren Wert ein Objekt ist (wie `forEachName`).
    func forEachObject(_ body: (String, JObject) throws -> Void) rethrows {
        for (k, v) in raw {
            if let d = v as? [String: Any] { try body(k, JObject(d)) }
        }
    }
}

struct JArray {
    let raw: [Any]

    init(_ raw: [Any]) { self.raw = raw }

    init(string: String) throws {
        guard let data = string.data(using: .utf8),
              let any = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]),
              let arr = any as? [Any]
        else { throw JSONError(message: "Kein JSON-Array") }
        self.raw = arr
    }

    var count: Int { raw.count }
    var isEmpty: Bool { raw.isEmpty }

    func object(_ i: Int) throws -> JObject {
        guard i < raw.count, let d = raw[i] as? [String: Any] else { throw JSONError(message: "Eintrag \(i) fehlt") }
        return JObject(d)
    }

    func array(_ i: Int) throws -> JArray {
        guard i < raw.count, let a = raw[i] as? [Any] else { throw JSONError(message: "Eintrag \(i) fehlt") }
        return JArray(a)
    }

    func string(_ i: Int) throws -> String {
        guard i < raw.count, !(raw[i] is NSNull) else { throw JSONError(message: "Eintrag \(i) fehlt") }
        return JSONValue.string(raw[i])
    }

    func optString(_ i: Int, _ fallback: String = "") -> String {
        guard i < raw.count, !(raw[i] is NSNull) else { return fallback }
        return JSONValue.string(raw[i])
    }

    func double(_ i: Int) throws -> Double {
        guard i < raw.count, let d = JSONValue.double(raw[i]) else { throw JSONError(message: "Eintrag \(i) ist keine Zahl") }
        return d
    }

    func optDouble(_ i: Int, _ fallback: Double = .nan) -> Double {
        guard i < raw.count, let d = JSONValue.double(raw[i]) else { return fallback }
        return d
    }

    func long(_ i: Int) throws -> Int64 { JSONValue.int64(try double(i)) }

    var objects: [JObject] { raw.compactMap { ($0 as? [String: Any]).map(JObject.init) } }
    var arrays: [JArray] { raw.compactMap { ($0 as? [Any]).map(JArray.init) } }
    var strings: [String] { raw.compactMap { $0 is NSNull ? nil : JSONValue.string($0) } }
}

enum JSONValue {
    static func string(_ v: Any) -> String {
        if let s = v as? String { return s }
        if let n = v as? NSNumber {
            // Bool nicht als 1/0 ausgeben
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return n.boolValue ? "true" : "false" }
            let d = n.doubleValue
            if d.rounded() == d && abs(d) < 1e15 { return String(Int64(d)) }
            return n.stringValue
        }
        if let d = try? JSONSerialization.data(withJSONObject: v), let s = String(data: d, encoding: .utf8) { return s }
        return "\(v)"
    }

    static func double(_ v: Any) -> Double? {
        if v is NSNull { return nil }
        if let n = v as? NSNumber {
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return nil }
            return n.doubleValue
        }
        if let s = v as? String {
            let text = s.trimmingCharacters(in: .whitespaces)
            guard let d = Double(text) else { return nil }
            // Swift liest auch «nan», «inf», «infinity» (beliebige Schreibweise); Java/org.json nur
            // «NaN» und «Infinity» – andere Schreibweisen gelten wie dort als keine Zahl.
            if !d.isFinite {
                let core = text.hasPrefix("+") || text.hasPrefix("-") ? String(text.dropFirst()) : text
                if ["nan", "inf", "infinity"].contains(core.lowercased()) && core != "NaN" && core != "Infinity" { return nil }
            }
            return d
        }
        return nil
    }

    /// Ganzzahl wie Javas `(long) d` (org.json `getLong`/`optLong` auf Android): NaN → 0,
    /// zu groß/klein oder ±∞ → Int64.max/.min. `Int64(d)` würde in diesen Fällen abstürzen.
    static func int64(_ d: Double) -> Int64 {
        if d.isNaN { return 0 }
        // 2^63 ist als Double exakt darstellbar; alles ab dort passt nicht mehr in Int64
        if d >= 9_223_372_036_854_775_808.0 { return .max }
        if d <= -9_223_372_036_854_775_808.0 { return .min }
        return Int64(d)
    }
}
