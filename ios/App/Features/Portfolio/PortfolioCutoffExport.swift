import Foundation
import SwiftUI
import UniformTypeIdentifiers

// Stichtag-Export (z. B. Wertschriftenverzeichnis per 31.12.) — wie `CutoffExport.kt`,
// `HistoricPriceSource.kt` und `PortfolioExportViewModel.kt` der Android-Fassung.

/// Kalendertag ohne Uhrzeit (Jahr, Monat, Tag).
struct CutoffDay: Hashable, Sendable {
    let year: Int
    let month: Int
    let day: Int

    init(year: Int, month: Int, day: Int) {
        self.year = year
        self.month = month
        self.day = day
    }

    /// Tag von `date` im Kalender `calendar` (Zeitzone des Geräts).
    init(_ date: Date, calendar: Calendar = .current) {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        self.init(year: c.year ?? 1970, month: c.month ?? 1, day: c.day ?? 1)
    }

    /// Standardstichtag: 31. Dezember des Vorjahrs.
    static func defaultDay(today: Date, calendar: Calendar = .current) -> CutoffDay {
        CutoffDay(year: (calendar.dateComponents([.year], from: today).year ?? 1971) - 1, month: 12, day: 31)
    }

    /// «2025-12-31»
    var iso: String { String(format: "%04d-%02d-%02d", year, month, day) }

    /// Beginn des Tags in `timeZone`.
    func start(in timeZone: TimeZone) -> Date {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        return cal.date(from: DateComponents(year: year, month: month, day: day)) ?? Date(timeIntervalSince1970: 0)
    }

    /// Letzte Millisekunde des Tags in `timeZone`.
    func endOfDayMillis(in timeZone: TimeZone) -> Int64 {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        let dayStart = self.start(in: timeZone)
        let next = cal.date(byAdding: .day, value: 1, to: dayStart) ?? dayStart.addingTimeInterval(86_400)
        return Int64((next.timeIntervalSince1970 * 1000).rounded()) - 1
    }

    /// UTC-Mitternacht (Beginn der Binance-/Coinbase-Tageskerze).
    var utcStartMillis: Int64 {
        let utcStart = self.start(in: TimeZone(identifier: "UTC") ?? .gmt)
        return Int64((utcStart.timeIntervalSince1970 * 1000).rounded())
    }

    /// 12:00 Ortszeit — für die Datumswahl.
    var noonLocal: Date { start(in: .current).addingTimeInterval(12 * 3600) }
}

/// Bestand eines Coins am Stichtag.
struct CutoffHolding: Equatable, Sendable {
    let coin: String
    let amount: Double
}

/// Eine Zeile des Exports. nil = nicht bekannt.
struct CutoffRow: Equatable, Sendable {
    let coin: String
    let amount: Double
    let priceUsdt: Double?
    let valueUsdt: Double?
    let rate: Double?
    let valueTarget: Double?
    let noPrice: Bool
    let noFx: Bool
}

/// Übersetzte Texte für die CSV-Datei.
struct CutoffCsvTexts: Sendable {
    var coin: String
    var amount: String
    var priceUsdt: String
    var valueUsdt: String
    /// Bereits mit der Zielwährung, z. B. «Kurs USD→CHF».
    var rate: String
    /// Bereits mit der Zielwährung, z. B. «Wert CHF».
    var valueTarget: String
    var note: String
    var total: String
    var noPrice: String
    var noFx: String
    var incomplete: String
    /// Kommentarzeile; 1. = Stichtag, 2. = Währung, 3. = Datum des Devisenkurses (je `%@`).
    var commentFormat: String

    /// Aus den App-Texten, Spaltentitel mit der Zielwährung.
    static func localized(currency: String) -> CutoffCsvTexts {
        CutoffCsvTexts(
            coin: L("portfolio_export_col_coin"),
            amount: L("portfolio_export_col_amount"),
            priceUsdt: L("portfolio_export_col_price"),
            valueUsdt: L("portfolio_export_col_value"),
            rate: L("portfolio_export_col_rate", currency),
            valueTarget: L("portfolio_export_col_value_cur", currency),
            note: L("portfolio_export_col_note"),
            total: L("portfolio_export_total"),
            noPrice: L("portfolio_export_no_price"),
            noFx: L("portfolio_export_no_fx"),
            incomplete: L("portfolio_export_incomplete"),
            commentFormat: L("portfolio_export_comment")
        )
    }
}

