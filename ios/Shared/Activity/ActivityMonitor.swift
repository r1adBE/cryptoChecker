import Foundation
import os

/// Holt die Marktdaten für «Ungewöhnliche Aktivität» und «Warum bewegt sich das?»
/// und gibt sie an den reinen `ActivityAnalyzer` — wie `ActivityMonitor.kt`.
/// Fehler bleiben still: Ohne Daten gibt es eben keine Signale.
///
/// Nur Systemmodule (Foundation, os), kein App-Code: auch aus dem Widget-Prozess nutzbar.
enum ActivityMonitor {
    static let maxParallel = 4
    static let budgetMillis: Int64 = 20_000
    static let sourceTimeoutMillis: Int64 = 8_000
    private static let fngCacheMillis: Int64 = 10 * 60_000

    /// Prozessweiter Stand, gemeinsam hinter einer Sperre.
    private struct State: Sendable {
        /// Nur eine Auswertung zur Zeit; läuft schon eine, wird die neue übersprungen.
        var running = false
        var fearGreedCache: (time: Int64, value: FearGreed)?
        /// Letzter Versuch je Paar in diesem Prozess (auch ohne Ergebnis) — für `ActivityDueOrder`.
        var attempts: [Int64: Int64] = [:]
    }

    private static let state = OSAllocatedUnfairLock(initialState: State())

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

    private static func markAttempt(_ id: Int64, _ now: Int64) {
        state.withLock { $0.attempts[id] = now }
    }

    /// Versuche der Paare `ids` (übrige vergessen).
    private static func attemptsSnapshot(retaining ids: Set<Int64>) -> [Int64: Int64] {
        state.withLock { s in
            s.attempts = s.attempts.filter { ids.contains($0.key) }
            return s.attempts
        }
    }

    private static func tryStart() -> Bool {
        state.withLock { s in
            if s.running { return false }
            s.running = true
            return true
        }
    }

    private static func finish() {
        state.withLock { $0.running = false }
    }

    /// Ergebnisse der parallelen Prüfungen, threadsicher gesammelt.
    private final class Collector: Sendable {
        private let items = OSAllocatedUnfairLock(
            initialState: [Int64: (watch: Watch, merge: ActivityAnalyzer.Merge)]())

        func put(_ watch: Watch, _ merge: ActivityAnalyzer.Merge) {
            items.withLock { $0[watch.id] = (watch: watch, merge: merge) }
        }

        var all: [Int64: (watch: Watch, merge: ActivityAnalyzer.Merge)] {
            items.withLock { $0 }
        }
    }

