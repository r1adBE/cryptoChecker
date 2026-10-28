import Foundation

/// Gezählte Fehler im Hintergrund — nur Zahlen je Bereich und Zeitpunkt des letzten, nie Texte.
/// Wie `BackgroundErrorSummary` (Android).
struct BackgroundErrorSummary: Sendable, Equatable {
    var counts: [String: Int] = [:]
    /// Letzter gezählter Fehler (ms); 0 = keiner bekannt.
    var lastAt: Int64 = 0
    /// Fehlerart je Bereich: Bereich → (Typname des Fehlers → Anzahl); nie Meldungstexte. Wie Android.
    var kinds: [String: [String: Int]] = [:]
}

/// Fehler in Hintergrundarbeit sind im Release sonst unsichtbar (nur Systemprotokoll). Statt eines
/// Protokolls nur ein Zähler je Bereich; im Diagnosebericht eine Zeile. Gleiches Format wie `BackgroundErrors.kt`
/// (Testfälle `background_errors.json`).
enum BackgroundErrors {
    static let refresh = "refresh"
    static let storage = "storage"
    static let widgets = "widgets"
    static let logos = "logos"
    static let coins = "coins"
    static let live = "live"
    static let alarms = "alarms"
    static let schedule = "schedule"
    static let startup = "startup"
    static let other = "other"

    /// Feste Bereiche — nur diese Codes gelangen in den Bericht (kein Platz für freien Text).
    static let areas: Set<String> = [refresh, storage, widgets, logos, coins, live, alarms, schedule, startup, other]

    /// Unbekannter Code → `other`.
    static func area(_ code: String) -> String { areas.contains(code) ? code : other }

    /// Zeile im Bericht: «Background errors: refresh 2, logos 1 (last 2026-10-22 08:14 UTC)» bzw. «… none». Nach Anzahl
    /// absteigend, bei Gleichstand nach Code; Zeit immer in UTC. Wie Android.
    static func line(_ summary: BackgroundErrorSummary) -> String {
        var merged: [String: Int] = [:]
        for (code, n) in summary.counts where n > 0 { merged[area(code), default: 0] += n }
        guard !merged.isEmpty else { return "Background errors: none" }
        let parts = merged.sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }
            .map { "\($0.key) \($0.value)" }.joined(separator: ", ")
        guard summary.lastAt > 0 else { return "Background errors: \(parts)" }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        let last = formatter.string(from: Date(timeIntervalSince1970: TimeInterval(summary.lastAt) / 1000))
        return "Background errors: \(parts) (last \(last) UTC)"
    }

    /// Höchstens so viele verschiedene Fehlerarten je Bereich; weitere zählen als `otherKind`.
    static let maxKinds = 5
    static let otherKind = "Other"
    static let unknownKind = "Unknown"
    /// Feste Arten ohne Fehlerobjekt: alle Kursabrufe eines Laufs gescheitert bzw. Kurse geholt, aber nicht gespeichert.
    static let allFailedKind = "AllFetchesFailed"
    static let saveFailedKind = "SaveFailed"
    private static let kindMaxLength = 40
    private static let kindCharacters = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_")

    /// Fehlerart aus einem Typnamen (iOS: Typ des Fehlers, Android: einfacher Klassenname): nur A–Z, a–z, Ziffern und «_»,
    /// höchstens 40 Zeichen; leer → `unknownKind`. Kein Meldungstext, keine personenbezogenen Daten. Wie Android.
    static func kind(_ typeName: String) -> String {
        var clean = String.UnicodeScalarView()
        for scalar in typeName.unicodeScalars where kindCharacters.contains(scalar) && clean.count < kindMaxLength {
            clean.append(scalar)
        }
        let result = String(clean)
        return result.isEmpty ? unknownKind : result
    }

    /// iOS: Fehlerart eines Fehlers (Typname, z. B. «URLError»).
    static func kind(of error: any Error) -> String { kind(String(describing: type(of: error))) }

    /// Fach für `kind` in einem Bereich, der schon `known` Arten hat: bekannt oder noch Platz → `kind`, sonst `otherKind`.
    static func kindSlot(known: Set<String>, kind: String) -> String {
        known.contains(kind) || known.count < maxKinds ? kind : otherKind
    }

    /// Zeile «Background error kinds: refresh URLError 2, DecodingError 1; logos URLError 1»; nil = keine Arten gezählt
    /// (Zeile entfällt). Bereiche nach Summe absteigend, dann Code; Arten nach Anzahl absteigend, dann Name. Wie Android.
    static func kindsLine(_ summary: BackgroundErrorSummary) -> String? {
        var merged: [String: [String: Int]] = [:]
        for (code, kinds) in summary.kinds {
            for (name, n) in kinds where n > 0 { merged[area(code), default: [:]][kind(name), default: 0] += n }
        }
        guard !merged.isEmpty else { return nil }
        let areas = merged.map { (code: $0.key, kinds: $0.value, total: $0.value.values.reduce(0, +)) }
            .sorted { $0.total != $1.total ? $0.total > $1.total : $0.code < $1.code }
        let parts = areas.map { entry in
            let list = entry.kinds.sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }
                .map { "\($0.key) \($0.value)" }.joined(separator: ", ")
            return "\(entry.code) \(list)"
        }
        return "Background error kinds: " + parts.joined(separator: "; ")
    }
}