/// Reine Rechnung und CSV: UTF-8 mit BOM (für Excel), Trennzeichen «;»,
/// Dezimalpunkt «.», Zeilenende CRLF.
enum CutoffExport {
    /// Stablecoins: die eine Liste aus `PortfolioStables` (Tagesschluss, sonst 1; USDT immer 1).
    static var stables: Set<String> { PortfolioStables.coins }

    private static let separator = ";"
    private static let bom = "\u{FEFF}"
    private static let eol = "\r\n"
    private static let posix = Locale(identifier: "en_US_POSIX")

    static func isStable(_ coin: String) -> Bool { PortfolioStables.isStable(coin) }

    static func fileName(_ day: CutoffDay) -> String { "cryptochecker-stichtag-\(day.iso).csv" }

    /// Bestand je Coin aus allen Transaktionen mit Zeit ≤ `cutoffMillis`; ohne Bestand fällt weg. Alphabetisch.
    static func holdingsAt(_ trades: [PortfolioTx], cutoffMillis: Int64) -> [CutoffHolding] {
        let until = trades.filter { $0.time <= cutoffMillis }
        var seen = Set<String>()
        var coins: [String] = []
        for t in until {
            let coin = PortfolioCalculator.normalizeCoin(t.coin)
            if seen.insert(coin).inserted { coins.append(coin) }
        }
        return coins.compactMap { coin -> CutoffHolding? in
            let holdings = PortfolioCalculator.position(coin, trades: until, currentPrice: nil).holdings
            return holdings > PortfolioCalculator.eps ? CutoffHolding(coin: coin, amount: holdings) : nil
        }
        .sorted { $0.coin < $1.coin }
    }

    /// Zeilen aus Bestand, Kursen (Coin → Tagesschluss in USDT) und Devisenkurs (nil = unbekannt).
    /// Stablecoins nach `PortfolioStables.price`: USDT = 1, die übrigen ihr Tagesschluss, fehlt er, 1.
    static func rows(_ holdings: [CutoffHolding], prices: [String: Double], fxRate: Double?) -> [CutoffRow] {
        let rate = fxRate.flatMap { $0 > 0 && $0.isFinite ? $0 : nil }
        return holdings.map { h in
            let price = PortfolioStables.price(h.coin, market: prices[h.coin])
            let value = price.map { h.amount * $0 }
            var target: Double?
            if let value, let rate { target = value * rate }
            return CutoffRow(
                coin: h.coin, amount: h.amount, priceUsdt: price, valueUsdt: value,
                rate: rate, valueTarget: target, noPrice: price == nil, noFx: rate == nil
            )
        }
    }

    /// Ganze CSV-Datei (mit BOM). `fxDate` = tatsächliches Kursdatum (nil → «—»).
    static func csv(day: CutoffDay, currency: String, fxDate: String?, rows: [CutoffRow], texts: CutoffCsvTexts) -> String {
        var out = bom
        let comment = String(format: texts.commentFormat, locale: posix, day.iso, currency, fxDate ?? "—")
        out += line([comment])
        out += line([texts.coin, texts.amount, texts.priceUsdt, texts.valueUsdt, texts.rate, texts.valueTarget, texts.note])
        for r in rows {
            var notes: [String] = []
            if r.noPrice { notes.append(texts.noPrice) }
            if r.noFx { notes.append(texts.noFx) }
            out += line([
                r.coin,
                amount(r.amount),
                r.priceUsdt.map { price($0) } ?? "",
                r.valueUsdt.map { money($0) } ?? "",
                r.rate.map { rate($0) } ?? "",
                r.valueTarget.map { money($0) } ?? "",
                notes.joined(separator: ", "),
            ])
        }

        // Total: Summe der bekannten Werte; fehlt irgendwo ein Kurs, steht «unvollständig» dabei
        let totalUsdt = rows.reduce(0.0) { $0 + ($1.valueUsdt ?? 0) }
        let anyValue = rows.contains { $0.valueUsdt != nil }
        let anyTarget = rows.contains { $0.valueTarget != nil }
        let totalTarget = rows.reduce(0.0) { $0 + ($1.valueTarget ?? 0) }
        var totalNotes: [String] = []
        if rows.contains(where: { $0.noPrice }) { totalNotes.append(texts.incomplete) }
        if !rows.isEmpty && rows.allSatisfy({ $0.noFx }) { totalNotes.append(texts.noFx) }
        out += line([
            texts.total,
            "",
            "",
            (anyValue || rows.isEmpty) ? money(totalUsdt) : "",
            rows.first?.rate.map { rate($0) } ?? "",
            anyTarget ? money(totalTarget) : "",
            totalNotes.joined(separator: ", "),
        ])
        return out
    }