    private static func analyzeLocked(watches all: [Watch], settings: AppSettings) async {
        // Nicht mehr gehandelte Paare: keine Auswertung, gespeicherte Signale (und ihre
        // Mitteilung) weg — sonst stünde ⚡ samt «Ungewöhnliche Bewegung» auf alten Kerzen da.
        // Deaktivierte Paare ebenso: keine Kerzen-Abfragen, keine ⚡-Signale, kein «Warum?» (wie Android)
        let notTraded = NotTraded.ids(all).union(all.filter(\.disabled).map(\.id))
        let stored = ActivityRepository.reports()
        for id in notTraded where stored[id] != nil { Notifier.cancelActivity(id) }
        let watches = all.filter { !notTraded.contains($0.id) }
        ActivityRepository.retain(Set(watches.map(\.id)))
        guard !watches.isEmpty else { return }

        let now = EpochTime.nowMillis
        let previous = ActivityRepository.reports()
        // Am längsten nicht geprüfte zuerst: sonst schnitte das Zeitbudget bei grossen Listen immer dieselben ab
        let due = ActivityDueOrder.order(watches.filter { ActivityAnalyzer.isDue(previous[$0.id], now: now) },
                                         id: \.id, computedAt: { previous[$0.id]?.computedAt },
                                         attemptedAt: attemptsSnapshot(retaining: Set(watches.map(\.id))))
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
                    markAttempt(watch.id, now)
                    group.addTask {
                        await analyzeOne(watch, previous: previous[watch.id], now: now,
                                         sensitivity: sensitivity, into: collector)
                    }
                }
                while await group.next() != nil {
                    guard index < due.count, !Task.isCancelled else { continue }
                    let watch = due[index]
                    index += 1
                    markAttempt(watch.id, now)
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

        // Meldungen: höchstens eine je Paar und Stunde, nur bei neuen Signalen. Kommt die Mitteilung
        // nicht an (keine Erlaubnis, alles Sichtbare aus), gilt nichts als gemeldet: Die Arten bleiben
        // offen und melden sich beim nächsten Lauf (Nachtruhe: lautlos) — wie Android
        var deliverable: Bool?
        for (watch, merge) in results.values {
            let pending = ActivityRepository.pendingKinds(watch.id)
            let kinds = ActivityAnalyzer.noticeKinds(newKinds: merge.newKinds, pending: pending, report: merge.report, now: now)
            let notify = ActivityAnalyzer.shouldNotify(
                enabled: settings.activityAlerts,
                newKinds: kinds,
                lastNotifiedAt: ActivityRepository.lastNotifiedAt(watch.id),
                now: now
            )
            let top = notify ? merge.report.signals.first(where: { kinds.contains($0.kind) }) : nil
            var posted = false
            if let top {
                if deliverable == nil { deliverable = await Notifier.isAuthorized() }
                if deliverable == true { posted = await Notifier.showActivity(watch, top) }
            }
            if posted { ActivityRepository.setNotifiedAt(watch.id, now) }
            let after = ActivityAnalyzer.pendingAfter(kinds: kinds, notify: top != nil, posted: posted)
            if after != pending { ActivityRepository.setPendingKinds(watch.id, after) }
        }
    }

    private static func analyzeOne(_ watch: Watch, previous: ActivityReport?, now: Int64,
                                   sensitivity: ActivitySensitivity, into collector: Collector) async {
        // Wie der Chart: eigene Börse, sonst Ausweich-Kette nur bei gleichem Token (nie für Laufzeit-Kontrakte)
        let pair = CandlePair.of(watch)
        let price = watch.lastPrice
        async let candlesJob = timed {
            await VolumeDataSource.pairCandles(pair, price: price, interval: .h1, limit: VolumeDataSource.limit)?.value
        }
        async let futuresJob = perpetualFutures(watch)
        let candles = await candlesJob
        let futures = await futuresJob
        // Abbruch des ganzen Durchlaufs (Zeitbudget): Ergebnis verwerfen
        guard !Task.isCancelled else { return }

        // Veraltete oder unplausible Reihen (z. B. Spot nach Delisting) liefern keine Signale
        let valid = CandleSeries.usable(candles, now: now, tickerChange24h: rolling24h(watch))
        let stats = valid.flatMap { ActivityAnalyzer.hourStats($0, now: now) }
        let oi = openInterest(watch, futures, now: now, store: true)
        // ⚡ nur bei einheitlicher Richtung gegenüber den älteren Messungen (wie Android)
        let oiChange: Double? = oi.flatMap { $0.consistent ? $0.changePercent : nil }
        let fresh = ActivityAnalyzer.signals(
            stats: stats,
            fundingPercent: futures?.fundingRatePercent,
            oiChangePercent: oiChange,
            oiMinutes: oi?.minutes,
            now: now,
            sensitivity: sensitivity,
            // Stündliches Funding (Hyperliquid) zählt für die Schwellen achtfach
            fundingSource: futures?.source,
            fundingIntervalHours: futures?.fundingIntervalHours
        )
        collector.put(watch, ActivityAnalyzer.merge(previous: previous, fresh: fresh, now: now, sensitivity: sensitivity))
    }

    private static func perpetualFutures(_ watch: Watch) async -> FuturesInfo? {
        guard watch.contractType.isPerpetual else { return nil }
        return await timed { try? await FuturesDataSource.fetch(watch: watch) }
    }

