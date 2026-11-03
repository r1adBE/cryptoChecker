import Foundation

private let bitOasisAPI = "https://api.bitoasis.net/v1/exchange"

/// BitOasis (VAE, AED-Paare), öffentliche API — Kennung wie «BTC-AED». Keine Sammelabfrage; die Paarliste steht je
/// Coin unter `tokens[].available_pairs` (nur `trade_enabled`). Fehler als `{"status": "error", "message"}`.
struct BitOasis: SimpleExchange {
    let key: String = "BitOasis"
    let name: String = "BitOasis"
    let pairListURL: String = bitOasisAPI + "/markets"
    let tickerURL: String = bitOasisAPI + "/ticker/%1$s"
    let errorField: String? = "message"

    // MARK: Einzelkurs

    func pairId(_ info: PairQuery) -> String? {
        info.pairId ?? info.base.uppercased() + "-" + info.quote.uppercased()
    }

    /// `ticker`: `open_price` = Kurs vor 24 h (gleitend: er wandert stündlich mit, BTC-USDT gegen Binance geprüft),
    /// Hoch/Tief über dasselbe Fenster. Kein Volumen, keine Handelszeit.
    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        let row = try json.object("ticker")
        let last = try row.double("last_price")
        ticker.last = last
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.high = row.optDoubleOrMissing("daily_high")
        ticker.low = row.optDoubleOrMissing("daily_low")
        ticker.change24hPercent = Change24h.fromOpen(last: last, open: row.optDouble("open_price"))
    }

    // MARK: Paarliste

    /// Je handelbarem Coin seine Paare «BASE-QUOTE».
    func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for token in try json.array("tokens").allObjects() where token.optBool("trade_enabled", false) {
            for id in token.optArray("available_pairs")?.strings ?? [] {
                let parts = id.split(separator: "-", omittingEmptySubsequences: false).map(String.init)
                if parts.count == 2, parts.allSatisfy({ !$0.isEmpty }) {
                    out.append(ExchangePair(parts[0].uppercased(), parts[1].uppercased(), id))
                }
            }
        }
        return out
    }
}
