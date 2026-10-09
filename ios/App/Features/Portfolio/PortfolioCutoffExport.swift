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
    /// Gelten als 1 USD (ohne Abfrage).
    static let stables: Set<String> = ["USDT", "USDC", "BUSD", "FDUSD", "TUSD", "USDP", "DAI", "USD"]

    private static let separator = ";"
    private static let bom = "\u{FEFF}"
    private static let eol = "\r\n"
    private static let posix = Locale(identifier: "en_US_POSIX")

    static func isStable(_ coin: String) -> Bool { stables.contains(PortfolioCalculator.normalizeCoin(coin)) }

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

    /// Zeilen aus Bestand, Kursen (Coin → USDT; Stablecoins = 1) und Devisenkurs (nil = unbekannt).
    static func rows(_ holdings: [CutoffHolding], prices: [String: Double], fxRate: Double?) -> [CutoffRow] {
        let rate = fxRate.flatMap { $0 > 0 && $0.isFinite ? $0 : nil }
        return holdings.map { h in
            let known = prices[h.coin].flatMap { $0 > 0 && $0.isFinite ? $0 : nil }
            let price: Double? = known ?? (isStable(h.coin) ? 1.0 : nil)
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

    static func amount(_ v: Double) -> String { trimmed(v, 10) }
    static func price(_ v: Double) -> String { trimmed(v, 10) }
    static func rate(_ v: Double) -> String { trimmed(v, 6) }
    static func money(_ v: Double) -> String { fixed(v, 2) }

    private static func fixed(_ v: Double, _ decimals: Int) -> String {
        let s = String(format: "%.\(decimals)f", locale: posix, v)
        // «-0.00» vermeiden
        let unsigned = s.hasPrefix("-") ? String(s.dropFirst()) : s
        return unsigned.allSatisfy { $0 == "0" || $0 == "." } ? unsigned : s
    }

    private static func trimmed(_ v: Double, _ decimals: Int) -> String {
        var s = fixed(v, decimals)
        guard s.contains(".") else { return s }
        while s.hasSuffix("0") { s.removeLast() }
        if s.hasSuffix(".") { s.removeLast() }
        return s
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
/// Es zählt nur die Kerze, die genau an diesem Tag beginnt. Kein Zwischenspeicher.
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

    /// Schlusskurse der Coins am Tag `day`; Coins ohne Kurs fehlen in der Rückgabe.
    static func dailyClosesUsdt(_ coins: [String], day: CutoffDay) async -> [String: Double] {
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
                        let price = await dailyCloseUsdt(coin, day: day)
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

    /// Schlusskurs eines Coins; nil, wenn keine Quelle ihn hat. Stablecoins = 1.
    static func dailyCloseUsdt(_ coin: String, day: CutoffDay) async -> Double? {
        let b = PortfolioCalculator.normalizeCoin(coin)
        if CutoffExport.isStable(b) { return 1.0 }
        guard isAsset(b) else { return nil }
        let start = day.utcStartMillis

        let binance: [(host: String, endpoint: String)] = [
            ("data-api.binance.vision", "https://data-api.binance.vision/api/v3/klines"),
            ("api.binance.com", "https://api.binance.com/api/v3/klines"),
            ("fapi.binance.com", "https://fapi.binance.com/fapi/v1/klines"),
        ]
        for source in binance {
            if let close = await binanceClose(host: source.host, endpoint: source.endpoint, symbol: b + "USDT", start: start) {
                return close
            }
        }
        for symbol in [b + "USDT", b + "USD"] {
            if let close = await binanceClose(host: "api.binance.us", endpoint: "https://api.binance.us/api/v3/klines",
                                              symbol: symbol, start: start) {
                return close
            }
        }
        return await coinbaseClose(b, day: day, start: start)
    }

    private static func binanceClose(host: String, endpoint: String, symbol: String, start: Int64) async -> Double? {
        if BlockedSources.isBlocked(host) { return nil }
        guard let body = await get(host, "\(endpoint)?symbol=\(symbol)&interval=1d&startTime=\(start)&limit=1"),
              let candles = try? CandleDataSource.parseBinance(body)
        else { return nil }
        return closeOf(candles, start: start)
    }

    private static func coinbaseClose(_ b: String, day: CutoffDay, start: Int64) async -> Double? {
        let host = "api.exchange.coinbase.com"
        // Fenster genau über den einen Tag (ISO 8601, UTC)
        let from = "\(day.iso)T00:00:00Z"
        let to = "\(day.iso)T23:59:59Z"
        for quote in ["USD", "USDC"] {
            if BlockedSources.isBlocked(host) { return nil }
            let url = "https://\(host)/products/\(b)-\(quote)/candles?granularity=86400&start=\(from)&end=\(to)"
            guard let body = await get(host, url),
                  let candles = try? CandleDataSource.parseCoinbase(body)
            else { continue }
            if let close = closeOf(candles, start: start) { return close }
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
        let coins = holdings.map(\.coin).filter { !CutoffExport.isStable($0) }
        let prices = await HistoricPriceSource.dailyClosesUsdt(coins, day: day)
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
