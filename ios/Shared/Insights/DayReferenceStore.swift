import Foundation

/// Stundenkerzen der letzten 24 h je Basis-Asset und Quote — gemeinsame Quelle für
/// den Mini-Chart der Merkliste (`WatchlistSparklineStore`, Schlusskurse gegen USDT)
/// und den 24-h-Bezug der Prozent-Pille (`DayChange`, im `PriceRefresher`) — dieser nur
/// noch als Ausweich-Weg, wenn der Ticker keinen 24-h-Wert liefert.
/// Wie `SparklineRepository.kt`: `CandleDataSource` mit Ausweich-Kette, 24 × 1 h.
///
/// Für die Tages-Basen der %-Änderung (`ChangeBasis`) dieselbe Abfrage mit 26 statt 24 Kerzen:
/// Bezug ist die Eröffnung der Kerze, in der der Tagesbeginn liegt (`dayStartReference`) —
/// in der Datei nur die Kerzen der heutigen Tagesbeginne (UTC, Ortszeit und die gewählte Zone).
///
/// Getrennte Gültigkeit: Mini-Chart 15 Minuten, 24-h-Bezug 60 Minuten
/// (`DayReferenceCache.freshMillis`). Die 24-h-Bezüge liegen zusätzlich als kleine JSON-Datei
/// in Caches (Version, kaputte Datei = leer), damit die Pillen gleich nach dem Start Werte haben.
/// Höchstens sechs Abrufe gleichzeitig. Fehlschläge werden kürzer gemerkt. Ein Abruf läuft in
/// einer eigenen Aufgabe zu Ende, auch wenn der Aufrufer nicht mehr wartet — beim nächsten Mal
/// liegt er dann bereit.
actor DayReferenceStore {
    static let shared = DayReferenceStore()

    /// Schlusskurse (Mini-Chart, die jüngsten 24), 24-h-Bezug (Pille) und die Eröffnung je
    /// Kerze (Startzeit → Eröffnung) für die Tages-Basen.
    struct Series: Sendable {
        let closes: [Double]
        let reference: DayReference?
        var opens: [Int64: Double] = [:]
        /// Anbieter der Kerzen (`CandleDataSource.candlesSourced`).
        var provider: String? = nil
    }

    /// Gültigkeit der Mini-Chart-Kurve.
    static let closesTtlMillis: Int64 = 15 * 60_000
    private static let failureTtlMillis: Int64 = 5 * 60_000
    /// So lange nach Beginn einer Stunde darf ihre Kerze bei der Quelle noch fehlen.
    private static let newCandleGraceMillis: Int64 = 10 * 60_000
    private static let maxParallel = 6
    private static let saveDelayNanos: UInt64 = 3_000_000_000

    private var cache: [String: (time: Int64, series: Series?)] = [:]
    /// Zuletzt erfolgreich geladene 24-h-Bezüge mit Abrufzeit (auch aus der Datei) und den
    /// Eröffnungen der Stundenkerzen (aus der Datei nur die der Tagesbeginne).
    private var days: [String: (time: Int64, reference: DayReference, opens: [Int64: Double], provider: String?)] = [:]
    private var inFlight: [String: Task<Series?, Never>] = [:]
    private var running = 0
    private var waiters: [CheckedContinuation<Void, Never>] = []
    private var restored = false
    private var savePending = false

    /// Schon geladene Stundenkurse gegen USDT mit Abrufzeit (ohne Netz, auch wenn älter) —
    /// für den Wertverlauf des Portfolio-Widgets; nil, wenn nichts geladen ist.
    func cachedCloses(base: String) -> (time: Int64, closes: [Double])? {
        guard let entry = cache[Self.key(base, "USDT")], let closes = entry.series?.closes else { return nil }
        return (entry.time, closes)
    }

    /// Gemerkter 24-h-Bezug (auch aus der Datei), höchstens `DayReferenceCache.maxAgeMillis` alt; sonst nil.
    func cachedReference(base: String, quote: String) -> DayReference? {
        restoreIfNeeded()
        guard let entry = days[Self.key(base, quote)],
              DayReferenceCache.usable(time: entry.time, now: TimeUtils.nowMillis) else { return nil }
        return entry.reference
    }

    /// Anbieter der Kerzen hinter dem gemerkten Bezug (`cachedReference`) — «Binance», «Binance.US»,
    /// «Coinbase»; nil, wenn unbekannt oder zu alt.
    func cachedProvider(base: String, quote: String) -> String? {
        restoreIfNeeded()
        guard let entry = days[Self.key(base, quote)],
              DayReferenceCache.usable(time: entry.time, now: TimeUtils.nowMillis) else { return nil }
        return entry.provider
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

    /// Bezug seit Tagesbeginn `dayStart` (Eröffnung der Kerze, in der er liegt, und letzter
    /// Schluss); liegt ein höchstens 60 Minuten alter Abruf vor, der diese Kerze schon enthält
    /// (auch aus der Datei), ohne Netz. nil ohne Quelle.
    func dayStartReference(base: String, quote: String, dayStart: Int64) async -> DayReference? {
        restoreIfNeeded()
        let key = Self.key(base, quote)
        guard !key.isEmpty else { return nil }
        let hour = ChangeBasisMath.hourOf(dayStart)
        if let entry = days[key], DayReferenceCache.fresh(time: entry.time, now: TimeUtils.nowMillis), entry.opens[hour] != nil {
            return ChangeBasisMath.reference(entry.opens, lastClose: entry.reference.lastClose, dayStart: dayStart)
        }
        guard let loaded = await series(base: base, quote: quote, maxAgeMillis: DayReferenceCache.freshMillis, needsHour: hour)
        else { return nil }
        return ChangeBasisMath.reference(loaded.opens, lastClose: loaded.reference?.lastClose, dayStart: dayStart)
    }

    /// Gemerkter Bezug seit `dayStart` (auch aus der Datei), Abruf höchstens
    /// `DayReferenceCache.maxAgeMillis` alt; sonst nil.
    func cachedDayStartReference(base: String, quote: String, dayStart: Int64) -> DayReference? {
        restoreIfNeeded()
        guard let entry = days[Self.key(base, quote)],
              DayReferenceCache.usable(time: entry.time, now: TimeUtils.nowMillis) else { return nil }
        return ChangeBasisMath.reference(entry.opens, lastClose: entry.reference.lastClose, dayStart: dayStart)
    }

    /// Reihe (mindestens zwei Kerzen), höchstens `maxAgeMillis` alt, oder nil. Gleichzeitige
    /// Anfragen teilen sich einen Abruf. Standard: Gültigkeit des Mini-Charts.
    /// `needsHour`: Kerze ab dieser Startzeit muss enthalten sein — ein Abruf von vor
    /// Mitternacht kennt die Kerze des neuen Tags noch nicht und gilt dann nicht als frisch.
    func series(base: String, quote: String, maxAgeMillis: Int64 = DayReferenceStore.closesTtlMillis,
                needsHour: Int64? = nil) async -> Series? {
        restoreIfNeeded()
        let key = Self.key(base, quote)
        guard !key.isEmpty else { return nil }
        if let entry = cache[key] {
            let age = TimeUtils.nowMillis - entry.time
            let ttl = entry.series == nil ? Self.failureTtlMillis : maxAgeMillis
            // Abruf von vor (oder kurz nach) Beginn dieser Stunde ohne ihre Kerze: neu laden; später
            // abgerufen und trotzdem ohne sie, liefert die Quelle sie nicht — bis zum Ablauf nicht erneut
            let hasHour = needsHour.map { hour in
                entry.series == nil || entry.series?.opens[hour] != nil || entry.time >= hour + Self.newCandleGraceMillis
            } ?? true
            if age >= 0 && age < ttl && hasHour { return entry.series }
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
        if let result, let reference = result.reference {
            days[key] = (now, reference, result.opens, result.provider)
            scheduleSave()
        }
        return result
    }

    private func load(base: String, quote: String) async -> Series? {
        await acquire()
        defer { release() }
        let b = base.trimmingCharacters(in: .whitespaces).uppercased()
        let q = quote.trimmingCharacters(in: .whitespaces).uppercased()
        let fetched = await CandleDataSource.candlesSourced(base: b, quote: q, interval: .h1, limit: ChangeBasisMath.candles)
        let candles = (fetched?.value ?? []).filter { $0.close.isFinite && $0.close > 0 }
        // Mini-Chart und rollender Bezug wie bisher aus den jüngsten 24 Kerzen
        let recent = Array(candles.suffix(ChangeBasisMath.rollingCandles))
        guard recent.count >= 2, let first = recent.first, let last = recent.last else { return nil }
        var opens: [Int64: Double] = [:]
        for candle in candles { opens[candle.openTime] = candle.open }
        return Series(closes: recent.map(\.close),
                      reference: DayReference.of(open: first.open, lastClose: last.close),
                      opens: opens,
                      provider: fetched?.provider)
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
            days[key] = (stored.time, reference, stored.startOpens, stored.provider)
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
        // Nur die Kerzen der heutigen Tagesbeginne (UTC, Ortszeit, gewählte Zone) mitschreiben
        let dayStarts = ChangeBasisMath.keptDayStarts(SharedStorage.loadSettings().changeBasis, now: now)
        let stored = days.map { entry in
            DayReferenceCache.Stored(key: entry.key, time: entry.value.time,
                                     open: entry.value.reference.open,
                                     lastClose: entry.value.reference.lastClose,
                                     starts: DayReferenceCache.keepStarts(entry.value.opens, dayStarts: dayStarts),
                                     provider: entry.value.provider)
        }
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

    /// Eröffnung der Stundenkerze ab `t` (volle Stunde) — für die Tagesbeginne.
    struct StartOpen: Codable, Equatable, Sendable {
        var t: Int64
        var o: Double
    }

    /// Ein gespeicherter Bezug; `key` wie «BTC|USDT»; `starts` nur für die Tagesbeginne
    /// (`keepStarts`), ältere Dateien ohne sie bleiben gültig. `provider`: Anbieter der Kerzen
    /// (Hinweis «Veränderung aus …-Kerzen»), ältere Dateien ohne ihn ebenso.
    struct Stored: Codable, Equatable, Sendable {
        var key: String
        var time: Int64
        var open: Double
        var lastClose: Double
        var starts: [StartOpen]? = nil
        var provider: String? = nil

        enum CodingKeys: String, CodingKey {
            case key = "k", time = "t", open = "o", lastClose = "c", starts = "s", provider = "p"
        }

        /// Gültige Eröffnungen (volle Stunde, Kurs > 0) als Startzeit → Eröffnung.
        var startOpens: [Int64: Double] {
            var out: [Int64: Double] = [:]
            for s in starts ?? [] where s.t > 0 && s.t % ChangeBasisMath.hourMillis == 0 && s.o.isFinite && s.o > 0 {
                out[s.t] = s.o
            }
            return out
        }
    }

    /// Von den Stunden-Eröffnungen nur die Kerzen, in denen einer der `dayStarts` liegt; nil wenn keine.
    static func keepStarts(_ opens: [Int64: Double], dayStarts: [Int64]) -> [StartOpen]? {
        let hours = Set(dayStarts.map { ChangeBasisMath.hourOf($0) })
        let kept = hours.sorted().compactMap { hour -> StartOpen? in
            guard let open = opens[hour], open.isFinite, open > 0 else { return nil }
            return StartOpen(t: hour, o: open)
        }
        return kept.isEmpty ? nil : kept
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
