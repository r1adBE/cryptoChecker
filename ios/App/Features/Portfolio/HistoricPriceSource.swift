import Foundation
import os

// Stichtag-Export: historische Kurse und Devisenkurse — wie `HistoricPriceSource.kt` (Android).

/// Devisenkurs eines Stichtags mit dem tatsächlichen Kursdatum (Wochenende → Freitag).
struct HistoricFx: Sendable {
    let rate: Double
    let date: String?
}

/// Tagesschlusskurse (UTC-Tageskerze) in USDT und Devisenkurs eines Tags.
/// Kerzen-Kette wie `CandleDataSource`: data-api.binance.vision → api.binance.com →
/// fapi.binance.com → api.binance.us (USDT, sonst USD) → Coinbase (USD, sonst USDC).
/// Es zählt nur die Kerze, die genau an diesem Tag beginnt. Kurse ohne Zwischenspeicher.
/// Identitätsprüfung (`PricePlausibility.acceptSourceClose`): jede Quelle (auch Binance-Spot) zählt
/// nur, wenn sie den Coin heute als denselben Token führt wie das Portfolio (ihr jüngster Schluss
/// gegen den aktuellen Kurs, 1/3…3; Ausweich-Quellen ±25 %; eine Abfrage mehr, nur bei einem Treffer
/// dort); sonst nächste Quelle. Ohne gültigen aktuellen Kurs keine Abfrage, der Coin ist «ungeprüft»
/// (`CutoffCloses.unchecked`), ebenso wenn nur nicht passende Kurse gefunden wurden.
/// Ausweich-Kette (`HistoricCloseChain`, HistoricPriceSource+Chain.swift): eigene Börse des Coins zuerst (Tageskerzen
/// dieser Börse, 1/3…3 wie Binance-Spot), nach Binance und Coinbase noch Kraken → OKX → Bybit (eigene Kerzen, ±25 % und
/// Name) — nur Hosts, die die App ohnehin anfragt. Der Kurs trägt seine Quelle (`CloseResult.source`).
/// Stablecoins nach `PortfolioStables`: USDT = 1 ohne Abfrage, andere wie jeder Coin.
/// Dazu die Devisen-Tageskurse eines Zeitraums für den Wertverlauf (`usdToSeries`, Referenzkurse der EZB,
/// 6 h im Speicher). Kerzen der Binance-Gruppe und von Coinbase über `HistoricCloseFetch`: je Tag eine Abfrage
/// (`HistoricDayFetch`) bzw. für viele Tage je Coin und Quelle in Stücken (`HistoricSpanFetch`, `dayClosesUsdt`,
/// HistoricPriceSource+Span.swift) — gleiche Kette, gleiche Kerze.
enum HistoricPriceSource {
    static let parallel = 4

