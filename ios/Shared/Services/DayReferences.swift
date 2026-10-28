import Foundation
import os

/// 24-h- und Tages-Bezüge aus Kerzen für die %-Veränderung: welche Paare Kerzen brauchen, Laden
/// anstossen und kurz abwarten, Veränderung gemäss %-Basis. Wie `DayReferences.kt`; Teil von `PriceRefresher`.
extension PriceRefresher {
    // MARK: 24-h-Veränderung

    /// So lange wartet ein Durchlauf nach den Kursen noch auf fehlende 24-h-Bezüge.
    static let dayReferenceWaitNanos: UInt64 = 5_000_000_000
    /// So lange werden späte Tages-Bezüge im Hintergrund noch nachgetragen.
    static let lateFillWaitNanos: UInt64 = 90_000_000_000

    /// Schlüssel-Teil «base» der Stundenkerzen der EIGENEN Börse in `dayReferenceKeys` (Teil «quote»
    /// = `CandlePair.cacheKey`) und in den Bezügen (`ownReferenceKey`).
    static let ownKeyBase = "#own"

    /// Schlüssel des Bezugs aus den Kerzen der eigenen Börse von `watch` in `references`.
    static func ownReferenceKey(_ watch: Watch) -> String {
        "\(ownKeyBase)|\(CandlePair(watch: watch, tradFi: false).cacheKey)"
    }

    /// Paare zu den Schlüsseln der eigenen Kerzen (`CandlePair.cacheKey` → Paar).
    static let ownPairs = OwnPairRegistry()

    /// Benötigte Kerzenreihen: zuerst die Stundenkerzen der eigenen Börse (wenn sie welche hat), dazu
    /// je Basis-Asset die Reihe in der Quote des Paars (USD-artige teilen sich die USDT-Reihe des
    /// Mini-Charts), bei Fiat-Quotes zusätzlich die USDT-Reihe — nicht für TradFi und DEX.
    static func dayReferenceKeys(_ watches: [Watch]) -> [(base: String, quote: String)] {
        var seen = Set<String>()
        var keys: [(base: String, quote: String)] = []
        let tradFiPairs = CoinLogoStore.tradFiPairs
        for watch in watches {
            let pair = CandlePair(watch: watch, tradFi: watch.groupName == AutoGroup.tradFi
                                  || tradFiPairs.contains(watch.logoPairKey))
            if OwnHourlyStore.supports(pair) {
                ownPairs.set(pair)
                let (inserted, _) = seen.insert("\(ownKeyBase)|\(pair.cacheKey)")
                if inserted { keys.append((base: ownKeyBase, quote: pair.cacheKey)) }
            }
            // TradFi und DEX: nie die Reihe eines gleichnamigen Krypto-Tokens
            guard OwnCandlePolicy.allowsFallback(pair) else { continue }
            let base = watch.baseAsset.trimmingCharacters(in: .whitespaces).uppercased()
            let quote = DayChange.candleQuote(watch.quoteAsset)
            var quotes = [quote]
            if quote != DayChange.usdtQuote && DayChange.isFiat(watch.quoteAsset) { quotes.append(DayChange.usdtQuote) }
            for q in quotes {
                let (inserted, _) = seen.insert("\(base)|\(q)")
                if inserted { keys.append((base: base, quote: q)) }
            }
        }
        return keys
    }

    /// Paare, die beim letzten vollen Durchlauf Kerzen brauchten (Ticker ohne 24-h-Wert).
    static let candleNeeds = CandleNeeds()

    /// Paare mit Kurs, die Kerzen brauchen (`ChangeBasisMath.needsCandles`): rollend nur ohne
    /// brauchbaren 24-h-Wert im Ticker, Tages-Basen alle.
    /// `remember`: Menge für den nächsten Durchlauf merken (nur bei vollen Durchläufen).
    static func watchesNeedingCandles(_ watches: [Watch], fetched: [Int64: Fetched], basis: ChangeBasis,
                                              remember: Bool) -> [Watch] {
        let needing = watches.filter { watch in
            guard let ticker = fetched[watch.id]?.ticker else { return false }
            return ChangeBasisMath.needsCandles(basis, tickerChange: ticker.change24hPercent)
        }
        if remember { candleNeeds.ids = Set(needing.map(\.id)) }
        return needing
    }

