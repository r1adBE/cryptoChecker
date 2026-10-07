import Foundation

/// Holt die Marktdaten für «Ungewöhnliche Aktivität» und «Warum bewegt sich das?»
/// und gibt sie an den reinen `ActivityAnalyzer` — wie `ActivityMonitor.kt`.
/// Fehler bleiben still: Ohne Daten gibt es eben keine Signale.
///
/// Nur Foundation, kein App-Code: auch aus dem Widget-Prozess nutzbar.
enum ActivityMonitor {
    static let maxParallel = 4
    static let budgetMillis: Int64 = 20_000
    static let sourceTimeoutMillis: Int64 = 8_000
    private static let fngCacheMillis: Int64 = 10 * 60_000

    private static let lock = NSLock()
    /// Nur eine Auswertung zur Zeit; läuft schon eine, wird die neue übersprungen.
    nonisolated(unsafe) private static var running = false
    nonisolated(unsafe) private static var fearGreedCache: (time: Int64, value: FearGreed)? = nil

    // MARK: Auswertung aller Paare

    /// Prüft alle Paare, deren letzte Prüfung über 10 Min. her ist. Gedacht für
    /// nach dem Speichern der Kurse (mit dem frischen Stand, damit der Open-Interest-
    /// Vergleich den aktuellen Kurs nutzt); höchstens `budgetMillis` lang — was bis
    /// dahin fertig ist, wird gespeichert, der Rest kommt beim nächsten Durchlauf dran.
    /// - Returns: true, wenn eine Auswertung gelaufen ist (nicht übersprungen).
    @discardableResult
    static func analyzeAll(watches: [Watch], settings: AppSettings) async -> Bool {
        guard tryStart() else { return false }
        defer { finish() }
        await analyzeLocked(watches: watches, settings: settings)
        return true
    }

    private static func tryStart() -> Bool {
        lock.lock(); defer { lock.unlock() }
        if running { return false }
        running = true
        return true
    }

    private static func finish() {
        lock.lock(); defer { lock.unlock() }
        running = false
    }

    /// Ergebnisse der parallelen Prüfungen, threadsicher gesammelt.
    private final class Collector: @unchecked Sendable {
        private let lock = NSLock()
        private var items: [Int64: (watch: Watch, merge: ActivityAnalyzer.Merge)] = [:]

        func put(_ watch: Watch, _ merge: ActivityAnalyzer.Merge) {
            lock.lock(); defer { lock.unlock() }
            items[watch.id] = (watch: watch, merge: merge)
        }

        var all: [Int64: (watch: Watch, merge: ActivityAnalyzer.Merge)] {
            lock.lock(); defer { lock.unlock() }
            return items
        }
    }