    /// Open Interest in Coins (USD-Wert / letzter Kurs), damit eine reine Kursbewegung nicht als
    /// OI-Sprung zählt. Vergleich gegen den gespeicherten Verlauf (`OiSeries`: Median der älteren
    /// Messungen derselben Quelle); `store` = Messung anhängen (höchstens alle 30 Min.). Wie Android.
    private static func openInterest(_ watch: Watch, _ futures: FuturesInfo?, now: Int64, store: Bool) -> OiSeries.Result? {
        guard let futures, let usd = futures.openInterestUsd, let price = watch.lastPrice, price > 0 else { return nil }
        let units = usd / price
        let series = ActivityRepository.oiSeries(watch.id)
        let result = OiSeries.change(series, currentUnits: units, source: futures.source, now: now)
        if store {
            let updated = OiSeries.append(series, OiSample(units: units, time: now, source: futures.source), now: now)
            if updated != series { ActivityRepository.setOiSeries(watch.id, updated) }
        }
        return result
    }

    // MARK: «Warum bewegt sich das?»

    /// Lädt alles für das «Warum»-Blatt gleichzeitig, jede Quelle mit eigener Zeitgrenze:
    /// Stundenkerzen des Paars, von BTC (bei Bitcoin ETH) und ETH (nicht für BTC/ETH selbst),
    /// den breiten Markt (CoinGecko-Top-40), Futures-Kennzahlen samt Funding-Verlauf,
    /// Fear & Greed und Tageskerzen (30-Tage-Hoch/-Tief). nil nur, wenn der Aufrufer abgebrochen hat.
    static func explain(_ watch: Watch) async -> WhyReport? {
        // Nicht mehr gehandelt bzw. deaktiviert: nichts laden, nichts einordnen (die Merkliste bietet «Warum?» dann nicht an)
        if watch.isNotTraded || watch.disabled { return ActivityAnalyzer.notLive(now: EpochTime.nowMillis) }
        let base = watch.baseAsset.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let isBtc = base == "BTC"
        let isEth = base == "ETH"
        // Stablecoins: kein Markt-Vergleich (Abweichung von 1:1), also auch keine Vergleichsdaten dafür
        let stable = StablePeg.isPegged(base: watch.baseAsset, quote: watch.quoteAsset)
        // Quote nicht USD (z. B. ETH/BTC, SOL/EUR): kein Markt-Vergleich, also auch keine Vergleichsdaten
        let usdQuote = ActivityAnalyzer.isUsdQuote(watch.quoteAsset)
        let compare = !isBtc && !stable && usdQuote
        let count = ActivityAnalyzer.whyHourlyLimit
        // Kerzen der eigenen Börse des Paars; sonst Ausweich-Kette (Futures-Paar: USDⓈ-M zuerst) nur bei
        // gleichem Token wie der Chart (`VolumeDataSource.pairCandles`; nicht für TradFi, DEX, Laufzeit-Kontrakte)
        let pair = CandlePair.of(watch)
        let price = watch.lastPrice
        async let candlesJob = timed {
            await VolumeDataSource.pairCandles(pair, price: price, interval: .h1, limit: count)
        }
        let referenceBase = isBtc ? "ETH" : "BTC"
        async let referenceJob = timed { () async -> Sourced<[MarketCandle]>? in
            if stable || !usdQuote { return nil }
            return await VolumeDataSource.hourlyCandlesSourced(base: referenceBase, quote: "USDT", count: count)
        }
        // Zweite Markt-Referenz ETH (für BTC ist ETH schon die Referenz, für ETH ist BTC sie)
        async let ethJob = timed { () async -> Sourced<[MarketCandle]>? in
            if !compare || isEth { return nil }
            return await VolumeDataSource.hourlyCandlesSourced(base: "ETH", quote: "USDT", count: count)
        }
        // Breiter Markt aus der CoinGecko-Top-40 (dieselbe Abfrage wie der Spot-Tab)
        async let broadJob = timed { () async -> BroadMarketSnapshot? in
            if !compare { return nil }
            return await SpotDataSource.shared.broadMarket(excludeBase: base)
        }
        async let futuresJob = timed { await whyFutures(watch) }
        async let historyJob = timed { await FuturesDataSource.fundingHistory(watch: watch) }
        async let fearGreedJob = timed { await fearGreed() }
        // Tageskerzen für «Nähe zum Hoch/Tief» (30 Tage); fehlen sie, entfallen nur diese Zeilen
        async let dailyJob = timed {
            await VolumeDataSource.pairCandles(pair, price: price, interval: .d1, limit: ActivityAnalyzer.highDays + 1)?.value
        }

        let futures = await futuresJob
        let oi = watch.contractType.isPerpetual
            ? openInterest(watch, futures, now: EpochTime.nowMillis, store: true) : nil
        let fng = await fearGreedJob
        let candles = await candlesJob
        let reference = await referenceJob
        let eth = await ethJob
        let broad = await broadJob
        let history = await historyJob
        let daily = await dailyJob
        if Task.isCancelled { return nil }

        let sources = WhySources(
            candles: candles?.provider,
            // Eigene Börse ist nie Ersatzquelle; Binance-Kerzen eines Phemex-Paars schon
            candlesFallback: WhyFreshness.candlesFallback(exchanges: [watch.exchangeKey, watch.exchangeName],
                                                          provider: candles?.provider),
            reference: reference?.provider.flatMap { $0 != candles?.provider ? $0 : nil },
            futures: futures?.source,
            futuresNote: futures.flatMap { futuresNote(watch, source: $0.source) },
            fearGreedTime: fng?.time,
            broadTime: broad?.time
        )
        var input = WhyInput(
            baseAsset: watch.baseAsset,
            candles: candles?.value,
            referenceCandles: reference?.value,
            fundingPercent: futures?.fundingRatePercent,
            openInterestChangePercent: oi?.changePercent,
            fearGreed: fng?.value,
            fearGreedYesterday: fng?.yesterday,
            now: EpochTime.nowMillis,
            // Dieselbe 24-h-Veränderung wie Änderung und Merkliste (bei «seit 00:00» aus den Kerzen)
            tickerChange24h: rolling24h(watch),
            marketLive: !watch.isNotTraded,
            dailyCandles: daily
        )
        input.quoteAsset = watch.quoteAsset
        input.ethCandles = eth?.value
        input.broadMarket = broad
        input.openInterestMinutes = oi?.minutes
        input.openInterestConsistent = oi?.consistent ?? true
        input.fundingHistory = history
        input.fundingSource = futures?.source
        input.fundingIntervalHours = futures?.fundingIntervalHours
        input.sources = sources
        return ActivityAnalyzer.explain(input)
    }