    /// Eigene Sitzung mit ~10 s Zeitgrenze je Anfrage.
    private static let session: URLSession = {
        let c = PrivateSessionConfig.make(timeout: 10, resource: 15)
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.httpAdditionalHeaders = [HttpUserAgent.header: HttpUserAgent.value, "Accept": "application/json"]
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()

    /// Ganze Geschichte der EZB (gepackt rund 1 MB): eigene Sitzung mit 20 s je Anfrage. Wie Android.
    private static let historySession: URLSession = {
        let c = PrivateSessionConfig.make(timeout: 20, resource: 30)
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.httpAdditionalHeaders = [HttpUserAgent.header: HttpUserAgent.value]
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()

    /// Schlusskurse der Coins am Tag `day`; Coins ohne bestätigten Kurs fehlen in den Preisen.
    /// `current`: Bezugskurse in USDT für die Identitätsprüfung (`CutoffExport.referencePrices`);
    /// fehlt einer (auch: Laden der aktuellen Kurse ganz gescheitert), ist der Coin «ungeprüft».
    static func dailyClosesUsdt(_ coins: [String], day: CutoffDay, current: [String: Double]) async -> CutoffCloses {
        var symbols: [String] = []
        for c in coins.map(PortfolioCalculator.normalizeCoin) where isAsset(c) && !symbols.contains(c) {
            symbols.append(c)
        }
        var result = CutoffCloses()
        // Eingetragene Börsen und Namen der Merkliste einmal für alle Coins (aus der App Group, ohne Netz)
        let own = ownRoutes(symbols)
        let names = watchNames(symbols)
        let fetch = HistoricDayFetch()
        // In Gruppen zu je `parallel` Abfragen
        var index = 0
        while index < symbols.count {
            let chunk = Array(symbols[index..<min(index + parallel, symbols.count)])
            index += parallel
            let found = await withTaskGroup(of: (String, CloseResult).self, returning: CutoffCloses.self) { group in
                for coin in chunk {
                    group.addTask {
                        let r = await closeUsdt(coin, day: day, current: current[coin], own: own[coin], fetch: fetch, names: names)
                        return (coin, r)
                    }
                }
                var partial = CutoffCloses()
                for await (coin, r) in group {
                    // Kerzenkurse kommen als Double: exakt über die kürzeste Darstellung (= Text der Quelle)
                    if let price = PortfolioDecimal.of(r.price) { partial.prices[coin] = price }
                    if r.unchecked { partial.unchecked.insert(coin) }
                }
                return partial
            }
            result.prices.merge(found.prices) { _, new in new }
            result.unchecked.formUnion(found.unchecked)
        }
        return result
    }

    /// Ergebnis je Coin: bestätigter Kurs, sonst `unchecked` = Kurs nicht als derselbe Token bestätigt. `source` =
    /// Anbieter des Kurses (z. B. «Binance», «Kraken»; nil ohne Kurs bzw. USDT = 1).
    struct CloseResult: Sendable, Equatable {
        var price: Double?
        var unchecked = false
        var source: String?
    }

    /// Schlusskurs eines Coins (UTC-Tageskerze). USDT = 1, andere Stablecoins werden wie jeder Coin
    /// abgefragt. `current` = aktueller Portfolio-Kurs (Bezug der Identitätsprüfung); ohne gültigen
    /// Bezug keine Abfrage, «ungeprüft».
    /// `fetch`: Abfrage je Tag (Tests: gespielter Markt).
    static func dailyCloseUsdt(_ coin: String, day: CutoffDay, current: Double?,
                               fetch: HistoricDayFetch = HistoricDayFetch()) async -> CloseResult {
        let b = PortfolioCalculator.normalizeCoin(coin)
        return await closeUsdt(b, day: day, current: current, own: ownRoutes([b])[b], fetch: fetch, names: nil)
    }

    /// Wie `dailyCloseUsdt`, mit schon gelesener eigener Börse `own` (nil = keine bzw. Binance), Kerzen über `fetch` und
    /// schon gelesenen Namen der Merkliste `names` (Coin → Name, fehlt = keiner; nil = hier lesen).
    static func closeUsdt(_ coin: String, day: CutoffDay, current: Double?, own: CandlePair?,
                          fetch: any HistoricCloseFetch, names: [String: String]?) async -> CloseResult {
        let b = PortfolioCalculator.normalizeCoin(coin)
        if !PortfolioStables.needsQuote(b) { return CloseResult(price: 1.0) }
        guard isAsset(b) else { return CloseResult(price: nil) }
        // Streng: ohne Bezug ist der gleiche Token nicht bestätigt — auch nicht über Ausweich-Quellen
        guard let reference = current, reference > 0, reference.isFinite else {
            return CloseResult(price: nil, unchecked: true)
        }
        let start = day.utcStartMillis
        let ownName = names.map { $0[b] } ?? watchNames([b])[b]
        let resolved = await HistoricCloseChain.resolve(HistoricCloseChain.order(own?.exchangeKey)) { source in
            if let own, source == own.exchangeKey {
                return await ownOutcome(own, base: b, day: day, start: start, reference: reference, ownName: ownName,
                                        fetch: fetch)
            }
            switch source {
            case HistoricCloseChain.binance:
                return await binanceOutcome(b, start: start, reference: reference, ownName: ownName, fetch: fetch)
            case HistoricCloseChain.coinbase:
                return await coinbaseOutcome(b, day: day, start: start, reference: reference, fetch: fetch)
            default:
                return await chainOutcome(source, base: b, start: start, reference: reference, ownName: ownName, fetch: fetch)
            }
        }
        return CloseResult(price: resolved.price, unchecked: resolved.unchecked, source: resolved.provider)
    }

    /// Coinbase (Fenster genau über den Tag, jedes Alter; USD, sonst USDC; ±25 %).
    static func coinbaseOutcome(_ b: String, day: CutoffDay, start: Int64, reference: Double,
                                fetch: any HistoricCloseFetch) async -> HistoricCloseChain.Outcome {
        let r = await coinbaseClose(b, day: day, start: start, current: reference, fetch: fetch)
        return HistoricCloseChain.Outcome(price: r.price, rejected: r.unchecked, provider: DataFreshness.coinbase)
    }

    /// Binance-Gruppe: Spot (data-api, api; 1/3…3), Futures (±25 %), Binance.US (USDT, sonst USD; ±25 %).
    private static func binanceOutcome(_ b: String, start: Int64, reference: Double, ownName: String?,
                                       fetch: any HistoricCloseFetch) async -> HistoricCloseChain.Outcome {
        // Gab es einen Kurs, der die Prüfung nicht bestand?
        var rejected = false

        // Binance-Spot: Identitätsprüfung 1/3…3; Futures und Ausweich-Quellen zusätzlich ±25 %
        let binance: [(host: String, endpoint: String, primary: Bool)] = [
            ("data-api.binance.vision", "https://data-api.binance.vision/api/v3/klines", true),
            ("api.binance.com", "https://api.binance.com/api/v3/klines", true),
            ("fapi.binance.com", "https://fapi.binance.com/fapi/v1/klines", false),
        ]
        // Gleicher Token: Nennen die Paare der Merkliste den Coin klar anders als Binance, fällt Binance aus
        let binanceOk = TokenIdentityStore.shared.binanceAllowed(base: b, ownName: ownName)
        if !binanceOk { rejected = true }
        for source in binance where binanceOk {
            let r = await binanceClose(host: source.host, endpoint: source.endpoint, symbol: b + "USDT",
                                       start: start, primary: source.primary, current: reference, fetch: fetch)
            if let price = r.price {
                return HistoricCloseChain.Outcome(price: price, provider: DataFreshness.candleProvider(host: source.host))
            }
            rejected = rejected || r.unchecked
        }
        for symbol in [b + "USDT", b + "USD"] {
            let r = await binanceClose(host: "api.binance.us", endpoint: "https://api.binance.us/api/v3/klines",
                                       symbol: symbol, start: start, primary: false, current: reference, fetch: fetch)
            if let price = r.price { return HistoricCloseChain.Outcome(price: price, provider: DataFreshness.binanceUS) }
            rejected = rejected || r.unchecked
        }
        return HistoricCloseChain.Outcome(price: nil, rejected: rejected)
    }

    private static func binanceClose(host: String, endpoint: String, symbol: String, start: Int64,
                                     primary: Bool, current: Double, fetch: any HistoricCloseFetch) async -> CloseResult {
        if BlockedSources.isBlocked(host) { return CloseResult(price: nil) }
        guard let close = await fetch.binanceDay(host: host, endpoint: endpoint, symbol: symbol, start: start) else {
            return CloseResult(price: nil)
        }
        // Führt die Quelle den Coin heute als denselben Token? (jüngste Tageskerze)
        let latest = await fetch.binanceLatest(host: host, endpoint: endpoint, symbol: symbol)
        return PricePlausibility.acceptSourceClose(close, primary: primary, sourceLatest: latest, current: current)
            ? CloseResult(price: close) : CloseResult(price: nil, unchecked: true)
    }

    private static func coinbaseClose(_ b: String, day: CutoffDay, start: Int64, current: Double,
                                      fetch: any HistoricCloseFetch) async -> CloseResult {
        var rejected = false
        for quote in ["USD", "USDC"] {
            if BlockedSources.isBlocked(coinbaseHost) { break }
            guard let close = await fetch.coinbaseDay(product: "\(b)-\(quote)", day: day, start: start) else { continue }
            // Jüngste Tageskerzen desselben Produkts
            let latest = await fetch.coinbaseLatest(product: "\(b)-\(quote)")
            if PricePlausibility.acceptSourceClose(close, primary: false, sourceLatest: latest, current: current) {
                return CloseResult(price: close)
            }
            rejected = true
        }
        return CloseResult(price: nil, unchecked: rejected)
    }

    static let coinbaseHost = "api.exchange.coinbase.com"

    static func closeOf(_ candles: [MarketCandle], start: Int64) -> Double? {
        guard let close = candles.first(where: { $0.openTime == start })?.close, close > 0, close.isFinite else { return nil }
        return close
    }

    /// So weit sucht `usdTo` vor dem Tag nach dem letzten EZB-Tag (auch über Ostern oder Weihnachten). Wie Android.
    static let fxLookbackDays = 10

    /// Devisenkurs USD → `currency` am Tag `day`; USD = 1 (ohne Datum). Euro-Referenzkurse der EZB (`ecbDays`): der
    /// jüngste EZB-Tag ≤ `day` mit USD-Kurs innerhalb von `fxLookbackDays` Tagen — an Wochenenden/Feiertagen der letzte
    /// Geschäftstag, dessen Datum in `date` steht; Kreuzkurs wie `EcbRates.cross`. Lew nach der Euro-Einführung aus dem
    /// EUR-Kurs desselben Tags (eine Abfrage). Wie Android.
    static func usdTo(_ currency: String, day: CutoffDay) async -> HistoricFx? {
        let target = currency.trimmingCharacters(in: .whitespaces).uppercased()
        if target == "USD" { return HistoricFx(rate: 1, date: nil) }
        guard FxRateSource.isCurrencyCode(target), let until = LocalDay(iso: day.iso) else { return nil }
        let days = await ecbDays(PortfolioHistoryFx.requestCurrencies(target), from: until.plusDays(-fxLookbackDays),
                                 to: until) ?? [:]
        guard let date = days.keys.filter({ $0 <= day.iso && days[$0]?["USD"] != nil }).max(),
              let rates = days[date] else { return nil }
        // Lew ab 2026: aus dem EUR-Kurs desselben Tags (fester Kurs 1.95583)
        let rate = EcbRates.cross(rates, base: "USD", quote: target)
            ?? FxRateSource.derivedBgn(currency: target, usdToEur: EcbRates.cross(rates, base: "USD", quote: "EUR"),
                                       dayIso: day.iso)
        guard let rate, rate > 0, rate.isFinite else { return nil }
        return HistoricFx(rate: rate, date: date)
    }

    // MARK: Devisen-Tageskurse für den Wertverlauf

    private static let fxSeriesTtlMillis: Int64 = 6 * 60 * 60_000
    /// «Währung|von|bis» → (Tageskurse, Abfragezeit); nur im Speicher, hinter einer Sperre mit Zustand.
    private static let fxSeriesCache = OSAllocatedUnfairLock(
        initialState: [String: (rates: [Int: Double], at: Int64)]())

    /// Tageskurse USD → `currency` von `from` bis `to` (Euro-Referenzkurse der EZB, eine Abfrage für den ganzen Zeitraum,
    /// `ecbDays`; Kreuzkurs wie `EcbRates.rebase`): Tag (epochDay) → Kurs, nur Geschäftstage — Wochenenden und Feiertage
    /// füllt `PortfolioHistoryFx.rateOn` mit dem Vortag. USD: leer (kein Bedarf). 6 h im Speicher. nil, wenn kein Kurs zu
    /// haben ist. Wie Android.
    static func usdToSeries(_ currency: String, from: LocalDay, to: LocalDay) async -> [Int: Double]? {
        await usdToSeriesChecked(currency, from: from, to: to).rates
    }

    /// Wie `usdToSeries`, sagt aber, WARUM nichts kam: `failed` = keine gültige Antwort (Netz, Dienst) — im Unterschied zu
    /// «für diese Währung bzw. diesen Zeitraum gibt es keinen Kurs» (der CSV-Import nennt beides getrennt). Wie Android.
    static func usdToSeriesChecked(_ currency: String, from: LocalDay, to: LocalDay) async
        -> (rates: [Int: Double]?, failed: Bool) {
        let target = currency.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        if target == "USD" { return ([:], false) }
        guard FxRateSource.isCurrencyCode(target), from <= to else { return (nil, false) }
        let key = "\(target)|\(from)|\(to)"
        let now = EpochTime.nowMillis
        let ttl = fxSeriesTtlMillis
        let cached: [Int: Double]? = fxSeriesCache.withLock {
            guard let entry = $0[key], now - entry.at >= 0, now - entry.at < ttl else { return nil }
            return entry.rates
        }
        if let cached { return (cached, false) }
        guard let days = await ecbDays(PortfolioHistoryFx.requestCurrencies(target), from: from, to: to) else {
            return (nil, true)
        }
        let series = PortfolioHistoryFx.ratesByDay(target, byDate: days.mapValues { EcbRates.rebase($0, base: "USD") })
        guard !series.isEmpty else { return (nil, false) }
        let entry = (rates: series, at: EpochTime.nowMillis)
        fxSeriesCache.withLock { $0[key] = entry }
        return (series, false)
    }

    /// Ganze Geschichte der EZB für die Währungen eines Schlüssels («CHF+USD») mit Ladezeit (nur im Speicher).
    private static let fullHistory = OSAllocatedUnfairLock<(key: String, days: EcbRates.Days, at: Int64)?>(
        initialState: nil)

    /// Euro-Referenzkurse (Tag → Code → Kurs je 1 EUR) der Währungen `codes` von `from` bis `to`: zuerst das ECB Data
    /// Portal (`EcbRates.seriesURL`, eine Abfrage für alle Währungen, nur Währungen und Zeitraum gehen hinaus).
    /// Antwortet es nicht, die festen Dateien der EZB (für alle gleich): die letzten 90 Tage (`EcbRates.hist90dURL`),
    /// wenn sie `from` abdecken, sonst bzw. danach die ganze Geschichte (`EcbRates.histURL`, nur `codes` gelesen und
    /// `fxSeriesTtlMillis` im Speicher). Leer = gültige Antwort ohne Kurse; nil = keine gültige Antwort. Wie Android.
    private static func ecbDays(_ codes: [String], from: LocalDay, to: LocalDay) async -> EcbRates.Days? {
        let series = EcbRates.seriesURL(codes: codes, from: from, to: to)
        if let body = try? await ExchangeHTTP.call(series, session: session), let days = EcbRates.parseSDMX(body) {
            return days
        }
        let first = from.description
        let last = to.description
        func inRange(_ days: EcbRates.Days) -> EcbRates.Days { days.filter { $0.key >= first && $0.key <= last } }
        if EcbRates.coveredByHist90d(from: from, today: LocalDay.todayUTC()),
           let body = try? await ExchangeHTTP.call(EcbRates.hist90dURL, session: session),
           let days = EcbRates.parseXML(body) {
            return inRange(days)
        }
        let only = Set(codes).subtracting(["EUR"])
        let key = only.sorted().joined(separator: "+")
        let now = EpochTime.nowMillis
        let ttl = fxSeriesTtlMillis
        let known: EcbRates.Days? = fullHistory.withLock {
            guard let entry = $0, entry.key == key, now - entry.at >= 0, now - entry.at < ttl else { return nil }
            return entry.days
        }
        if let known { return inRange(known) }
        guard let body = try? await ExchangeHTTP.call(EcbRates.histURL, session: historySession),
              let days = EcbRates.parseXML(body, only: only) else { return nil }
        let entry = (key: key, days: days, at: EpochTime.nowMillis)
        fullHistory.withLock { $0 = entry }
        return inRange(days)
    }

    /// GET; nil bei Fehler. 451/403 sperrt die Quelle vorübergehend (`BlockedSources`).
    static func get(_ host: String, _ url: String) async -> String? {
        do {
            return try await ExchangeHTTP.call(url, session: session)
        } catch {
            BlockedSources.noteFailure(host, error)
            return nil
        }
    }

    /// Namen der Merkliste je Coin (`TokenIdentityStore.watchName`), der Stand der Merkliste einmal gelesen.
    static func watchNames(_ coins: [String]) -> [String: String] {
        let watches = SharedStorage.loadSnapshot().watches
        var out: [String: String] = [:]
        for coin in coins where out[coin] == nil {
            if let name = TokenIdentityStore.shared.watchName(coin, watches: watches) { out[coin] = name }
        }
        return out
    }

    static func isAsset(_ s: String) -> Bool {
        !s.isEmpty && s.count <= 15 && s.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber) }
    }
}
