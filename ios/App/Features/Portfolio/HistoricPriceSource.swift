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
/// Dazu die Devisen-Tageskurse eines Zeitraums für den Wertverlauf (`usdToSeries`, Frankfurter,
/// 6 h im Speicher).
enum HistoricPriceSource {
    private static let parallel = 4

    /// Eigene Sitzung mit ~10 s Zeitgrenze je Anfrage.
    private static let session: URLSession = {
        let c = PrivateSessionConfig.make(timeout: 10, resource: 15)
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.httpAdditionalHeaders = [HttpUserAgent.header: HttpUserAgent.value, "Accept": "application/json"]
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
        // Eingetragene Börsen einmal für alle Coins (aus der App Group, ohne Netz)
        let own = ownRoutes(symbols)
        // In Gruppen zu je `parallel` Abfragen
        var index = 0
        while index < symbols.count {
            let chunk = Array(symbols[index..<min(index + parallel, symbols.count)])
            index += parallel
            let found = await withTaskGroup(of: (String, CloseResult).self, returning: CutoffCloses.self) { group in
                for coin in chunk {
                    group.addTask {
                        let r = await closeUsdt(coin, day: day, current: current[coin], own: own[coin])
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
    struct CloseResult: Sendable {
        var price: Double?
        var unchecked = false
        var source: String?
    }

    /// Schlusskurs eines Coins (UTC-Tageskerze). USDT = 1, andere Stablecoins werden wie jeder Coin
    /// abgefragt. `current` = aktueller Portfolio-Kurs (Bezug der Identitätsprüfung); ohne gültigen
    /// Bezug keine Abfrage, «ungeprüft».
    static func dailyCloseUsdt(_ coin: String, day: CutoffDay, current: Double?) async -> CloseResult {
        let b = PortfolioCalculator.normalizeCoin(coin)
        return await closeUsdt(b, day: day, current: current, own: ownRoutes([b])[b])
    }

    /// Wie `dailyCloseUsdt`, mit schon gelesener eigener Börse `own` (nil = keine bzw. Binance).
    private static func closeUsdt(_ coin: String, day: CutoffDay, current: Double?, own: CandlePair?) async -> CloseResult {
        let b = PortfolioCalculator.normalizeCoin(coin)
        if !PortfolioStables.needsQuote(b) { return CloseResult(price: 1.0) }
        guard isAsset(b) else { return CloseResult(price: nil) }
        // Streng: ohne Bezug ist der gleiche Token nicht bestätigt — auch nicht über Ausweich-Quellen
        guard let reference = current, reference > 0, reference.isFinite else {
            return CloseResult(price: nil, unchecked: true)
        }
        let start = day.utcStartMillis
        let names = TokenIdentityStore.shared
        let ownName = names.watchName(b, watches: SharedStorage.loadSnapshot().watches)
        let resolved = await HistoricCloseChain.resolve(HistoricCloseChain.order(own?.exchangeKey)) { source in
            if let own, source == own.exchangeKey {
                return await ownOutcome(own, base: b, day: day, start: start, reference: reference, ownName: ownName)
            }
            switch source {
            case HistoricCloseChain.binance: return await binanceOutcome(b, start: start, reference: reference, ownName: ownName)
            case HistoricCloseChain.coinbase: return await coinbaseOutcome(b, day: day, start: start, reference: reference)
            default: return await chainOutcome(source, base: b, start: start, reference: reference, ownName: ownName)
            }
        }
        return CloseResult(price: resolved.price, unchecked: resolved.unchecked, source: resolved.provider)
    }

    /// Coinbase wie bisher (Fenster genau über den Tag, jedes Alter; USD, sonst USDC; ±25 %).
    static func coinbaseOutcome(_ b: String, day: CutoffDay, start: Int64, reference: Double) async -> HistoricCloseChain.Outcome {
        let r = await coinbaseClose(b, day: day, start: start, current: reference)
        return HistoricCloseChain.Outcome(price: r.price, rejected: r.unchecked, provider: DataFreshness.coinbase)
    }

    /// Binance-Gruppe wie bisher: Spot (data-api, api; 1/3…3), Futures (±25 %), Binance.US (USDT, sonst USD; ±25 %).
    private static func binanceOutcome(_ b: String, start: Int64, reference: Double,
                                       ownName: String?) async -> HistoricCloseChain.Outcome {
        // Gab es einen Kurs, der die Prüfung nicht bestand?
        var rejected = false

        // Binance-Spot: Identitätsprüfung 1/3…3; Futures und Ausweich-Quellen zusätzlich ±25 %
        let binance: [(host: String, endpoint: String, primary: Bool)] = [
            ("data-api.binance.vision", "https://data-api.binance.vision/api/v3/klines", true),
            ("api.binance.com", "https://api.binance.com/api/v3/klines", true),
            ("fapi.binance.com", "https://fapi.binance.com/fapi/v1/klines", false),
        ]
        // Gleicher Token (K-8): Nennen die Paare der Merkliste den Coin klar anders als Binance, fällt Binance aus
        let binanceOk = TokenIdentityStore.shared.binanceAllowed(base: b, ownName: ownName)
        if !binanceOk { rejected = true }
        for source in binance where binanceOk {
            let r = await binanceClose(host: source.host, endpoint: source.endpoint, symbol: b + "USDT",
                                       start: start, primary: source.primary, current: reference)
            if let price = r.price {
                return HistoricCloseChain.Outcome(price: price, provider: DataFreshness.candleProvider(host: source.host))
            }
            rejected = rejected || r.unchecked
        }
        for symbol in [b + "USDT", b + "USD"] {
            let r = await binanceClose(host: "api.binance.us", endpoint: "https://api.binance.us/api/v3/klines",
                                       symbol: symbol, start: start, primary: false, current: reference)
            if let price = r.price { return HistoricCloseChain.Outcome(price: price, provider: DataFreshness.binanceUS) }
            rejected = rejected || r.unchecked
        }
        return HistoricCloseChain.Outcome(price: nil, rejected: rejected)
    }

    private static func binanceClose(host: String, endpoint: String, symbol: String, start: Int64,
                                     primary: Bool, current: Double) async -> CloseResult {
        if BlockedSources.isBlocked(host) { return CloseResult(price: nil) }
        guard let body = await get(host, "\(endpoint)?symbol=\(symbol)&interval=1d&startTime=\(start)&limit=1"),
              let candles = try? CandleDataSource.parseBinance(body),
              let close = closeOf(candles, start: start)
        else { return CloseResult(price: nil) }
        // Führt die Quelle den Coin heute als denselben Token? (jüngste Tageskerze)
        var latest: Double?
        if let recent = await get(host, "\(endpoint)?symbol=\(symbol)&interval=1d&limit=1") {
            latest = (try? CandleDataSource.parseBinance(recent))?.last?.close
        }
        return PricePlausibility.acceptSourceClose(close, primary: primary, sourceLatest: latest, current: current)
            ? CloseResult(price: close) : CloseResult(price: nil, unchecked: true)
    }

    private static func coinbaseClose(_ b: String, day: CutoffDay, start: Int64, current: Double) async -> CloseResult {
        let host = "api.exchange.coinbase.com"
        // Fenster genau über den einen Tag (ISO 8601, UTC)
        let from = "\(day.iso)T00:00:00Z"
        let to = "\(day.iso)T23:59:59Z"
        var rejected = false
        for quote in ["USD", "USDC"] {
            if BlockedSources.isBlocked(host) { break }
            let url = "https://\(host)/products/\(b)-\(quote)/candles?granularity=86400&start=\(from)&end=\(to)"
            guard let body = await get(host, url),
                  let candles = try? CandleDataSource.parseCoinbase(body),
                  let close = closeOf(candles, start: start)
            else { continue }
            // Jüngste Tageskerzen desselben Produkts (ohne Zeitfenster = die neuesten)
            var latest: Double?
            if let recent = await get(host, "https://\(host)/products/\(b)-\(quote)/candles?granularity=86400") {
                latest = (try? CandleDataSource.parseCoinbase(recent))?.max(by: { $0.openTime < $1.openTime })?.close
            }
            if PricePlausibility.acceptSourceClose(close, primary: false, sourceLatest: latest, current: current) {
                return CloseResult(price: close)
            }
            rejected = true
        }
        return CloseResult(price: nil, unchecked: rejected)
    }

    private static func closeOf(_ candles: [MarketCandle], start: Int64) -> Double? {
        guard let close = candles.first(where: { $0.openTime == start })?.close, close > 0, close.isFinite else { return nil }
        return close
    }

    /// Devisenkurs USD → `currency` am Tag `day`; USD = 1 (ohne Datum). Frankfurter liefert
    /// an Wochenenden/Feiertagen den letzten Geschäftstag — dessen Datum steht in `date`.
    static func usdTo(_ currency: String, day: CutoffDay) async -> HistoricFx? {
        // Unverändert durch die Ausweich-Kette der Kurse: Wochenende/Feiertag → letzter Geschäftstag (Frankfurter)
        let target = currency.trimmingCharacters(in: .whitespaces).uppercased()
        if target == "USD" { return HistoricFx(rate: 1, date: nil) }
        guard FxRateSource.isCurrencyCode(target) else { return nil }
        if let fx = await loadUsdTo(target, day: day) { return fx }
        // Lew ab 2026: aus dem EUR-Kurs desselben Tags (fester Kurs 1.95583)
        guard target == "BGN", day.iso >= FxRateSource.bgnEuroDay,
              let eur = await loadUsdTo("EUR", day: day),
              let bgn = FxRateSource.derivedBgn(currency: target, usdToEur: eur.rate, dayIso: day.iso)
        else { return nil }
        return HistoricFx(rate: bgn, date: eur.date)
    }

    private static func loadUsdTo(_ target: String, day: CutoffDay) async -> HistoricFx? {
        let urls = [
            "https://api.frankfurter.app/\(day.iso)?from=USD&to=\(target)",
            "https://api.frankfurter.dev/v1/\(day.iso)?from=USD&to=\(target)",
        ]
        for url in urls {
            guard let body = try? await ExchangeHTTP.call(url, session: session),
                  let data = body.data(using: .utf8),
                  let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                  let rates = root["rates"] as? [String: Any],
                  let rate = (rates[target] as? NSNumber)?.doubleValue,
                  rate > 0, rate.isFinite
            else { continue }
            return HistoricFx(rate: rate, date: root["date"] as? String)
        }
        return nil
    }

    // MARK: Devisen-Tageskurse für den Wertverlauf

    private static let fxSeriesTtlMillis: Int64 = 6 * 60 * 60_000
    /// «Währung|von|bis» → (Tageskurse, Abfragezeit); nur im Speicher, hinter einer Sperre mit Zustand.
    private static let fxSeriesCache = OSAllocatedUnfairLock(
        initialState: [String: (rates: [Int: Double], at: Int64)]())

    /// Tageskurse USD → `currency` von `from` bis `to` (EZB über Frankfurter, in Stücken von höchstens
    /// `PortfolioHistoryFx.fxChunkDays` Tagen): Tag (epochDay) → Kurs, nur Geschäftstage — Wochenenden und Feiertage
    /// füllt `PortfolioHistoryFx.rateOn` mit dem Vortag. USD: leer (kein Bedarf). 6 h im Speicher.
    /// nil, wenn kein Kurs zu haben ist oder ein Stück bei beiden Anbietern scheitert.
    static func usdToSeries(_ currency: String, from: LocalDay, to: LocalDay) async -> [Int: Double]? {
        let target = currency.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        if target == "USD" { return [:] }
        guard FxRateSource.isCurrencyCode(target), from <= to else { return nil }
        let key = "\(target)|\(from)|\(to)"
        let now = EpochTime.nowMillis
        let ttl = fxSeriesTtlMillis
        let cached: [Int: Double]? = fxSeriesCache.withLock {
            guard let entry = $0[key], now - entry.at >= 0, now - entry.at < ttl else { return nil }
            return entry.rates
        }
        if let cached { return cached }
        let symbols = PortfolioHistoryFx.requestCurrencies(target).joined(separator: ",")
        // In Stücken von höchstens einem Jahr: längere Zeiträume liefert Frankfurter nur wöchentlich. Scheitert ein Stück
        // bei beiden Anbietern, keine Reihe (lieber keine als eine mit einer Lücke von einem Jahr). Wie Android.
        // Ein Stück ohne Werktag (nur Wochenende bzw. nur heute) darf scheitern: dort zählt es als leer
        var merged: [Int: Double] = [:]
        let today = LocalDay.todayUTC()
        for chunk in PortfolioHistoryFx.fxChunks(from: from, to: to) {
            var fetched = await fxSeriesChunk(target, symbols: symbols, from: chunk.from, to: chunk.to)
            if fetched == nil && !PortfolioHistoryFx.chunkMayHaveRates(from: chunk.from, to: chunk.to, today: today) {
                fetched = [:]
            }
            guard let part = fetched else { return nil }
            merged.merge(part) { _, new in new }
        }
        guard !merged.isEmpty else { return nil }
        let entry = (rates: merged, at: EpochTime.nowMillis)
        fxSeriesCache.withLock { $0[key] = entry }
        return merged
    }

    /// Ein Stück der Zeitreihe (`usdToSeries`) von beiden Frankfurter-Adressen: die erste Antwort mit Kursen, sonst eine
    /// gültige leere (z. B. Zeitraum ohne Kurse dieser Währung); nil, wenn keine Adresse eine gültige Antwort gibt.
    private static func fxSeriesChunk(_ target: String, symbols: String, from: LocalDay, to: LocalDay) async -> [Int: Double]? {
        let urls = [
            "https://api.frankfurter.app/\(from)..\(to)?from=USD&to=\(symbols)",
            "https://api.frankfurter.dev/v1/\(from)..\(to)?from=USD&to=\(symbols)",
        ]
        var answered = false
        for url in urls {
            guard let body = try? await ExchangeHTTP.call(url, session: session),
                  let data = body.data(using: .utf8),
                  let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                  let rates = root["rates"] as? [String: Any]
            else { continue }
            var byDate: [String: [String: Double]] = [:]
            for (date, value) in rates {
                guard let day = value as? [String: Any] else { continue }
                var parsed: [String: Double] = [:]
                for (code, rate) in day {
                    if let number = rate as? NSNumber { parsed[code] = number.doubleValue }
                }
                byDate[date] = parsed
            }
            answered = true
            let series = PortfolioHistoryFx.ratesByDay(target, byDate: byDate)
            if !series.isEmpty { return series }
        }
        return answered ? [:] : nil
    }

    /// GET; nil bei Fehler. 451/403 sperrt die Quelle vorübergehend (`BlockedSources`).
    private static func get(_ host: String, _ url: String) async -> String? {
        do {
            return try await ExchangeHTTP.call(url, session: session)
        } catch {
            BlockedSources.noteFailure(host, error)
            return nil
        }
    }

    static func isAsset(_ s: String) -> Bool {
        !s.isEmpty && s.count <= 15 && s.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber) }
    }
}
