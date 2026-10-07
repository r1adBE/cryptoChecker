import Foundation

/// Stundenkerzen der letzten 24 h je Basis-Asset und Quote — gemeinsame Quelle für
/// den Mini-Chart der Merkliste (`WatchlistSparklineStore`, Schlusskurse gegen USDT)
/// und die 24-h-Veränderung der Prozent-Pille (`DayChange`, im `PriceRefresher`).
/// Wie `SparklineRepository.kt`: `CandleDataSource` mit Ausweich-Kette, 24 × 1 h.
///
/// Nur im Speicher, 15 Minuten gültig; höchstens vier Abrufe gleichzeitig. Fehlschläge
/// werden kürzer gemerkt. Ein Abruf läuft in einer eigenen Aufgabe zu Ende, auch wenn
/// der Aufrufer nicht mehr wartet — beim nächsten Mal liegt er dann bereit.
actor DayReferenceStore {
    static let shared = DayReferenceStore()

    /// Schlusskurse (Mini-Chart) und 24-h-Bezug (Pille) einer Reihe.
    struct Series: Sendable {
        let closes: [Double]
        let reference: DayReference?
    }

    private static let ttlMillis: Int64 = 15 * 60_000
    private static let failureTtlMillis: Int64 = 5 * 60_000
    private static let maxParallel = 4
    private static let points = 24

    private var cache: [String: (time: Int64, series: Series?)] = [:]
    private var inFlight: [String: Task<Series?, Never>] = [:]
    private var running = 0
    private var waiters: [CheckedContinuation<Void, Never>] = []

    /// Zwischengespeicherter 24-h-Bezug, auch wenn er schon etwas alt ist; nil ohne Eintrag.
    func cachedReference(base: String, quote: String) -> DayReference? {
        cache[Self.key(base, quote)]?.series?.reference
    }

    /// Frische Reihe (mindestens zwei Kerzen) oder nil. Gleichzeitige Anfragen teilen sich einen Abruf.
    func series(base: String, quote: String) async -> Series? {
        let key = Self.key(base, quote)
        guard !key.isEmpty else { return nil }
        if let entry = cache[key] {
            let age = TimeUtils.nowMillis - entry.time
            let ttl = entry.series == nil ? Self.failureTtlMillis : Self.ttlMillis
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
        cache[key] = (TimeUtils.nowMillis, result)
        inFlight[key] = nil
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
}