    /// Lädt die Bezüge in einer eigenen Aufgabe: Was nach dem Warten noch fehlt, wird trotzdem
    /// fertig geladen und liegt beim nächsten Durchlauf bereit. `dayStart`: Tages-Basis —
    /// Bezug seit diesem Tagesbeginn statt rollend.
    static func startDayReferenceLoads(_ keys: [(base: String, quote: String)], dayStart: Int64?) -> Task<Void, Never> {
        Task {
            await withTaskGroup(of: Void.self) { group in
                for key in keys {
                    // Stundenkerzen der eigenen Börse
                    if key.base == ownKeyBase {
                        guard let pair = ownPairs.get(key.quote) else { continue }
                        group.addTask {
                            if let dayStart {
                                _ = await OwnHourlyStore.shared.dayStartReference(pair, dayStart: dayStart)
                            } else {
                                _ = await OwnHourlyStore.shared.dayReference(pair)
                            }
                        }
                        continue
                    }
                    // Devisenkurs der Fiat-Quote (3 h Zwischenspeicher) für die Kursabstand-Prüfung
                    // der USDT-Reihe — wie `startFxLoads` (Android)
                    if key.quote != DayChange.usdtQuote && DayChange.isFiat(key.quote) {
                        group.addTask { _ = await FxRateSource.usdTo(key.quote) }
                    }
                    group.addTask {
                        if let dayStart {
                            _ = await DayReferenceStore.shared.dayStartReference(base: key.base, quote: key.quote, dayStart: dayStart)
                        } else {
                            _ = await DayReferenceStore.shared.dayReference(base: key.base, quote: key.quote)
                        }
                    }
                }
            }
        }
    }

