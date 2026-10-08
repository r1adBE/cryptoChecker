import Foundation

/// Alarm «Nahe am Hoch / Tief» — wie `NearExtreme.kt`: Abstand des aktuellen Kurses
/// zum Hoch bzw. Tief der letzten 30 / 90 / 365 Tage (Tageskerzen).
///
/// Gespeichert wird ohne neue Felder:
///  - `threshold` = Abstand in Prozent (Standard 2 %)
///  - `windowHours` = Zeitraum in TAGEN (30, 90, 365; alles andere gilt als 30)
///  - `referenceAt` = 0 → scharf; > 0 → schon gemeldet, wartet auf Wiederscharfstellung
///  - `referencePrice` = zuletzt gemeldete Marke
///
/// Wer gemeldet hat, meldet erst wieder, wenn sich der Kurs deutlich aus der Zone
/// entfernt hat (`rearmDistance`) — oder bei einem weiteren, deutlich höheren Hoch
/// (tieferen Tief, `furtherStepPercent`), frühestens 1 h nach der letzten Meldung. Darüber gilt die Pause zwischen Alarmen; einmalige Alarme schalten sich ab.
enum NearExtreme {

    enum Side: Sendable { case high, low }

    /// Wählbare Zeiträume in Tagen.
    static let windows: [Int] = [30, 90, 365]
    static let defaultWindowDays = 30
    static let defaultDistancePercent = 2.0
    /// Abstand 0 = nur neue Hochs/Tiefs melden (Vorlage «Neues 30-Tage-Hoch»), keine Annäherung.
    /// Gespeichert wie jeder Abstand in `threshold`; ältere Versionen melden damit nie.
    static let newOnlyDistance = 0.0

    /// Alarm meldet nur neue Hochs/Tiefs (`newOnlyDistance`)?
    static func isNewOnly(_ thresholdPercent: Double) -> Bool { thresholdPercent == newOnlyDistance }
    /// Zwischenspeicher der Fenster-Hochs/-Tiefs je Paar.
    static let cacheMillis: Int64 = 6 * 60 * 60_000
    /// Tageskerzen für das längste Fenster plus laufender Tag, mit Reserve.
    static let candleLimit = 370
    private static let dayMillis: Int64 = 24 * 60 * 60_000

    /// Zeitraum aus dem gespeicherten Feld; Unbekanntes (z. B. Standard 1) → 30 Tage.
    static func windowDays(_ stored: Int) -> Int {
        windows.contains(stored) ? stored : defaultWindowDays
    }

    /// Hoch und Tief eines Zeitraums (Währung der Kerzen).
    struct Range: Codable, Equatable, Sendable {
        var high: Double
        var low: Double

        var isValid: Bool { high.isFinite && low.isFinite && low > 0 && high >= low }

        func scaled(_ factor: Double) -> Range { Range(high: high * factor, low: low * factor) }
    }

    /// Hoch/Tief der letzten `days` ABGESCHLOSSENEN Tageskerzen (die laufende zählt nicht).
    /// nil, wenn die Reihe kürzer als 90 % des Zeitraums ist (höchstens 300 Tage verlangt).
    static func range(_ candles: [MarketCandle], days: Int, now: Int64) -> Range? {
        guard days > 0 else { return nil }
        let completed = candles
            .filter { $0.openTime + dayMillis <= now && $0.high > 0 && $0.low > 0 }
            .sorted { $0.openTime < $1.openTime }
            .suffix(days)
        let needed = (min(days, 300) * 9 + 9) / 10
        guard completed.count >= max(needed, 2),
              let high = completed.map(\.high).max(),
              let low = completed.map(\.low).min() else { return nil }
        let r = Range(high: high, low: low)
        return r.isValid ? r : nil
    }

    /// Ergebnis der Prüfung.
    enum Decision: Equatable, Sendable {
        case idle
        /// Wieder scharf stellen (referenceAt = 0, referencePrice = nil).
        case rearm
        /// Melden: `newExtreme` = neues Hoch/Tief, `distancePercent` ≥ 0,
        /// `extreme` = Hoch bzw. Tief des Zeitraums, `level` = neu zu merkende Marke.
        case fire(newExtreme: Bool, distancePercent: Double, extreme: Double, level: Double)
    }

