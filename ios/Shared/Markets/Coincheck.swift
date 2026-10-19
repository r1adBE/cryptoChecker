import Foundation

private let coincheckAPI = "https://coincheck.com/api"

/// Status eines Paars, das gerade nicht handelbar ist; «available» und «itayose» (Auktion) bleiben.
private let coincheckStatusStop = "stop"

/// Coincheck (Japan, JPY-Paare): https://coincheck.com/documents/exchange/api. Eine eigene Paarliste gibt es nicht;
/// `exchange_status` nennt aber jedes Paar mit seinem Zustand und dient darum als Liste (keine feste Tabelle).
/// Kennung «btc_jpy». Keine Sammelabfrage, keine 24-h-Veränderung im Ticker.
final class Coincheck: SimpleMarket {
    init() {
        super.init(
            key: "Coincheck",
            name: "Coincheck",
            pairListURL: coincheckAPI + "/exchange_status",
            tickerURL: coincheckAPI + "/ticker?pair=%1$s",
            ttsName: "Coin check",
            errorField: "error"
        )
    }

    // MARK: Einzelkurs

    override func pairId(_ info: PairQuery) -> String? { info.pairId ?? info.baseLower + "_" + info.quoteLower }

    /// `timestamp` ist laut Doku die aktuelle Zeit (Antwort), kein Handel — Zeit der Börse bleibt unbekannt.
    /// `high`/`low`/`volume` gelten über 24 Stunden.
    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        ticker.last = try json.double("last")
        ticker.bid = json.optDoubleOrMissing("bid")
        ticker.ask = json.optDoubleOrMissing("ask")
        ticker.high = json.optDoubleOrMissing("high")
        ticker.low = json.optDoubleOrMissing("low")
        ticker.vol = json.optDoubleOrMissing("volume")
    }

    // MARK: Paarliste

    override func fillPairList(call: Int, json: JObject) throws -> [MarketPair] {
        var out: [MarketPair] = []
        let entries = try json.array("exchange_status").allObjects()
        for entry in entries where entry.optString("status") != coincheckStatusStop {
            let pair = entry.optString("pair")
            let sides = pair.components(separatedBy: "_")
            if sides.count == 2 { out.append(MarketPair(sides[0].uppercased(), sides[1].uppercased(), pair)) }
        }
        return out
    }
}