    /// Wartet auf `task`, aber höchstens `nanos`; die Aufgabe selbst läuft weiter.
    static func waitAtMost(nanos: UInt64, for task: Task<Void, Never>) async {
        let gate = ResumeOnce()
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            gate.set(continuation)
            Task {
                await task.value
                gate.resume()
            }
            Task {
                try? await Task.sleep(nanoseconds: nanos)
                gate.resume()
            }
        }
    }

    /// Gemerkte Bezüge («BASE|QUOTE»), auch etwas ältere; fehlende bleiben weg.
    /// `dayStart`: seit diesem Tagesbeginn statt rollend.
    static func cachedDayReferences(_ watches: [Watch], dayStart: Int64?) async -> [String: DayReference] {
        var result: [String: DayReference] = [:]
        for key in dayReferenceKeys(watches) {
            let ref: DayReference?
            if key.base == ownKeyBase {
                guard let pair = ownPairs.get(key.quote) else { continue }
                if let dayStart {
                    ref = await OwnHourlyStore.shared.cachedDayStartReference(pair, dayStart: dayStart)
                } else {
                    ref = await OwnHourlyStore.shared.cachedDayReference(pair)
                }
            } else {
                if let dayStart {
                    ref = await DayReferenceStore.shared.cachedDayStartReference(base: key.base, quote: key.quote, dayStart: dayStart)
                } else {
                    ref = await DayReferenceStore.shared.cachedReference(base: key.base, quote: key.quote)
                }
                // Anbieter merken: Prüfung auf gleichen Token je Paar (`fallbackReference`)
                let provider: String? = await DayReferenceStore.shared.cachedProvider(base: key.base, quote: key.quote)
                referenceProviders.set("\(key.base)|\(key.quote)", provider: provider)
            }
            if let ref { result["\(key.base)|\(key.quote)"] = ref }
        }
        return result
    }

    /// Anbieter der gemerkten Ausweich-Bezüge («BASE|QUOTE»), gefüllt von `cachedDayReferences`.
    static let referenceProviders = ReferenceProviders()

    /// Bezug der Ausweich-Reihe `key` beim Paar `watch` — Reihe einer anderen Börse nur bei belegt gleichem Namen
    /// (`TokenIdentityStore.fallbackChangeAllowed`; Kürzel + Kursband reichen für die %-Veränderung nicht), sonst
    /// nil: lieber «—» als ein fremder Wert. Wie Android `DayReferences.reference`.
    static func fallbackReference(_ watch: Watch, key: String, references: [String: DayReference]) -> DayReference? {
        guard let ref = references[key] else { return nil }
        let provider: String? = referenceProviders.provider(key)
        let allowed: Bool = TokenIdentityStore.shared.fallbackChangeAllowed(marketKey: watch.marketKey,
                                                                            marketName: watch.marketName,
                                                                            base: watch.baseAsset, provider: provider)
        return allowed ? ref : nil
    }

    /// Veränderung zum neuen Kurs gemäss %-Basis (`ChangeBasisMath.choose`). Rollend: zuerst der
    /// 24-h-Wert aus dem Ticker (gilt für das Paar selbst, also schon in seiner Quote — auch bei
    /// Fiat-Quotes), sonst aus Kerzen. Tages-Basen: nur aus Kerzen seit Tagesbeginn (`references`
    /// sind dann solche). Kerzen mit `DayChange.select` (Kursabstand-Prüfung); nil ohne Bezug —
    /// nie die Veränderung seit der letzten Abfrage.
    static func change24h(_ watch: Watch, price: Double, ticker: Ticker, basis: ChangeBasis,
                                  references: [String: DayReference]) -> Double? {
        let value = ChangeBasisMath.choose(basis, tickerChange: ticker.change24hPercent) {
            candleChange(watch, price: price, references: references)
        }
        // Merken, ob (und aus welcher Reihe) die Änderung aus Kerzen kam (Hinweis «Veränderung aus
        // …-Kerzen» im Aktionsblatt)
        let fromCandles = value != nil && ChangeBasisMath.needsCandles(basis, tickerChange: ticker.change24hPercent)
        // Aus den Kerzen der eigenen Börse: kein Hinweis auf eine fremde Quelle
        let own = ownChange(watch, price: price, references: references) != nil
        candlePills.set(watch.id, series: fromCandles && !own ? candleSeries(watch, references: references) : nil)
        return value
    }

    /// Watch-Ids, deren Änderung zuletzt aus Kerzen kam — nur im Speicher (nach dem Start bis zur
    /// ersten Aktualisierung leer), wie `DayReferences.pillSources` (Android).
    static let candlePills = CandlePills()

    /// Kam die Änderung von `watch` aus den Kerzen einer anderen Börse (z. B. Kraken-Paar,
    /// Binance-Kerzen) oder aus der USDT-Reihe eines Fiat-Paars («Binance USDT»)? Dann deren Name
    /// für den Hinweis in Aktionsblatt und «Was bewegt sich mit?»; sonst nil. Reihe wie `DayChange.select`.
    static func foreignCandleSource(_ watch: Watch) async -> String? {
        guard let series = candlePills.series(watch.id) else { return nil }
        let base = watch.baseAsset.trimmingCharacters(in: .whitespaces).uppercased()
        let quote = series == .usdt ? DayChange.usdtQuote : DayChange.candleQuote(watch.quoteAsset)
        let provider = await DayReferenceStore.shared.cachedProvider(base: base, quote: quote)
        let name = DayChange.candleSourceName(provider: provider, series: series)
        return DayChange.foreignCandleSource(markets: [watch.marketKey, watch.marketName], provider: name)
    }

    /// Reihe, die `candleChange` nimmt (eigene, sonst bei Fiat-Quotes die USDT-Reihe).
    static func candleSeries(_ watch: Watch, references: [String: DayReference]) -> DayChange.CandleSeries {
        let base = watch.baseAsset.trimmingCharacters(in: .whitespaces).uppercased()
        let quote = DayChange.candleQuote(watch.quoteAsset)
        return DayChange.series(pairReference: fallbackReference(watch, key: "\(base)|\(quote)", references: references),
                                usdtReference: fallbackReference(watch, key: "\(base)|\(DayChange.usdtQuote)", references: references),
                                quoteIsFiat: DayChange.isFiat(watch.quoteAsset))
    }

    /// Veränderung aus dem Bezug der Kerzen der eigenen Börse (`DayChange.fromPrice`, Kursabstand-Prüfung).
    static func ownChange(_ watch: Watch, price: Double, references: [String: DayReference]) -> Double? {
        guard let reference = references[ownReferenceKey(watch)] else { return nil }
        return DayChange.fromPrice(price, reference)
    }

    /// Veränderung aus den Bezügen der Kerzen: zuerst die eigene Börse, sonst das Paar, bei Fiat-Quotes
    /// ersatzweise die USDT-Reihe, geprüft über den gemerkten Devisenkurs (nicht für TradFi und DEX).
    static func candleChange(_ watch: Watch, price: Double, references: [String: DayReference]) -> Double? {
        if let own = ownChange(watch, price: price, references: references) { return own }
        guard OwnCandlePolicy.allowsFallback(CandlePair.of(watch)) else { return nil }
        let base = watch.baseAsset.trimmingCharacters(in: .whitespaces).uppercased()
        let quote = DayChange.candleQuote(watch.quoteAsset)
        let fiat = DayChange.isFiat(watch.quoteAsset)
        return DayChange.select(price: price,
                                pairReference: fallbackReference(watch, key: "\(base)|\(quote)", references: references),
                                usdtReference: fallbackReference(watch, key: "\(base)|\(DayChange.usdtQuote)", references: references),
                                quoteIsFiat: fiat,
                                usdToQuote: fiat ? FxRateSource.cached(watch.quoteAsset) : nil,
                                // Kein Devisenkurs von vor 24 h verfügbar → bei Fiat-Paaren ohne
                                // eigene Reihe kein Wert (nie der USDT-Prozentwert als Fiat-Wert)
                                usdToQuoteAtOpen: nil)
    }
}