    /// Mindestabstand zwischen zwei Meldungen «weiteres neues Hoch/Tief» desselben Alarms.
    static let furtherExtremeMinMillis: Int64 = 60 * 60_000

    /// Ein weiteres neues Hoch/Tief meldet erst ab max(Schwelle / 2, 0,5 %) über der gemeldeten Marke.
    static func furtherStepPercent(_ thresholdPercent: Double) -> Double {
        max(thresholdPercent * 0.5, 0.5)
    }

    /// Abstand (Prozentpunkte), ab dem ein gemeldeter Alarm wieder scharf wird.
    static func rearmDistance(_ thresholdPercent: Double) -> Double {
        thresholdPercent + max(thresholdPercent * 0.5, 0.5)
    }

    static func decide(side: Side, price: Double, range: Range, thresholdPercent: Double,
                       armed: Bool, lastLevel: Double?, inCooldown: Bool,
                       lastTriggeredAt: Int64, now: Int64) -> Decision {
        guard price.isFinite, price > 0, range.isValid else { return .idle }
        // Abstand 0 = nur neue Hochs/Tiefs (`newOnlyDistance`); negativ/ungültig = nichts
        guard thresholdPercent.isFinite, thresholdPercent >= 0 else { return .idle }

        let extreme = side == .high ? range.high : range.low
        let beyond = side == .high ? price > extreme : price < extreme
        let distance = abs(price - extreme) / extreme * 100

        if beyond {
            // Scharf — oder deutlich weiter als die gemeldete Marke UND frühestens 1 h nach der letzten Meldung
            let further: Bool
            if armed { further = true }
            else if let lastLevel, lastLevel > 0 {
                let elapsed = now - lastTriggeredAt
                if lastTriggeredAt > 0 && elapsed >= 0 && elapsed < furtherExtremeMinMillis {
                    further = false
                } else {
                    let step = side == .high ? (price - lastLevel) / lastLevel * 100 : (lastLevel - price) / lastLevel * 100
                    further = step >= furtherStepPercent(thresholdPercent)
                }
            }
            else { further = true }
            guard further, !inCooldown else { return .idle }
            return .fire(newExtreme: true, distancePercent: distance, extreme: extreme, level: price)
        }
        if thresholdPercent > 0 && distance <= thresholdPercent {
            guard armed, !inCooldown else { return .idle }
            return .fire(newExtreme: false, distancePercent: distance, extreme: extreme, level: extreme)
        }
        if !armed && distance > rearmDistance(thresholdPercent) { return .rearm }
        return .idle
    }

    /// Cooldown wie in `AlarmEvaluator`.
    static func inCooldown(lastTriggeredAt: Int64, now: Int64, cooldownMinutes: Int) -> Bool {
        guard lastTriggeredAt > 0, cooldownMinutes > 0 else { return false }
        let elapsed = now - lastTriggeredAt
        return elapsed >= 0 && elapsed < Int64(cooldownMinutes) * 60_000
    }
}

/// Hoch und Tief der Zeiträume je Paar — wie `WindowRanges` (Android).
/// `currency` = Quote des Paars oder «USDT», wenn nur das USDT-Paar Kerzen hat.
struct NearExtremeRanges: Codable, Sendable {
    var currency: String
    /// Zeitraum in Tagen → Hoch/Tief.
    var ranges: [Int: NearExtreme.Range]
    var savedAt: Int64
}

/// Tageskerzen über `CandleDataSource` (Binance-Spiegel, Binance, Binance.US, Coinbase):
/// zuerst <BASE><QUOTE>, sonst <BASE>USDT. Eine Anfrage je Paar liefert alle drei Zeiträume.
/// Zwischenspeicher 6 h im Speicher und als kleine JSON-Datei im Caches-Ordner.
/// Keine neuen Hosts. Wie `NearExtremeDataSource.kt`.
enum NearExtremeDataSource {
    private static let formatVersion = 1
    private static let missingMillis: Int64 = 60 * 60_000
    private static let lock = NSLock()
    nonisolated(unsafe) private static var memory: [String: NearExtremeRanges] = [:]
    nonisolated(unsafe) private static var missing: [String: Int64] = [:]

