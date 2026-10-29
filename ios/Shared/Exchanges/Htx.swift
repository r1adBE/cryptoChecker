import Foundation

private let htxSpotAPI = "https://api.huobi.pro"
private let htxSwapAPI = "https://api.hbdm.com"

/// HTX (vormals Huobi) Spot. Kennung = `sc` («btcusdt»).
struct Htx: HtxBase {
    let key: String = "Htx"
    let name: String = "HTX"
    let pairListURL: String = htxSpotAPI + "/v2/settings/common/symbols"
    let tickerURL: String = htxSpotAPI + "/market/detail/merged?symbol=%1$s"
    let errorField: String? = "err-msg"
    let allTickersURL: String = htxSpotAPI + "/market/tickers"
    let rowsField: String = "data"
    let idField: String = "symbol"
    let turnoverField: String = "vol"
    let singleHighLowRolling: Bool = true

    // MARK: Paarliste

    func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for entry in try json.array("data").allObjects() where entry.optString("state") == "online" {
            // bc = Coin, qc = Gegenwährung, sc = Kennung
            out.append(ExchangePair(try entry.string("bc").uppercased(), try entry.string("qc").uppercased(),
                                        try entry.string("sc")))
        }
        return out
    }
}

/// HTX USDT-Perpetuals (linear swap). Kennung = `contract_code` («BTC-USDT»).
struct HtxFutures: HtxBase {
    /// Abschnitt «Futures» der Börsenauswahl (`ExchangeCategory`). Android gleich.
    var category: ExchangeCategory { .futures }

    let key: String = "HtxFutures"
    let name: String = "HTX Futures"
    let pairListURL: String = htxSwapAPI + "/linear-swap-api/v1/swap_contract_info?business_type=swap"
    let tickerURL: String = htxSwapAPI + "/linear-swap-ex/market/detail/merged?contract_code=%1$s"
    let errorField: String? = "err_msg"
    let allTickersURL: String = htxSwapAPI + "/linear-swap-ex/market/detail/batch_merged?business_type=swap"
    let rowsField: String = "ticks"
    let idField: String = "contract_code"
    let turnoverField: String = "trade_turnover"
    let singleHighLowRolling: Bool = false

    // MARK: Paarliste

    func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for entry in try json.array("data").allObjects() {
            // contract_status 1 = gelistet und handelbar
            guard entry.optInt("contract_status", -1) == 1 else { continue }
            let code = try entry.string("contract_code")
            let sides = code.components(separatedBy: "-")
            guard sides.count == 2 else { continue }
            out.append(ExchangePair(sides[0], sides[1], code, .perpetual,
                                        tradFi: TradFi.htx(labels: HtxFutures.tradFiLabels(entry))))
        }
        return out
    }

    // MARK: Lesen

    /// `tradfi_labels` kommt als Liste oder als Text; nicht leer = Aktie, Rohstoff … (TradFi).
    private static func tradFiLabels(_ entry: JObject) -> [String] {
        entry.optArray("tradfi_labels") == nil ? [entry.optString("tradfi_labels")] : entry.optStrings("tradfi_labels")
    }
}

/// Gemeinsames von Spot und Swap: gleiche Ticker-Felder unter `tick`, Sammelabfrage als Liste `rowsField` mit der
/// Kennung in `idField`. Den Umsatz in der Quote nennt `turnoverField`: Spot `vol`, Swap `trade_turnover` — beim Swap
/// ist `vol` die Zahl der Kontrakte, also nie Ersatz. Die Paarliste liest jede der beiden Börsen selbst.
/// Hoch/Tief: Die Sammelabfragen (Spot `market/tickers`, Swap `batch_merged`) und der Swap-Einzelkurs zählen sie seit
/// Tagesbeginn UTC+8, nicht über 24 h — nur der Spot-Einzelkurs (`detail/merged`, rollend) liefert sie
/// (`singleHighLowRolling`).
protocol HtxBase: SimpleExchange {
    /// Sammelabfrage (alle Paare).
    var allTickersURL: String { get }
    /// Liste der Sammelabfrage.
    var rowsField: String { get }
    /// Kennung in einer Zeile der Sammelabfrage.
    var idField: String { get }
    /// Umsatz in der Quote.
    var turnoverField: String { get }
    /// Hoch/Tief des Einzelkurses gelten über 24 h (nur Spot).
    var singleHighLowRolling: Bool { get }
}

extension HtxBase {
    // MARK: Einzelkurs

    /// `ts` der Hülle ist die Antwortzeit, kein Handel → Zeit der Börse unbekannt.
    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        try copyRow(json.object("tick"), into: &ticker, withHighLow: singleHighLowRolling)
    }

    // MARK: Sammelabfrage

    var bulkTickersNumOfRequests: Int { 1 }

    var bulkTickersComplete: Bool { true }

    func bulkTickersURL(call: Int) -> String? { allTickersURL }

    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JObject(string: body).array(rowsField).allObjects() {
            bulkRowIfActive(row.optString(idField), row: row, priceField: "close", tickers: &tickers,
                            failed: &failed) { try copyRow(row, into: &$0, withHighLow: false) }
        }
        return tickers
    }

    // MARK: Lesen

    /// Keine 24-h-Veränderung: «open» ist laut Doku der Sammelabfrage die Eröffnung des Kalendertags (Singapur) —
    /// Einzel- und Sammelabruf wären sonst uneinheitlich. `ts` ist die Momentaufnahme, nicht der letzte Handel.
    private func copyRow(_ row: JObject, into ticker: inout Ticker, withHighLow: Bool) throws {
        ticker.last = try row.double("close")
        // Hoch/Tief seit Tagesbeginn (UTC+8) gehörten zu einem anderen Zeitraum als die 24-h-Werte: nicht übernommen
        if withHighLow {
            ticker.high = row.optDoubleOrMissing("high")
            ticker.low = row.optDoubleOrMissing("low")
        }
        ticker.vol = row.optDoubleOrMissing("amount")
        ticker.volQuote = row.optDoubleOrMissing(turnoverField)
        ticker.bid = Self.bestPrice(row, "bid")
        ticker.ask = Self.bestPrice(row, "ask")
    }

    /// Geld/Brief kommen als Zahl oder als [Preis, Menge].
    private static func bestPrice(_ row: JObject, _ field: String) -> Double {
        guard let level = row.optArray(field) else { return row.optDoubleOrMissing(field) }
        return level.optDouble(0, Ticker.missing)
    }
}