    private static func line(_ fields: [String]) -> String {
        fields.map(escape).joined(separator: separator) + eol
    }

    /// Feld in Anführungszeichen, wenn es «;», «"» oder einen Zeilenumbruch enthält.
    static func escape(_ field: String) -> String {
        guard field.contains(where: { $0 == ";" || $0 == "\"" || $0 == "\n" || $0 == "\r" || $0 == "\r\n" }) else {
            return field
        }
        return "\"" + field.replacingOccurrences(of: "\"", with: "\"\"") + "\""
    }

    // MARK: Zahlen (Dezimalpunkt, ohne Tausendertrennung)

    // Gerundet wird kaufmännisch (HALF_UP) ab der kürzesten Dezimaldarstellung (`DecimalText`),
    // gleich wie Android (`CutoffExport.kt`): 1.005 → «1.01». Nicht endlich → leeres Feld.

    /// Menge: bis 10 Nachkommastellen, unter 1 bis 10 gültige Stellen (3e-11 → «0.00000000003»), ohne Nullen am Ende.
    static func amount(_ v: Double) -> String { significant(v) }
    /// Kurs: wie `amount` — auch Kleinstkurse (3e-11) bleiben lesbar statt «0».
    static func price(_ v: Double) -> String { significant(v) }
    /// Devisenkurs: bis 6 Nachkommastellen.
    static func rate(_ v: Double) -> String { DecimalText.plain(v, scale: 6) }
    /// Geldbetrag: genau 2 Nachkommastellen; «-0.00» → «0.00».
    static func money(_ v: Double) -> String { DecimalText.fixed(v, scale: 2) }

    private static func significant(_ v: Double) -> String {
        DecimalText.plain(v, scale: DecimalText.significantScale(v, minDecimals: 10, significant: 10))
    }
}

// MARK: Historische Kurse

/// Devisenkurs eines Stichtags mit dem tatsächlichen Kursdatum (Wochenende → Freitag).
struct HistoricFx: Sendable {
    let rate: Double
    let date: String?
}

/// Tagesschlusskurse (UTC-Tageskerze) in USDT und Devisenkurs eines Tags.
/// Kerzen-Kette wie `CandleDataSource`: data-api.binance.vision → api.binance.com →
/// fapi.binance.com → api.binance.us (USDT, sonst USD) → Coinbase (USD, sonst USDC).
/// Es zählt nur die Kerze, die genau an diesem Tag beginnt. Kurse ohne Zwischenspeicher.
/// Plausibilität (`PricePlausibility.acceptSourceClose`): Ausweich-Quellen (Futures, Binance.US,
/// Coinbase) zählen nur, wenn dieselbe Quelle den Coin heute höchstens 25 % vom aktuellen
/// Portfolio-Kurs entfernt führt (eine Abfrage mehr, nur bei einem Treffer dort).
/// Stablecoins nach `PortfolioStables`: USDT = 1 ohne Abfrage, andere wie jeder Coin.
/// Dazu die Devisen-Tageskurse eines Zeitraums für den Wertverlauf (`usdToSeries`, Frankfurter,
/// 6 h im Speicher).
enum HistoricPriceSource {
    private static let parallel = 4

    /// Eigene Sitzung mit ~10 s Zeitgrenze je Anfrage.
    private static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 10
        c.timeoutIntervalForResource = 15
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.httpAdditionalHeaders = ["User-Agent": "cryptoChecker-iOS/16", "Accept": "application/json"]
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()

    /// Schlusskurse der Coins am Tag `day`; Coins ohne (plausiblen) Kurs fehlen in der Rückgabe.
    /// `current`: aktuelle Portfolio-Kurse in USDT für die Plausibilitätsprüfung (fehlt einer, gilt
    /// der Kurs der ersten Quelle ungeprüft).
    static func dailyClosesUsdt(_ coins: [String], day: CutoffDay, current: [String: Double] = [:]) async -> [String: Double] {
        var symbols: [String] = []
        for c in coins.map(PortfolioCalculator.normalizeCoin) where isAsset(c) && !symbols.contains(c) {
            symbols.append(c)
        }
        var result: [String: Double] = [:]
        // In Gruppen zu je `parallel` Abfragen
        var index = 0
        while index < symbols.count {
            let chunk = Array(symbols[index..<min(index + parallel, symbols.count)])
            index += parallel
            let found = await withTaskGroup(of: (String, Double?).self, returning: [String: Double].self) { group in
                for coin in chunk {
                    group.addTask {
                        let price = await dailyCloseUsdt(coin, day: day, current: current[coin])
                        return (coin, price)
                    }
                }
                var partial: [String: Double] = [:]
                for await (coin, price) in group {
                    if let price { partial[coin] = price }
                }
                return partial
            }
            result.merge(found) { _, new in new }
        }
        return result
    }