    private static func analyzeLocked(watches all: [Watch], settings: AppSettings) async {
        // Nicht mehr gehandelte Paare: keine Auswertung, gespeicherte Signale (und ihre
        // Mitteilung) weg — sonst stünde ⚡ samt «Ungewöhnliche Bewegung» auf alten Kerzen da.
        let notTraded = NotTraded.ids(all)
        let stored = ActivityRepository.reports()
        for id in notTraded where stored[id] != nil { Notifier.cancelActivity(id) }
        let watches = all.filter { !notTraded.contains($0.id) }
        ActivityRepository.retain(Set(watches.map(\.id)))
        guard !watches.isEmpty else { return }

        let now = TimeUtils.nowMillis
        let previous = ActivityRepository.reports()
        let due = watches.filter { ActivityAnalyzer.isDue(previous[$0.id], now: now) }
        guard !due.isEmpty else { return }
        let sensitivity = settings.activitySensitivity

        let collector = Collector()
        // Höchstens vier gleichzeitig; das Zeitbudget bricht den Rest ab.
        let work = Task {
            await withTaskGroup(of: Void.self) { group in
                var index = 0
                while index < min(maxParallel, due.count) {
                    let watch = due[index]
                    index += 1
                    group.addTask {
                        await analyzeOne(watch, previous: previous[watch.id], now: now,
                                         sensitivity: sensitivity, into: collector)
                    }
                }
                while await group.next() != nil {
                    guard index < due.count, !Task.isCancelled else { continue }
                    let watch = due[index]
                    index += 1
                    group.addTask {
                        await analyzeOne(watch, previous: previous[watch.id], now: now,
                                         sensitivity: sensitivity, into: collector)
                    }
                }
            }
        }
        let timer = Task {
            try? await Task.sleep(nanoseconds: UInt64(budgetMillis) * 1_000_000)
            work.cancel()
        }
        await withTaskCancellationHandler {
            await work.value
        } onCancel: {
            work.cancel()
        }
        timer.cancel()

        let results = collector.all
        ActivityRepository.putAll(results.mapValues { $0.merge.report })

        // Meldungen: höchstens eine je Paar und Stunde, nur bei neuen Signalen
        for (watch, merge) in results.values {
            let notify = ActivityAnalyzer.shouldNotify(
                enabled: settings.activityAlerts,
                newKinds: merge.newKinds,
                lastNotifiedAt: ActivityRepository.lastNotifiedAt(watch.id),
                now: now
            )
            guard notify, let top = merge.report.signals.first(where: { merge.newKinds.contains($0.kind) }) else { continue }
            Notifier.showActivity(watch, top)
            ActivityRepository.setNotifiedAt(watch.id, now)
        }
    }

    private static func analyzeOne(_ watch: Watch, previous: ActivityReport?, now: Int64,
                                   sensitivity: ActivitySensitivity, into collector: Collector) async {
        let futuresPair = isFutures(watch)
        async let candlesJob = timed {
            await VolumeDataSource.hourlyCandles(base: watch.baseAsset, quote: watch.quoteAsset, futures: futuresPair)
        }
        async let futuresJob = perpetualFutures(watch)
        let candles = await candlesJob
        let futures = await futuresJob
        // Abbruch des ganzen Durchlaufs (Zeitbudget): Ergebnis verwerfen
        guard !Task.isCancelled else { return }

        // Veraltete oder unplausible Reihen (z. B. Spot nach Delisting) liefern keine Signale
        let valid = CandleSeries.usable(candles, now: now, tickerChange24h: watch.change24h)
        let stats = valid.flatMap { ActivityAnalyzer.hourStats($0) }
        let oi = openInterest(watch, futures, now: now, store: true)
        let fresh = ActivityAnalyzer.signals(
            stats: stats,
            fundingPercent: futures?.fundingRatePercent,
            oiChangePercent: oi?.changePercent,
            oiMinutes: oi?.minutes,
            now: now,
            sensitivity: sensitivity
        )
        collector.put(watch, ActivityAnalyzer.merge(previous: previous, fresh: fresh, now: now, sensitivity: sensitivity))
    }

    private static func perpetualFutures(_ watch: Watch) async -> FuturesInfo? {
        guard watch.contractType == .perpetual else { return nil }
        return await timed { try? await FuturesDataSource.fetch(watch: watch) }
    }

    /// Open Interest in Coins (USD-Wert / letzter Kurs), damit eine reine
    /// Kursbewegung nicht als OI-Sprung zählt. Vergleich gegen die gespeicherte
    /// Messung; `store` = neue Messung bei Bedarf ablegen.
    private static func openInterest(_ watch: Watch, _ futures: FuturesInfo?, now: Int64, store: Bool) -> OiUpdate? {
        guard let usd = futures?.openInterestUsd, let price = watch.lastPrice, price > 0 else { return nil }
        let units = usd / price
        let update = ActivityAnalyzer.oiChange(previous: ActivityRepository.oiSample(watch.id), currentUnits: units, now: now)
        if store && update.store {
            ActivityRepository.setOiSample(watch.id, OiSample(units: units, time: now))
        }
        return update
    }

