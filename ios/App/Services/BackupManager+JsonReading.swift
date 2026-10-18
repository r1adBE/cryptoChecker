import Foundation

// Werte aus der Sicherung mit Typ-Toleranz lesen (Zahl als Text und umgekehrt) — für alle Teile von `BackupManager`.

extension BackupManager {
    // MARK: Lesen mit Typ-Toleranz (wie optInt/optBoolean von org.json)

    /// Minute des Tages (0…1439) oder nil, wenn fehlend, gebrochen oder ausserhalb — wie `optMinute`.
    static func minute(_ o: [String: Any], _ key: String) -> Int? {
        guard !isNull(o, key), let value = double(o, key), value.isFinite,
              value.truncatingRemainder(dividingBy: 1) == 0,
              // `exactly:` statt `Int(value)`: ein riesiger Wert aus der Datei (z. B. 1e300) brach sonst ab
              let m = Int(exactly: value) else { return nil }
        return QuietHours.isValidMinute(m) ? m : nil
    }

    static func isNull(_ o: [String: Any], _ key: String) -> Bool {
        o[key] == nil || o[key] is NSNull
    }

    static func string(_ o: [String: Any], _ key: String) -> String? {
        if let s = o[key] as? String { return s }
        if let n = o[key] as? NSNumber { return n.stringValue }
        return nil
    }

    static func int64(_ o: [String: Any], _ key: String) -> Int64? {
        if let n = o[key] as? NSNumber { return n.int64Value }
        if let s = o[key] as? String { return Int64(s) ?? Double(s).flatMap(int64Truncating) }
        return nil
    }

    /// Ganzzahl aus einer Kommazahl der Sicherung, nach Null hin abgeschnitten (wie bisher
    /// `Int64(d)`); nil bei NaN, ±Unendlich oder ausserhalb von Int64 — `Int64(d)` brach dort ab.
    static func int64Truncating(_ value: Double) -> Int64? {
        guard value.isFinite else { return nil }
        return Int64(exactly: value.rounded(.towardZero))
    }

    static func int(_ o: [String: Any], _ key: String) -> Int? {
        int64(o, key).map { Int($0) }
    }

    static func double(_ o: [String: Any], _ key: String) -> Double? {
        if let n = o[key] as? NSNumber { return n.doubleValue }
        if let s = o[key] as? String { return Double(s) }
        return nil
    }

    static func bool(_ o: [String: Any], _ key: String) -> Bool? {
        if let n = o[key] as? NSNumber { return n.boolValue }
        if let s = o[key] as? String {
            switch s.lowercased() {
            case "true": return true
            case "false": return false
            default: return nil
            }
        }
        return nil
    }
}
