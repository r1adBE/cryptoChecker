import Foundation

private let bitbankAPI = "https://public.bitbank.cc"

/// Alle Paare in einer Antwort; dient auch als Paarliste (dieselbe Adresse, kein zweiter Host).
private let bitbankTickers = bitbankAPI + "/tickers"

/// Fehlercode «zu viele Anfragen» — bitbank schickt nur Codes, keinen Text.
private let bitbankTooManyRequests = 10009

/// bitbank führt Bitcoin Cash als «bcc»; angezeigt und verglichen wird das übliche Kürzel (wie Luno XBT → BTC).
private let bitbankCommon: [String: String] = ["BCC": "BCH"]

/// bitbank (Japan, JPY-Paare), öffentliche API: https://github.com/bitbankinc/bitbank-api-docs. Kennung «btc_jpy».
/// Jede Antwort steckt in {"success":1,"data":…}; Fehler sind {"success":0,"data":{"code":…}} ohne Text.
final class Bitbank: SimpleExchange, @unchecked Sendable { // @unchecked: wie `Exchange` (keine veränderlichen Eigenschaften)
    init() {
        super.init(
            key: "Bitbank",
            name: "bitbank",
            pairListURL: bitbankTickers,
            tickerURL: bitbankAPI + "/%1$s/ticker",
            ttsName: "bit bank"
        )
    }

    // MARK: Einzelkurs

    override func pairId(_ info: PairQuery) -> String? {
        info.pairId ?? Bitbank.bitbankCode(info.base) + "_" + Bitbank.bitbankCode(info.quote)
    }

    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try copyRow(json.object("data"), into: &ticker)
    }

    /// Nur ein Code. Der Text für «zu viele Anfragen» enthält «Too many requests», damit der Bericht ihn als
    /// Bremsung erkennt (RateLimitSignal); andere Codes erscheinen als «Error code …».
    override func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        if json.optInt("success", 1) != 0 { try noErrorText() }
        let code = json.optObject("data")?.optInt("code", 0) ?? 0
        if code <= 0 { try noErrorText() }
        return code == bitbankTooManyRequests ? "Too many requests (\(code))" : "Error code \(code)"
    }

    // MARK: Sammelabfrage

    override var bulkTickersNumOfRequests: Int { 1 }

    /// `/tickers` ist als Liste aller Paare dokumentiert.
    override var bulkTickersComplete: Bool { true }

    override func bulkTickersURL(call: Int) -> String? { bitbankTickers }

    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JObject(string: body).array("data").allObjects() {
            bulkRowIfActive(row.optString("pair"), row: row, priceField: "last", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    /// Aus `/tickers`: jedes Paar mit Kennung «basis_gegenwährung».
    override func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for row in try json.array("data").allObjects() {
            let pair = row.optString("pair")
            let sides = pair.components(separatedBy: "_")
            if sides.count == 2 { out.append(ExchangePair(Bitbank.common(sides[0]), Bitbank.common(sides[1]), pair)) }
        }
        return out
    }

    // MARK: Lesen

    /// Ein Ticker. `timestamp` ist die Zeit der Momentaufnahme, kein Handel — Zeit der Börse bleibt unbekannt.
    /// `sell`/`buy` = bestes Angebot/Gebot.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        let last = try row.double("last")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("buy")
        ticker.ask = row.optDoubleOrMissing("sell")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("vol")
        // «open» laut Doku: Eröffnung vor 24 Stunden (gleitend)
        ticker.change24hPercent = Change24h.fromOpen(last: last, open: row.optDouble("open"))
    }

    private static func common(_ code: String) -> String {
        let upper = code.uppercased()
        return bitbankCommon[upper] ?? upper
    }

    /// Umkehrung von `common` für die Kennung ohne gespeicherte Paar-ID («bcc_jpy»), klein geschrieben.
    private static func bitbankCode(_ code: String) -> String {
        let upper = code.uppercased()
        return (bitbankCommon.first { $0.value == upper }?.key ?? code).lowercased()
    }
}
