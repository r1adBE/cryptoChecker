import Foundation

private let coincheckAPI = "https://coincheck.com/api"

/// Status eines Paars, das gerade nicht handelbar ist; «available» und «itayose» (Auktion) bleiben.
private let coincheckStatusStop = "stop"

/// Coincheck (Japan, JPY-Paare): https://coincheck.com/documents/exchange/api. Eine eigene Paarliste gibt es nicht;
/// `exchange_status` nennt aber jedes Paar mit seinem Zustand und dient darum als Liste (keine feste Tabelle).
/// Kennung «btc_jpy». Keine Sammelabfrage, keine 24-h-Veränderung im Ticker.
struct Coincheck: SimpleExchange {
    let key: String = "Coincheck"
    let name: String = "Coincheck"
    let pairListURL: String = coincheckAPI + "/exchange_status"
    let tickerURL: String = coincheckAPI + "/ticker?pair=%1$s"
    let ttsName: String = "Coin check"
    let errorField: String? = "error"

    // MARK: Einzelkurs

    func pairId(_ info: PairQuery) -> String? { info.pairId ?? info.baseLower + "_" + info.quoteLower }

    /// `timestamp` ist laut Doku die aktuelle Zeit (Antwort), kein Handel — Zeit der Börse bleibt unbekannt.
    /// `high`/`low`/`volume` gelten über 24 Stunden.
    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        ticker.last = try json.double("last")
        ticker.bid = json.optDoubleOrMissing("bid")
        ticker.ask = json.optDoubleOrMissing("ask")
        ticker.high = json.optDoubleOrMissing("high")
        ticker.low = json.optDoubleOrMissing("low")
        ticker.vol = json.optDoubleOrMissing("volume")
    }

    // MARK: Paarliste

    func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        let entries = try json.array("exchange_status").allObjects()
        for entry in entries where entry.optString("status") != coincheckStatusStop {
            let pair = entry.optString("pair")
            let sides = pair.components(separatedBy: "_")
            if sides.count == 2 { out.append(ExchangePair(sides[0].uppercased(), sides[1].uppercased(), pair)) }
        }
        return out
    }
}
