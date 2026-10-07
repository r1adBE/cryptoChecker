import Foundation

/// Stundenkerzen der letzten 24 h je Basis-Asset und Quote — gemeinsame Quelle für
/// den Mini-Chart der Merkliste (`WatchlistSparklineStore`, Schlusskurse gegen USDT)
/// und den 24-h-Bezug der Prozent-Pille (`DayChange`, im `PriceRefresher`) — dieser nur
/// noch als Ausweich-Weg, wenn der Ticker keinen 24-h-Wert liefert.
/// Wie `SparklineRepository.kt`: `CandleDataSource` mit Ausweich-Kette, 24 × 1 h.
///
/// Getrennte Gültigkeit: Mini-Chart 15 Minuten, 24-h-Bezug 60 Minuten
/// (`DayReferenceCache.freshMillis`). Die 24-h-Bezüge liegen zusätzlich als kleine JSON-Datei
/// in Caches (Version, kaputte Datei = leer), damit die Pillen gleich nach dem Start Werte haben.
/// Höchstens sechs Abrufe gleichzeitig. Fehlschläge werden kürzer gemerkt. Ein Abruf läuft in
/// einer eigenen Aufgabe zu Ende, auch wenn der Aufrufer nicht mehr wartet — beim nächsten Mal
/// liegt er dann bereit.
actor DayReferenceStore {
    static let shared = DayReferenceStore()

    /// Schlusskurse (Mini-Chart) und 24-h-Bezug (Pille) einer Reihe.
    struct Series: Sendable {
        let closes: [Double]
        let reference: DayReference?
    }

    /// Gültigkeit der Mini-Chart-Kurve.
    static let closesTtlMillis: Int64 = 15 * 60_000
    private static let failureTtlMillis: Int64 = 5 * 60_000
    private static let maxParallel = 6
    private static let points = 24
    private static let saveDelayNanos: UInt64 = 3_000_000_000

    private var cache: [String: (time: Int64, series: Series?)] = [:]
    /// Zuletzt erfolgreich geladene 24-h-Bezüge mit Abrufzeit (auch aus der Datei).
    private var days: [String: (time: Int64, reference: DayReference)] = [:]
    private var inFlight: [String: Task<Series?, Never>] = [:]
    private var running = 0
    private var waiters: [CheckedContinuation<Void, Never>] = []
    private var restored = false
    private var savePending = false

    /// Gemerkter 24-h-Bezug (auch aus der Datei), höchstens `DayReferenceCache.maxAgeMillis` alt; sonst nil.
    func cachedReference(base: String, quote: String) -> DayReference? {
        restoreIfNeeded()
        guard let entry = days[Self.key(base, quote)],
              DayReferenceCache.usable(time: entry.time, now: TimeUtils.nowMillis) else { return nil }
        return entry.reference
    }

    /// 24-h-Bezug für `base` gegen `quote`; liegt ein höchstens 60 Minuten alter vor (auch aus
    /// der Datei), ohne Netz. nil, wenn keine Quelle das Paar liefert.
    func dayReference(base: String, quote: String) async -> DayReference? {
        restoreIfNeeded()
        let key = Self.key(base, quote)
        guard !key.isEmpty else { return nil }
        if let entry = days[key], DayReferenceCache.fresh(time: entry.time, now: TimeUtils.nowMillis) {
            return entry.reference
        }
        return await series(base: base, quote: quote, maxAgeMillis: DayReferenceCache.freshMillis)?.reference
    }

    /// Reihe (mindestens zwei Kerzen), höchstens `maxAgeMillis` alt, oder nil. Gleichzeitige
    /// Anfragen teilen sich einen Abruf. Standard: Gültigkeit des Mini-Charts.
    func series(base: String, quote: String, maxAgeMillis: Int64 = DayReferenceStore.closesTtlMillis) async -> Series? {
        restoreIfNeeded()
        let key = Self.key(base, quote)
        guard !key.isEmpty else { return nil }
        if let entry = cache[key] {
            let age = TimeUtils.nowMillis - entry.time
            let ttl = entry.series == nil ? Self.failureTtlMillis : maxAgeMillis
            if age >= 0 && age < ttl { return entry.series }
        }
        if let pending = inFlight[key] { return await pending.value }

        // Eigene Aufgabe: Bricht der Aufrufer ab, wird trotzdem fertig geladen und gemerkt.
        let task = Task { await self.loadAndStore(key, base: base, quote: quote) }
        inFlight[key] = task
        return await task.value
    }

    private func loadAndStore(_ key: String, base: String, quote: String) async -> Series? {
        let result = await load(base: base, quote: quote)
        let now = TimeUtils.nowMillis
        cache[key] = (now, result)
        inFlight[key] = nil
        // Fehlschlag: der zuletzt erfolgreiche Bezug bleibt (begrenzt durch maxAgeMillis)
        if let reference = result?.reference {
            days[key] = (now, reference)
            scheduleSave()
        }
        return result
    }

    private func load(base: String, quote: String) async -> Series? {
        await acquire()
        defer { release() }
        let b = base.trimmingCharacters(in: .whitespaces).uppercased()
        let q = quote.trimmingCharacters(in: .whitespaces).uppercased()
        let fetched = await CandleDataSource.candles(base: b, quote: q, interval: .h1, limit: Self.points)
        let candles = (fetched ?? []).filter { $0.close.isFinite && $0.close > 0 }
        guard candles.count >= 2, let first = candles.first, let last = candles.last else { return nil }
        return Series(closes: candles.map(\.close),
                      reference: DayReference.of(open: first.open, lastClose: last.close))
    }

    // Einfache Zählsperre: höchstens `maxParallel` Abrufe gleichzeitig.
    private func acquire() async {
        if running < Self.maxParallel {
            running += 1
            return
        }
        await withCheckedContinuation { continuation in
            waiters.append(continuation)
        }
        // Der Platz wurde von `release()` direkt übergeben — `running` bleibt gleich.
    }

    private func release() {
        if waiters.isEmpty {
            running -= 1
        } else {
            waiters.removeFirst().resume()
        }
    }

    private static func key(_ base: String, _ quote: String) -> String {
        let b = base.trimmingCharacters(in: .whitespaces).uppercased()
        let q = quote.trimmingCharacters(in: .whitespaces).uppercased()
        guard !b.isEmpty, !q.isEmpty else { return "" }
        return "\(b)|\(q)"
    }

    // MARK: Datei

    private struct FileContent: Codable {
        var v: Int
        var e: [DayReferenceCache.Stored]
    }

    private static let fileURL: URL? = {
        guard let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first else { return nil }
        return base.appendingPathComponent("day_references_v\(DayReferenceCache.formatVersion).json")
    }()

    /// Einmal je Prozess die Datei lesen; kaputt oder fremde Version = nichts gespeichert.
    private func restoreIfNeeded() {
        guard !restored else { return }
        restored = true
        guard let url = Self.fileURL, let data = try? Data(contentsOf: url) else { return }
        guard let content = try? JSONDecoder().decode(FileContent.self, from: data) else {
            try? FileManager.default.removeItem(at: url)
            return
        }
        for (key, stored) in DayReferenceCache.restore(version: content.v, entries: content.e, now: TimeUtils.nowMillis) {
            guard let reference = DayReference.of(open: stored.open, lastClose: stored.lastClose) else { continue }
            // Nie einen frischeren Wert aus dem Netz überschreiben
            if let current = days[key], current.time >= stored.time { continue }
            days[key] = (stored.time, reference)
        }
    }

    /// Schreibt höchstens alle drei Sekunden (viele Abrufe hintereinander = eine Datei).
    private func scheduleSave() {
        guard !savePending else { return }
        savePending = true
        Task {
            try? await Task.sleep(nanoseconds: Self.saveDelayNanos)
            await self.save()
        }
    }

    private func save() {
        savePending = false
        guard let url = Self.fileURL else { return }
        let now = TimeUtils.nowMillis
        let stored = days.map { DayReferenceCache.Stored(key: $0.key, time: $0.value.time,
                                                         open: $0.value.reference.open,
                                                         lastClose: $0.value.reference.lastClose) }
        let content = FileContent(v: DayReferenceCache.formatVersion,
                                  e: DayReferenceCache.toSave(stored, now: now))
        guard let data = try? JSONEncoder().encode(content) else { return }
        try? data.write(to: url, options: .atomic)
    }
}

