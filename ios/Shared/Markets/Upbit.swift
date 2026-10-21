import Foundation

/// Upbit (Südkorea). Kennung «QUOTE-BASE», z. B. KRW-BTC. Doku: docs.upbit.com
final class Upbit: UpbitStyleMarket {
    init() {
        super.init(
            key: "Upbit",
            name: "Upbit",
            ttsName: "Up bit",
            apiRoot: "https://api.upbit.com/v1",
            detailsFlag: "is_details=false",
            unfilteredURL: "https://api.upbit.com/v1/ticker/all?quote_currencies=KRW,BTC,USDT",
            tradeTimeFromUTCFields: false
        )
    }
}

/// Bithumb (Südkorea), API v1 mit Upbits Aufbau. Doku: apidocs.bithumb.com. Anders als bei Upbit ist
/// «trade_timestamp» die koreanische Uhrzeit als Epoche geschrieben (9 h zu spät); die Zeit kommt darum aus
/// «trade_date» + «trade_time» (UTC).
final class Bithumb: UpbitStyleMarket {
    init() {
        super.init(
            key: "Bithumb",
            name: "Bithumb",
            ttsName: "Bithumb",
            apiRoot: "https://api.bithumb.com/v1",
            detailsFlag: "isDetails=false",
            unfilteredURL: nil,
            tradeTimeFromUTCFields: true
        )
    }
}

/// Gemeinsamer Aufbau von Upbit und Bithumb: Paarliste `market/all`, Ticker `ticker?markets=…`, Kennung «QUOTE-BASE».
/// `unfilteredURL`: Sammelabfrage ohne Auswahl, falls die Börse eine hat (Upbit), sonst nil (Bithumb).
/// `tradeTimeFromUTCFields`: Handelszeit aus «trade_date»/«trade_time» (UTC) statt aus «trade_timestamp» (Bithumb).
class UpbitStyleMarket: SimpleMarket {
    private let unfilteredURL: String?
    private let tradeTimeFromUTCFields: Bool
    /// Gefilterte Sammelabfrage: dahinter die Kennungen mit Komma.
    private let selectionPrefix: String

    init(key: String, name: String, ttsName: String, apiRoot: String, detailsFlag: String, unfilteredURL: String?,
         tradeTimeFromUTCFields: Bool) {
        self.unfilteredURL = unfilteredURL
        self.tradeTimeFromUTCFields = tradeTimeFromUTCFields
        selectionPrefix = apiRoot + "/ticker?markets="
        super.init(
            key: key,
            name: name,
            pairListURL: apiRoot + "/market/all?" + detailsFlag,
            tickerURL: apiRoot + "/ticker?markets=%1$s",
            ttsName: ttsName
        )
    }

    // MARK: Einzelkurs

    override func pairId(_ info: PairQuery) -> String? { info.pairId ?? info.quote + "-" + info.base }

    override func fillTicker(call: Int, body: String, ticker: inout Ticker, info: PairQuery) throws {
        try UpbitStyleMarket.copyRow(JArray(string: body).object(0), into: &ticker, utcFields: tradeTimeFromUTCFields)
    }