    /// Schlusskurs eines Coins; nil, wenn keine Quelle einen plausiblen hat. USDT = 1, andere
    /// Stablecoins werden wie jeder Coin abgefragt. `current` = aktueller Portfolio-Kurs (Plausibilität).
    static func dailyCloseUsdt(_ coin: String, day: CutoffDay, current: Double? = nil) async -> Double? {
        let b = PortfolioCalculator.normalizeCoin(coin)
        if !PortfolioStables.needsQuote(b) { return 1.0 }
        guard isAsset(b) else { return nil }
        let start = day.utcStartMillis

        // Binance-Spot liefert auch die aktuellen Portfolio-Kurse — keine Prüfung nötig
        let binance: [(host: String, endpoint: String, trusted: Bool)] = [
            ("data-api.binance.vision", "https://data-api.binance.vision/api/v3/klines", true),
            ("api.binance.com", "https://api.binance.com/api/v3/klines", true),
            ("fapi.binance.com", "https://fapi.binance.com/fapi/v1/klines", false),
        ]
        for source in binance {
            if let close = await binanceClose(host: source.host, endpoint: source.endpoint, symbol: b + "USDT",
                                              start: start, trusted: source.trusted, current: current) {
                return close
            }
        }
        for symbol in [b + "USDT", b + "USD"] {
            if let close = await binanceClose(host: "api.binance.us", endpoint: "https://api.binance.us/api/v3/klines",
                                              symbol: symbol, start: start, trusted: false, current: current) {
                return close
            }
        }
        return await coinbaseClose(b, day: day, start: start, current: current)
    }

    private static func binanceClose(host: String, endpoint: String, symbol: String, start: Int64,
                                     trusted: Bool, current: Double?) async -> Double? {
        if BlockedSources.isBlocked(host) { return nil }
        guard let body = await get(host, "\(endpoint)?symbol=\(symbol)&interval=1d&startTime=\(start)&limit=1"),
              let candles = try? CandleDataSource.parseBinance(body),
              let close = closeOf(candles, start: start)
        else { return nil }
        if trusted || current == nil { return close }
        // Ausweich-Quelle: führt sie den Coin heute zum aktuellen Kurs? (jüngste Tageskerze)
        var latest: Double?
        if let recent = await get(host, "\(endpoint)?symbol=\(symbol)&interval=1d&limit=1") {
            latest = (try? CandleDataSource.parseBinance(recent))?.last?.close
        }
        return PricePlausibility.acceptSourceClose(close, trusted: false, sourceLatest: latest, current: current) ? close : nil
    }

    private static func coinbaseClose(_ b: String, day: CutoffDay, start: Int64, current: Double?) async -> Double? {
        let host = "api.exchange.coinbase.com"
        // Fenster genau über den einen Tag (ISO 8601, UTC)
        let from = "\(day.iso)T00:00:00Z"
        let to = "\(day.iso)T23:59:59Z"
        for quote in ["USD", "USDC"] {
            if BlockedSources.isBlocked(host) { return nil }
            let url = "https://\(host)/products/\(b)-\(quote)/candles?granularity=86400&start=\(from)&end=\(to)"
            guard let body = await get(host, url),
                  let candles = try? CandleDataSource.parseCoinbase(body),
                  let close = closeOf(candles, start: start)
            else { continue }
            guard let current else { return close }
            // Jüngste Tageskerzen desselben Produkts (ohne Zeitfenster = die neuesten)
            var latest: Double?
            if let recent = await get(host, "https://\(host)/products/\(b)-\(quote)/candles?granularity=86400") {
                latest = (try? CandleDataSource.parseCoinbase(recent))?.max(by: { $0.openTime < $1.openTime })?.close
            }
            if PricePlausibility.acceptSourceClose(close, trusted: false, sourceLatest: latest, current: current) {
                return close
            }
        }
        return nil
    }

    private static func closeOf(_ candles: [MarketCandle], start: Int64) -> Double? {
        guard let close = candles.first(where: { $0.openTime == start })?.close, close > 0, close.isFinite else { return nil }
        return close
    }