/// Paare der Merkliste je `CandlePair.cacheKey` für die Abrufe eigener Kerzen (threadsicher).
final class OwnPairRegistry: Sendable {
    private let pairs = OSAllocatedUnfairLock(initialState: [String: CandlePair]())

    func set(_ pair: CandlePair) {
        pairs.withLock { $0[pair.cacheKey] = pair }
    }

    func get(_ key: String) -> CandlePair? {
        pairs.withLock { $0[key] }
    }
}

/// Gemerkte Watch-Ids, deren Ticker beim letzten vollen Durchlauf keinen 24-h-Wert hatte
/// (threadsicher; App und Hintergrund können gleichzeitig aktualisieren).
final class CandleNeeds: Sendable {
    private let stored = OSAllocatedUnfairLock(initialState: Set<Int64>())

    var ids: Set<Int64> {
        get { stored.withLock { $0 } }
        set { stored.withLock { $0 = newValue } }
    }
}

/// Watch-Ids mit einer Änderung aus Kerzen und der genommenen Reihe (threadsicher).
final class CandlePills: Sendable {
    private let entries = OSAllocatedUnfairLock(initialState: [Int64: DayChange.CandleSeries]())

    /// Merkt die Reihe (oder vergisst mit nil — Änderung aus dem Ticker bzw. ohne Wert).
    func set(_ id: Int64, series: DayChange.CandleSeries?) {
        entries.withLock { $0[id] = series }
    }

    func contains(_ id: Int64) -> Bool { series(id) != nil }

    func series(_ id: Int64) -> DayChange.CandleSeries? {
        entries.withLock { $0[id] }
    }
}

/// Anbieter der gemerkten Ausweich-Bezüge je «BASE|QUOTE» (threadsicher) — wie der Anbieter in `SparklineRepository.kt`.
final class ReferenceProviders: Sendable {
    private let entries = OSAllocatedUnfairLock(initialState: [String: String]())

    /// Merkt den Anbieter (oder vergisst ihn mit nil — unbekannt).
    func set(_ key: String, provider: String?) {
        entries.withLock { $0[key] = provider }
    }

    func provider(_ key: String) -> String? {
        entries.withLock { $0[key] }
    }
}

/// Setzt eine Fortsetzung genau einmal fort (wer zuerst kommt: Ende der Aufgabe oder Zeitgrenze).
final class ResumeOnce: Sendable {
    private struct State: Sendable {
        var continuation: CheckedContinuation<Void, Never>?
        var done = false
    }

    private let state = OSAllocatedUnfairLock(initialState: State())

    func set(_ continuation: CheckedContinuation<Void, Never>) {
        let resumeNow = state.withLock { s -> Bool in
            if s.done { return true }
            s.continuation = continuation
            return false
        }
        if resumeNow { continuation.resume() }
    }

    func resume() {
        let pending = state.withLock { s -> CheckedContinuation<Void, Never>? in
            guard !s.done else { return nil }
            s.done = true
            defer { s.continuation = nil }
            return s.continuation
        }
        pending?.resume()
    }
}