/// Zählt Fehler im Hintergrund dauerhaft je Bereich in der App Group (App und Widgets zählen
/// zusammen) — nur Zahlen. Wie `BackgroundErrorCounter` (Android, SharedPreferences). Dazu je Bereich die Fehlerart
/// (Typname, `BackgroundErrors.kind(of:)`, höchstens `BackgroundErrors.maxKinds` je Bereich).
enum BackgroundErrorCounter {
    private static let countPrefix = "bg_errors_n_"
    private static let kindsPrefix = "bg_errors_kinds_"
    private static let lastAtKey = "bg_errors_last_at"
    private static let lock = NSLock()

    /// Einen Fehler im Bereich `area` zählen (unbekannt → «other»); mit `error` auch seine Art (Typname, ohne Text), ohne
    /// Fehlerobjekt eine feste Art `kind` (z. B. `BackgroundErrors.allFailedKind`). Nicht bei gesperrter Ablage (Neustart
    /// vor dem ersten Entsperren): Schreiben in die noch unlesbaren Einstellungen könnte sie sonst überschreiben.
    static func record(_ area: String, now: Int64 = EpochTime.nowMillis, defaults: UserDefaults? = nil,
                       error: (any Error)? = nil, kind: String? = nil) {
        let store: UserDefaults
        if let defaults {
            store = defaults
        } else {
            guard SharedStorage.loadSettingsChecked().state != .inaccessible else { return }
            store = SharedStorage.defaults
        }
        let code = BackgroundErrors.area(area)
        let key = countPrefix + code
        lock.lock()
        defer { lock.unlock() }
        let n = store.integer(forKey: key)
        store.set(n == Int.max ? n : n + 1, forKey: key)
        store.set(Double(now), forKey: lastAtKey)
        guard let errorKind = kind.map({ BackgroundErrors.kind($0) }) ?? error.map({ BackgroundErrors.kind(of: $0) })
        else { return }
        var areaKinds = storedKinds(in: store, area: code)
        let slot = BackgroundErrors.kindSlot(known: Set(areaKinds.keys), kind: errorKind)
        let old = areaKinds[slot] ?? 0
        areaKinds[slot] = old == Int.max ? old : old + 1
        store.set(areaKinds, forKey: kindsPrefix + code)
    }

    /// Gespeicherte Arten eines Bereichs (Art → Anzahl).
    private static func storedKinds(in store: UserDefaults, area: String) -> [String: Int] {
        (store.dictionary(forKey: kindsPrefix + area) ?? [:]).compactMapValues { $0 as? Int }.filter { $0.value > 0 }
    }

    /// Zählerstand für den Diagnosebericht.
    static func summary(defaults: UserDefaults = SharedStorage.defaults) -> BackgroundErrorSummary {
        var counts: [String: Int] = [:]
        var kindsByArea: [String: [String: Int]] = [:]
        for area in BackgroundErrors.areas {
            let n = defaults.integer(forKey: countPrefix + area)
            if n > 0 { counts[area] = n }
            let areaKinds = storedKinds(in: defaults, area: area)
            if !areaKinds.isEmpty { kindsByArea[area] = areaKinds }
        }
        return BackgroundErrorSummary(counts: counts, lastAt: Int64(defaults.double(forKey: lastAtKey)), kinds: kindsByArea)
    }
}