    /// Devisenkurs USD → `currency` am Tag `day`; USD = 1 (ohne Datum). Frankfurter liefert
    /// an Wochenenden/Feiertagen den letzten Geschäftstag — dessen Datum steht in `date`.
    static func usdTo(_ currency: String, day: CutoffDay) async -> HistoricFx? {
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
            guard let body = try? await MarketHTTP.call(url, session: session),
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
    private static let fxSeriesLock = NSLock()
    /// «Währung|von|bis» → (Tageskurse, Abfragezeit); nur im Speicher.
    nonisolated(unsafe) private static var fxSeriesCache: [String: (rates: [Int: Double], at: Int64)] = [:]

    /// Tageskurse USD → `currency` von `from` bis `to` (EZB über Frankfurter, eine Abfrage für den
    /// ganzen Zeitraum): Tag (epochDay) → Kurs, nur Geschäftstage — Wochenenden und Feiertage
    /// füllt `PortfolioHistoryFx.rateOn` mit dem Vortag. USD: leer (kein Bedarf). 6 h im Speicher.
    /// nil, wenn kein Kurs zu haben ist.
    static func usdToSeries(_ currency: String, from: LocalDay, to: LocalDay) async -> [Int: Double]? {
        let target = currency.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        if target == "USD" { return [:] }
        guard FxRateSource.isCurrencyCode(target), from <= to else { return nil }
        let key = "\(target)|\(from)|\(to)"
        let now = TimeUtils.nowMillis
        let cached: [Int: Double]? = fxSeriesLock.withLock {
            guard let entry = fxSeriesCache[key], now - entry.at >= 0, now - entry.at < fxSeriesTtlMillis else { return nil }
            return entry.rates
        }
        if let cached { return cached }
        let symbols = PortfolioHistoryFx.requestCurrencies(target).joined(separator: ",")
        let urls = [
            "https://api.frankfurter.app/\(from)..\(to)?from=USD&to=\(symbols)",
            "https://api.frankfurter.dev/v1/\(from)..\(to)?from=USD&to=\(symbols)",
        ]
        for url in urls {
            guard let body = try? await MarketHTTP.call(url, session: session),
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
            let series = PortfolioHistoryFx.ratesByDay(target, byDate: byDate)
            if !series.isEmpty {
                fxSeriesLock.withLock { fxSeriesCache[key] = (rates: series, at: TimeUtils.nowMillis) }
                return series
            }
        }
        return nil
    }

    /// GET; nil bei Fehler. 451/403 sperrt die Quelle vorübergehend (`BlockedSources`).
    private static func get(_ host: String, _ url: String) async -> String? {
        do {
            return try await MarketHTTP.call(url, session: session)
        } catch {
            BlockedSources.noteFailure(host, error)
            return nil
        }
    }

    private static func isAsset(_ s: String) -> Bool {
        !s.isEmpty && s.count <= 15 && s.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber) }
    }
}

// MARK: Ablauf

enum PortfolioCutoffExporter {
    /// Bestand am Ende des Tags (Zeitzone des Geräts), Kurse und Devisenkurs holen, CSV bauen.
    static func build(transactions: [PortfolioTx], day: CutoffDay, currency: String, texts: CutoffCsvTexts) async -> String {
        let cutoff = day.endOfDayMillis(in: .current)
        let holdings = CutoffExport.holdingsAt(transactions, cutoffMillis: cutoff)
        // USDT = 1 ohne Abfrage; andere Stablecoins wie jeder Coin (fehlt der Kurs, gilt 1)
        let coins = holdings.map(\.coin).filter { PortfolioStables.needsQuote($0) }
        // Aktuelle Kurse als Bezug der Plausibilitätsprüfung (Ausweich-Quellen); ohne sie ungeprüft
        var current: [String: Double] = [:]
        if !coins.isEmpty { current = await PortfolioPriceSource.prices(coins).prices }
        let prices = await HistoricPriceSource.dailyClosesUsdt(coins, day: day, current: current)
        var fx: HistoricFx?
        if !holdings.isEmpty || currency == "USD" {
            fx = await HistoricPriceSource.usdTo(currency, day: day)
        }
        let rows = CutoffExport.rows(holdings, prices: prices, fxRate: fx?.rate)
        return CutoffExport.csv(day: day, currency: currency, fxDate: fx?.date, rows: rows, texts: texts)
    }
}

/// CSV-Datei für `.fileExporter`.
struct CutoffCsvDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.commaSeparatedText] }
    static var writableContentTypes: [UTType] { [.commaSeparatedText] }

    var data: Data

    init(data: Data) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        guard let contents = configuration.file.regularFileContents else {
            throw CocoaError(.fileReadCorruptFile)
        }
        data = contents
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
