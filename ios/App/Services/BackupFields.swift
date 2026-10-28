import Foundation

/// Pflichtfelder der Sicherung (auch Journal, gleiches Format): Was der Schreiber immer schreibt, muss da sein und den
/// richtigen Typ haben — sonst ist die Datei unlesbar (`BackupManager.Failure.invalid`) und nichts wird ersetzt. Felder,
/// die der Schreiber bei leerem Wert weglässt, sind wahlfrei; stehen sie da, zählt ihr Typ. Unbekannte Felder werden
/// übergangen. Gleiche Listen in `Parity/backup_entries.json` und Android `BackupFields`.
enum BackupFields {
    /// Typ eines Felds; «…OrNull» = der Schreiber schreibt JSON null, wenn kein Wert da ist.
    enum FieldType: String, CaseIterable, Sendable {
        case bool = "BOOL", int = "INT", double = "DOUBLE", string = "STRING", array = "ARRAY", object = "OBJECT"
        case intMap = "INT_MAP", boolOrNull = "BOOL_OR_NULL", intOrNull = "INT_OR_NULL", doubleOrNull = "DOUBLE_OR_NULL"
        case stringOrNull = "STRING_OR_NULL", objectOrNull = "OBJECT_OR_NULL"
    }

    typealias Fields = [(key: String, type: FieldType)]

    /// Einträge einer Liste `part` der Sicherung mit ihren Pflicht- und wahlfreien Feldern.
    struct Entry: Sendable {
        let part: String
        let required: Fields
        var optional: Fields = []
    }

    static let watch = Entry(part: "watches", required: [
        ("id", .int), ("exchangeKey", .string), ("exchangeName", .string), ("baseAsset", .string), ("quoteAsset", .string),
        ("contractType", .string), ("pairId", .stringOrNull), ("sortOrder", .int), ("notificationEnabled", .bool),
        ("ttsEnabled", .bool), ("favorite", .bool), ("group", .stringOrNull), ("note", .stringOrNull), ("disabled", .bool),
    ])

    static let alarm = Entry(part: "alarms", required: [
        ("id", .int), ("watchId", .int), ("condition", .string), ("threshold", .double), ("enabled", .bool),
        ("repeating", .bool), ("sound", .bool), ("vibrate", .bool), ("speak", .bool), ("referencePrice", .doubleOrNull),
        ("windowHours", .int), ("currency", .stringOrNull),
    ])

    static let tx = Entry(part: "portfolio", required: [
        ("id", .int), ("coin", .string), ("type", .string), ("amountText", .string), ("priceText", .stringOrNull),
        ("time", .int), ("note", .stringOrNull), ("feeText", .stringOrNull), ("feeUnit", .stringOrNull),
        ("exchange", .stringOrNull), ("transferFrom", .stringOrNull), ("transferTo", .stringOrNull),
        ("feeCoin", .stringOrNull), ("feeCoinAmountText", .stringOrNull), ("feeCoinPriceText", .stringOrNull),
        ("importKey", .stringOrNull), ("acquiredTime", .intOrNull), ("toCoin", .stringOrNull),
        ("toAmountText", .stringOrNull), ("swapValueText", .stringOrNull), ("importBatch", .stringOrNull),
        ("giftToSpouse", .bool), ("linkedImportKey", .stringOrNull), ("importRestoreState", .objectOrNull),
        ("feeRow", .bool), ("interest", .bool), ("stakingMove", .bool),
    ])

    static let portfolioAlarm = Entry(part: "portfolioAlarms", required: [
        ("id", .int), ("kind", .string), ("threshold", .double), ("currency", .stringOrNull), ("enabled", .bool),
        ("repeating", .bool),
    ])

    static let watchedAddress = Entry(part: WatchedAddressRules.backupKey,
                                      required: [("chain", .string), ("address", .string), ("addedAt", .int)],
                                      optional: [("label", .string), ("observations", .array), ("countTokens", .bool),
                                                 ("tokenObservations", .object), ("networks", .array),
                                                 ("countInPortfolio", .bool), ("costLots", .array),
                                                 ("overlapChecked", .bool)])

