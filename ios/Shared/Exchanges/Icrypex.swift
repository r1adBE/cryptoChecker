import Foundation

private let icrypexAPI = "https://api.icrypex.com/v1"

/// Alle Ticker in einer Antwort; einen Einzelabruf je Paar gibt es nicht (`/v1/ticker?symbol=` antwortet 404).
private let icrypexTickers = icrypexAPI + "/tickers"

/// Nur Spot-Märkte; dieselbe Liste führt Perpetuals («BTCUSDT/P», `PERPETUAL`).
private let icrypexSpot = "SPOT"

/// Handelbar; `CancelOnly` erlaubt nur noch das Löschen von Aufträgen und fällt weg.
private let icrypexRunning = "Running"

/// Icrypex (Türkei), öffentliche API — Kennung = `symbol` ohne Trennzeichen («BTCUSDT»), im Ticker ebenso. Die
/// Paarliste kommt aus `exchange/info` (`pairs` mit `base`, `quote`, `status`, `marketTypes`): nur sie sagt, was Spot
/// ist und was gehandelt wird, und führt auch Gegenwährungen wie ICPX, die sich aus dem Symbol allein nicht sicher
/// abtrennen liessen. Perpetuals tragen «/P» im Symbol und werden nirgends übernommen.
final class Icrypex: SimpleExchange, @unchecked Sendable { // @unchecked: wie `Exchange` (keine veränderlichen Eigenschaften)
    init() {
        super.init(
            key: "Icrypex",
            name: "Icrypex",
            pairListURL: icrypexAPI + "/exchange/info",
            tickerURL: icrypexTickers
        )
    }

    // MARK: Einzelkurs: aus der Gesamtliste das eigene Paar

    override func pairId(_ info: PairQuery) -> String? { info.pairId ?? info.base + info.quote }

    /// Eine Spot-Kennung enthält nie «/», darum trifft der Vergleich nie die Perpetual-Zeile desselben Coins.
    override func fillTicker(call: Int, body: String, ticker: inout Ticker, info: PairQuery) throws {
        let id = pairId(info)
        guard let row = try JArray(string: body).allObjects().first(where: { $0.optString("symbol") == id }) else {
            throw JSONError(message: JSONError.noData)
        }
        try copyRow(row, into: &ticker)
    }

    // MARK: Sammelabfrage

    override var bulkTickersNumOfRequests: Int { 1 }

    /// `tickers` führt jedes Spot-Paar (Aufzeichnung 19.10.2026: alle 211 Paare von `exchange/info`, auch
    /// `CancelOnly`), ein fehlendes Paar ist nicht mehr gelistet.
    override var bulkTickersComplete: Bool { true }

    override func bulkTickersURL(call: Int) -> String? { icrypexTickers }

    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        var tickers: [String: Ticker] = [:]
        for row in try JArray(string: body).allObjects() {
            let id = row.optString("symbol")
            // Perpetual («…/P»): keine Spot-Zeile, still übergehen statt als unlesbar zu zählen
            if Icrypex.isPerpetual(id) { continue }
            bulkRowIfActive(id, row: row, priceField: "last", tickers: &tickers, failed: &failed) {
                try copyRow(row, into: &$0)
            }
        }
        return tickers
    }

    // MARK: Paarliste

    /// Nur Spot-Märkte im Status `Running`; Basis und Gegenwährung stehen getrennt in der Antwort.
    override func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] {
        var out: [ExchangePair] = []
        for entry in try json.array("pairs").allObjects() {
            guard entry.optString("status") == icrypexRunning,
                  entry.optStrings("marketTypes").contains(icrypexSpot) else { continue }
            let id = try entry.string("symbol")
            if Icrypex.isPerpetual(id) { continue }
            out.append(ExchangePair(try entry.string("base").uppercased(), try entry.string("quote").uppercased(), id))
        }
        return out
    }

    // MARK: Lesen

    /// Eine Ticker-Zeile, alle Zahlen als Text. `volume` zählt in der Basiswährung (BTCUSDT: «25.87»; als USDT wäre
    /// das bei diesem Kurs unmöglich wenig). Nicht übernommen: `change` (Prozent, `qty` = Veränderung absolut) — der
    /// Zeitraum ist nicht dokumentiert; Hoch/Tief/`avg` passen zwar zu 24 h, Zeilen ohne Eröffnungskurs melden aber
    /// `change` 0 bei `qty` = `last`. Die App rechnet dann mit Kerzen. Eine Zeit der Börse liefert Icrypex nicht.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        ticker.last = try row.double("last")
        ticker.bid = row.optDoubleOrMissing("bid")
        ticker.ask = row.optDoubleOrMissing("ask")
        ticker.high = row.optDoubleOrMissing("high")
        ticker.low = row.optDoubleOrMissing("low")
        ticker.vol = row.optDoubleOrMissing("volume")
    }

    private static func isPerpetual(_ id: String) -> Bool { id.contains("/") }
}