    private struct Entry: Codable {
        let version: Int
        let value: NearExtremeRanges
    }

    /// nil, wenn keine Quelle das Paar (oder <BASE>USDT) führt.
    static func ranges(base: String, quote: String) async -> NearExtremeRanges? {
        let b = base.trimmingCharacters(in: .whitespaces).uppercased()
        let q = quote.trimmingCharacters(in: .whitespaces).uppercased()
        guard !b.isEmpty, !q.isEmpty else { return nil }
        let key = cacheKey(b, q)
        let now = TimeUtils.nowMillis

        if let hit = fromMemory(key, now: now) { return hit }
        if let disk = readDisk(key), fresh(disk.savedAt, now: now) {
            remember(key, disk)
            return disk
        }
        if isMissing(key, now: now) { return nil }

        guard let loaded = await fetch(base: b, quote: q, now: now) else {
            // Abgebrochen (Zeitbudget): «nicht gefunden» nicht merken
            if !Task.isCancelled { markMissing(key, now: now) }
            return nil
        }
        remember(key, loaded)
        writeDisk(key, loaded)
        return loaded
    }

    private static func fetch(base: String, quote: String, now: Int64) async -> NearExtremeRanges? {
        var currency = quote
        var candles = await CandleDataSource.candles(base: base, quote: quote, interval: .d1, limit: NearExtreme.candleLimit)
        if candles == nil {
            guard quote != "USDT", quote != "USD" else { return nil }
            currency = "USDT"
            candles = await CandleDataSource.candles(base: base, quote: "USDT", interval: .d1, limit: NearExtreme.candleLimit)
        }
        guard let list = candles else { return nil }
        var ranges: [Int: NearExtreme.Range] = [:]
        for days in NearExtreme.windows {
            if let r = NearExtreme.range(list, days: days, now: now) { ranges[days] = r }
        }
        guard !ranges.isEmpty else { return nil }
        return NearExtremeRanges(currency: currency, ranges: ranges, savedAt: now)
    }

    private static func fresh(_ savedAt: Int64, now: Int64) -> Bool {
        let age = now - savedAt
        return age >= 0 && age < NearExtreme.cacheMillis
    }

    private static func cacheKey(_ b: String, _ q: String) -> String {
        let clean: (String) -> String = { s in String(s.filter { $0.isASCII && ($0.isLetter || $0.isNumber) }) }
        return "nearext_\(clean(b))_\(clean(q))"
    }

    private static func fromMemory(_ key: String, now: Int64) -> NearExtremeRanges? {
        lock.lock(); defer { lock.unlock() }
        guard let hit = memory[key], fresh(hit.savedAt, now: now) else { return nil }
        return hit
    }

    private static func remember(_ key: String, _ value: NearExtremeRanges) {
        lock.lock(); defer { lock.unlock() }
        memory[key] = value
        missing[key] = nil
    }

    private static func isMissing(_ key: String, now: Int64) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard let at = missing[key] else { return false }
        let age = now - at
        return age >= 0 && age < missingMillis
    }

    private static func markMissing(_ key: String, now: Int64) {
        lock.lock(); defer { lock.unlock() }
        missing[key] = now
    }

    // MARK: Datei

    private static let directory: URL? = {
        guard let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first else { return nil }
        let dir = base.appendingPathComponent("near_extreme", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    private static func url(_ key: String) -> URL? {
        directory?.appendingPathComponent("\(key).v\(formatVersion).json")
    }

    private static func readDisk(_ key: String) -> NearExtremeRanges? {
        guard let url = url(key), let data = try? Data(contentsOf: url) else { return nil }
        guard let entry = try? JSONDecoder().decode(Entry.self, from: data), entry.version == formatVersion,
              !entry.value.ranges.isEmpty else {
            try? FileManager.default.removeItem(at: url)
            return nil
        }
        return entry.value
    }

    private static func writeDisk(_ key: String, _ value: NearExtremeRanges) {
        guard let url = url(key), let data = try? JSONEncoder().encode(Entry(version: formatVersion, value: value)) else { return }
        try? data.write(to: url, options: .atomic)
    }
}