    /// Alle Listen mit Einträgen.
    static let entries = [watch, alarm, tx, portfolioAlarm, watchedAddress]

    /// Objekte der Sicherung.
    private static let objectParts = ["favorites", "settings"]

    /// Ganze Sicherung (bzw. Journal) prüfen: «createdAt», jede Liste mit ihren Einträgen (`entries`), Favoriten je Art
    /// als Liste von Texten und jedes Feld der Einstellungen (`BackupManager.settingsFields`). Wie Android `checkParts`.
    static func checkParts(_ root: [String: Any]) throws {
        try check(root, [("createdAt", .int)], "")
        for entry in entries where !(root[entry.part] is [Any]) { throw BackupManager.Failure.invalid(entry.part) }
        for key in objectParts where !(root[key] is [String: Any]) { throw BackupManager.Failure.invalid(key) }
        let fav = root["favorites"] as? [String: Any] ?? [:]
        for kind in FavoriteKind.allCases where !((fav[kind.rawValue] as? [Any])?.allSatisfy({ $0 is String }) ?? false) {
            throw BackupManager.Failure.invalid("favorites." + kind.rawValue)
        }
        try check(root["settings"] as? [String: Any] ?? [:], BackupManager.settingsFields, "settings.")
        for entry in entries {
            for item in root[entry.part] as? [Any] ?? [] { try checkEntry(entry, item) }
        }
    }

    /// Ein Eintrag: Objekt mit allen Pflichtfeldern; wahlfreie Felder, wenn da, mit ihrem Typ.
    static func checkEntry(_ entry: Entry, _ item: Any) throws {
        guard let o = item as? [String: Any] else { throw BackupManager.Failure.invalid(entry.part) }
        try check(o, entry.required, entry.part + ".")
        for field in entry.optional where o[field.key].map({ !matches($0, field.type) }) ?? false {
            throw BackupManager.Failure.invalid(entry.part + "." + field.key)
        }
    }

    /// Jedes Feld aus `fields` vorhanden und vom richtigen Typ (Zahl statt Text und umgekehrt zählt als falsch);
    /// sonst `Failure.invalid(prefix + Feld)`.
    static func check(_ o: [String: Any], _ fields: Fields, _ prefix: String) throws {
        for field in fields where !(o[field.key].map({ matches($0, field.type) }) ?? false) {
            throw BackupManager.Failure.invalid(prefix + field.key)
        }
    }

    static func matches(_ value: Any, _ type: FieldType) -> Bool {
        switch type {
        case .bool: return isFlag(value)
        case .int: return isWhole(value)
        case .double: return finiteNumber(value) != nil
        case .string: return value is String
        case .array: return value is [Any]
        case .object: return value is [String: Any]
        case .intMap: return (value as? [String: Any])?.values.allSatisfy(isWhole) ?? false
        case .boolOrNull: return value is NSNull || isFlag(value)
        case .intOrNull: return value is NSNull || isWhole(value)
        case .doubleOrNull: return value is NSNull || finiteNumber(value) != nil
        case .stringOrNull: return value is NSNull || value is String
        case .objectOrNull: return value is NSNull || value is [String: Any]
        }
    }

    /// JSON true/false (eine NSNumber vom Typ CFBoolean), keine Zahl.
    static func isFlag(_ value: Any) -> Bool {
        guard let n = value as? NSNumber else { return false }
        return CFGetTypeID(n) == CFBooleanGetTypeID()
    }

    /// Endliche Zahl, kein Wahrheitswert.
    static func finiteNumber(_ value: Any) -> Double? {
        guard let n = value as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(), n.doubleValue.isFinite else { return nil }
        return n.doubleValue
    }

    static func isWhole(_ value: Any) -> Bool {
        finiteNumber(value).map { $0.truncatingRemainder(dividingBy: 1) == 0 } ?? false
    }
}