/// Regeln für den Zwischenspeicher der 24-h-Bezüge — wie `DayReferenceCache.kt`.
///  - `freshMillis`: so lange gilt ein Bezug als frisch — kein neuer Abruf.
///  - `maxAgeMillis`: so alt darf ein Bezug höchstens sein, um noch benutzt zu werden.
///  - Datei mit `formatVersion`; fremde Version oder kaputte Einträge = nichts gespeichert.
enum DayReferenceCache {
    static let formatVersion = 1
    /// Gültigkeit eines 24-h-Bezugs (Mini-Chart-Kurven haben ihre eigene, kürzere).
    static let freshMillis: Int64 = 60 * 60_000
    /// Älter wird ein gemerkter Bezug nicht mehr benutzt (auch nicht von der Platte).
    static let maxAgeMillis: Int64 = 3 * 60 * 60_000
    /// Höchstens so viele Einträge in der Datei (die jüngsten).
    static let maxEntries = 3_000

    /// Ein gespeicherter Bezug; `key` wie «BTC|USDT».
    struct Stored: Codable, Equatable, Sendable {
        var key: String
        var time: Int64
        var open: Double
        var lastClose: Double

        enum CodingKeys: String, CodingKey {
            case key = "k", time = "t", open = "o", lastClose = "c"
        }
    }