    /// Fehlerform {"error": {"message": …}} oder {"message": …}.
    override func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        guard let nested = json.optObject("error") else { return try json.string("message") }
        return try nested.string("message")
    }

    // MARK: Sammelabfrage

    override var bulkTickersNumOfRequests: Int { 1 }

    /// Gefiltert liefert die Börse jedes angefragte Paar oder lehnt ab; ungefiltert (Upbit, alle Kotierungen) jedes
    /// gehandelte. Was fehlt, wird nicht mehr gehandelt (Einzelabfrage bestätigt, NotTraded).
    override var bulkTickersComplete: Bool { true }

    override func bulkTickersURL(call: Int) -> String? { unfilteredURL }

    /// Nur die beobachteten Paare. Kennt die Börse eines davon nicht, lehnt sie alles ab («Code not found», ohne
    /// Paar): Upbit fällt auf die ungefilterte Abfrage zurück, Bithumb lädt die Paarliste, lässt Unbekannte weg und
    /// fragt neu (`bulkKnownPairsURL`).
    override func bulkTickersURL(call: Int, pairIds: [String]) -> String? {
        if pairIds.isEmpty { return unfilteredURL }
        // Lange Listen auf mehrere URLs verteilen (sonst HTTP 414) — nur mit ungefiltertem Ersatz (Upbit). Bei
        // Bithumb teilte die Wiederholung ohne Unbekannte die Liste neu auf und träfe andere Gruppen
        guard let groups = selectionGroups(pairIds) else {
            return call == 0 ? selectionPrefix + pairIds.sorted().joined(separator: ",") : nil
        }
        return call < groups.count ? BulkPairChunks.url(prefix: selectionPrefix, chunk: groups[call]) : nil
    }

    override func bulkTickersRequestCount(pairIds: [String]) -> Int {
        selectionGroups(pairIds)?.count ?? bulkTickersNumOfRequests
    }

    override func bulkKnownPairsURL(call: Int) -> String? {
        unfilteredURL == nil ? pairListAddress(call: call) : nil
    }

    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        let utcFields = tradeTimeFromUTCFields
        for row in try JArray(string: body).allObjects() {
            bulkRowIfActive(row.optString("market"), row: row, priceField: "trade_price", tickers: &tickers,
                            failed: &failed) { try UpbitStyleMarket.copyRow(row, into: &$0, utcFields: utcFields) }
        }
        return tickers
    }

    // MARK: Paarliste

    override func fillPairList(call: Int, body: String) throws -> [MarketPair] {
        var out: [MarketPair] = []
        for entry in try JArray(string: body).allObjects() {
            let market = entry.optString("market")
            guard let sides = UpbitStyleMarket.splitMarket(market, keepingEmpty: false) else { continue }
            out.append(MarketPair(sides.base, sides.quote, market))
        }
        return out
    }

    // MARK: Namen

    /// Volle Namen aus derselben Liste («english_name», KRW-BTC → Bitcoin); erster Treffer gilt.
    override func parseBaseNames(call: Int, body: String) throws -> [String: String] {
        var names: [String: String] = [:]
        for entry in try JArray(string: body).allObjects() {
            // leere Teile zählen mit: «-BTC» oder «KRW-» ergeben keinen Namen
            guard let sides = UpbitStyleMarket.splitMarket(entry.optString("market"), keepingEmpty: true) else { continue }
            let english = entry.optString("english_name")
            if sides.base.isEmpty || english.isEmpty || names[sides.base] != nil { continue }
            names[sides.base] = english
        }
        return names
    }

    // MARK: Lesen

    private static func copyRow(_ row: JObject, into ticker: inout Ticker, utcFields: Bool) throws {
        ticker.last = try row.double("trade_price")
        // high_price/low_price gelten seit Tagesbeginn der Börse, nicht über 24 h — darum nicht übernommen
        ticker.vol = row.optDoubleOrMissing("acc_trade_volume_24h")
        ticker.volQuote = row.optDoubleOrMissing("acc_trade_price_24h")
        // Zeit des letzten Handels (nur Anzeige); «timestamp» wäre bloss die Antwortzeit. «Veraltet» misst am Abruf
        // (`Market.readTicker`) — ein ruhiges Paar mit altem Handel ist nicht veraltet
        ticker.timestamp = utcFields
            ? utcTradeMillis(date: row.optString("trade_date"), time: row.optString("trade_time"))
            : row.optLong("trade_timestamp")
        // signed_change_rate bezieht sich auf den Vortagesschluss (KST): keine 24-h-Veränderung
    }

    /// «20261016» + «232853» (UTC) → ms; 0 (= keine Börsenzeit), wenn ein Teil fehlt, anders aufgebaut oder kein
    /// gültiger Zeitpunkt ist. Wie Android `UpbitStyleMarket.utcTradeMillis`.
    static func utcTradeMillis(date: String, time: String) -> Int64 {
        let digits = Array(date + time)
        guard date.count == 8, time.count == 6, digits.allSatisfy({ $0.isASCII && $0.isNumber }) else { return 0 }
        let n = digits.map { Int64(String($0))! }
        func number(_ from: Int, _ count: Int) -> Int64 { n[from..<from + count].reduce(0) { $0 * 10 + $1 } }
        let (year, month, day) = (number(0, 4), number(4, 2), number(6, 2))
        let (hours, minutes, seconds) = (number(8, 2), number(10, 2), number(12, 2))
        let leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
        let monthDays: [Int64] = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
        guard year >= 1, (1...12).contains(month), day >= 1, day <= monthDays[Int(month) - 1],
              hours <= 23, minutes <= 59, seconds <= 59 else { return 0 }
        // Tage seit 1970-01-01 im gregorianischen Kalender (Verfahren «days from civil», H. Hinnant)
        let y = month <= 2 ? year - 1 : year
        let era = (y >= 0 ? y : y - 399) / 400
        let yearOfEra = y - era * 400
        let dayOfYear = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1
        let dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        let epochDay = era * 146_097 + dayOfEra - 719_468
        return (epochDay * 86_400 + hours * 3_600 + minutes * 60 + seconds) * 1_000
    }

    /// Gruppen der gefilterten Abfrage — nur mit ungefiltertem Ersatz; sonst oder nicht filterbar: nil.
    private func selectionGroups(_ pairIds: [String]) -> [[String]]? {
        guard unfilteredURL != nil else { return nil }
        return BulkPairChunks.chunks(prefix: selectionPrefix, pairIds: pairIds)
    }

    /// «KRW-BTC» → (Basis BTC, Kotierung KRW); andere Form → nil. `keepingEmpty`: leere Teile zählen mit
    /// («KRW--BTC» hat dann drei Teile), sonst fallen sie weg.
    private static func splitMarket(_ market: String, keepingEmpty: Bool) -> (base: String, quote: String)? {
        let parts = market.split(separator: "-", omittingEmptySubsequences: !keepingEmpty)
        guard parts.count == 2 else { return nil }
        return (base: String(parts[1]), quote: String(parts[0]))
    }
}