    // MARK: «Warum bewegt sich das?»

    /// Lädt alles für das «Warum»-Blatt gleichzeitig, jede Quelle mit eigener Zeitgrenze.
    /// nil nur, wenn der Aufrufer abgebrochen hat.
    static func explain(_ watch: Watch) async -> WhyReport? {
        // Nicht mehr gehandelt: nichts laden, nichts einordnen
        if watch.isNotTraded { return ActivityAnalyzer.notLive(now: TimeUtils.nowMillis) }
        let isBtc = watch.baseAsset.uppercased() == "BTC"
        // Futures-Paar: Kerzen seines eigenen Markts (USDⓈ-M), nicht die des Spot-Paars
        let futuresPair = isFutures(watch)
        async let candlesJob = timed {
            await VolumeDataSource.hourlyCandles(base: watch.baseAsset, quote: watch.quoteAsset, futures: futuresPair)
        }
        async let referenceJob = timed { await VolumeDataSource.hourlyCandles(base: isBtc ? "ETH" : "BTC", quote: "USDT") }
        async let futuresJob = timed { await whyFutures(watch) }
        async let fearGreedJob = timed { await fearGreed() }

        let futures = await futuresJob
        let oi = watch.contractType == .perpetual
            ? openInterest(watch, futures, now: TimeUtils.nowMillis, store: false) : nil
        let fng = await fearGreedJob
        let candles = await candlesJob
        let reference = await referenceJob
        if Task.isCancelled { return nil }

        return ActivityAnalyzer.explain(WhyInput(
            baseAsset: watch.baseAsset,
            candles: candles,
            referenceCandles: reference,
            fundingPercent: futures?.fundingRatePercent,
            openInterestChangePercent: oi?.changePercent,
            fearGreed: fng?.value,
            fearGreedYesterday: fng?.yesterday,
            now: TimeUtils.nowMillis,
            // Dieselbe 24-h-Veränderung wie Pille und Merkliste
            tickerChange24h: watch.change24h,
            marketLive: !watch.isNotTraded
        ))
    }

    /// Kontrakt statt Spot: Kerzen kommen dann zuerst vom Futures-Markt.
    private static func isFutures(_ watch: Watch) -> Bool { watch.contractType != .none }

    /// Perpetual: dessen Kennzahlen; sonst das USDT-Perpetual desselben Coins als Richtwert.
    private static func whyFutures(_ watch: Watch) async -> FuturesInfo? {
        if watch.contractType == .perpetual {
            return try? await FuturesDataSource.fetch(watch: watch)
        }
        return try? await FuturesDataSource.fetchForBase(baseAsset: watch.baseAsset)
    }

    private static func fearGreed() async -> FearGreed? {
        let now = TimeUtils.nowMillis
        if let cached = cachedFearGreed(now: now) { return cached }
        guard let value = try? await InsightsDataSource.fearGreed() else { return nil }
        storeFearGreed(value)
        return value
    }

    private static func storeFearGreed(_ value: FearGreed) {
        lock.lock(); defer { lock.unlock() }
        fearGreedCache = (time: TimeUtils.nowMillis, value: value)
    }

    private static func cachedFearGreed(now: Int64) -> FearGreed? {
        lock.lock(); defer { lock.unlock() }
        guard let entry = fearGreedCache else { return nil }
        let age = now - entry.time
        return age >= 0 && age < fngCacheMillis ? entry.value : nil
    }

    // MARK: Zeitgrenze

    /// Ergebnis von `body` oder nil nach `sourceTimeoutMillis` (dann wird `body` abgebrochen).
    private static func timed<T: Sendable>(_ body: @escaping @Sendable () async -> T?) async -> T? {
        await withTaskGroup(of: Optional<T>.self) { group in
            group.addTask { await body() }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(sourceTimeoutMillis) * 1_000_000)
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }
}