    /// Wie die Futures-Quelle zum Paar steht (`FuturesSources.note`): Daten der beobachteten Börse,
    /// Ausweichquelle oder Richtwert einer anderen Börse (Spot-Paare, Börsen ohne eigene Daten) — wie Android.
    private static func futuresNote(_ watch: Watch, source: String) -> FuturesSourceNote? {
        FuturesSources.note(watch, source: source)
    }

    /// Gespeicherte Veränderung des Paars, wenn sie rollend über 24 Stunden gilt; bei einer Tages-Basis
    /// der %-Änderung («seit 00:00») nil — «Warum?» rechnet dann aus den Kerzen (wie Android).
    private static func rolling24h(_ watch: Watch) -> Double? {
        SharedStorage.loadSettings().changeBasis.isDay ? nil : watch.change24h
    }

    /// Perpetual: dessen Kennzahlen; sonst das USDT-Perpetual desselben Coins als Richtwert.
    private static func whyFutures(_ watch: Watch) async -> FuturesInfo? {
        if watch.contractType.isPerpetual {
            return try? await FuturesDataSource.fetch(watch: watch)
        }
        return try? await FuturesDataSource.fetchForBase(baseAsset: watch.baseAsset)
    }

    private static func fearGreed() async -> FearGreed? {
        let now = EpochTime.nowMillis
        if let cached = cachedFearGreed(now: now) { return cached }
        guard let value = try? await InsightsDataSource.fearGreed() else { return nil }
        storeFearGreed(value)
        return value
    }

    private static func storeFearGreed(_ value: FearGreed) {
        let entry = (time: EpochTime.nowMillis, value: value)
        state.withLock { $0.fearGreedCache = entry }
    }

    private static func cachedFearGreed(now: Int64) -> FearGreed? {
        guard let entry = state.withLock({ $0.fearGreedCache }) else { return nil }
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