    /// Abrufzeit liegt höchstens `maxAgeMillis` zurück (und nicht in der Zukunft).
    static func usable(time: Int64, now: Int64) -> Bool {
        let age = now - time
        return time > 0 && age >= 0 && age <= maxAgeMillis
    }

    /// Noch frisch: kein neuer Abruf nötig.
    static func fresh(time: Int64, now: Int64) -> Bool {
        let age = now - time
        return time > 0 && age >= 0 && age < freshMillis
    }

    /// Was beim Start aus der Datei übernommen wird: nur aktuelles Format, gültige Werte,
    /// Schlüssel «BASE|QUOTE», nicht zu alt; je Schlüssel der jüngste, höchstens `maxEntries`.
    static func restore(version: Int?, entries: [Stored], now: Int64) -> [String: Stored] {
        guard version == formatVersion else { return [:] }
        var result: [String: Stored] = [:]
        let valid = entries.filter { validKey($0.key) && usable(time: $0.time, now: now)
            && DayReference.of(open: $0.open, lastClose: $0.lastClose) != nil }
        for entry in valid.sorted(by: { $0.time > $1.time }) where result.count < maxEntries && result[entry.key] == nil {
            result[entry.key] = entry
        }
        return result
    }

    /// Was in die Datei kommt: nur noch benutzbare, die jüngsten zuerst, höchstens `maxEntries`.
    static func toSave(_ entries: [Stored], now: Int64) -> [Stored] {
        Array(entries.filter { usable(time: $0.time, now: now) }
            .sorted(by: { $0.time > $1.time })
            .prefix(maxEntries))
    }

    private static func validKey(_ key: String) -> Bool {
        let parts = key.split(separator: "|", omittingEmptySubsequences: false)
        return parts.count == 2 && parts.allSatisfy { part in
            let text = String(part)
            return !text.trimmingCharacters(in: .whitespaces).isEmpty
                && text == text.trimmingCharacters(in: .whitespaces).uppercased()
        }
    }
}
