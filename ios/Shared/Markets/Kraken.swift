import Foundation

/// Öffentliche REST-Schnittstelle von Kraken (docs.kraken.com, «Market Data»).
private let krakenPublic = "https://api.kraken.com/0/public"

/// Ticker ohne Filter: alle handelbaren Paare.
private let krakenTicker = krakenPublic + "/Ticker"

/// Ticker mit Paarliste («?pair=A,B,C»).
private let krakenTickerFor = krakenTicker + "?pair="

/// AssetPairs-`status` eines handelbaren Paars; andere Werte (z. B. «cancel_only») stehen nicht zur Wahl.
private let krakenStatusOnline = "online"

/// Grund, wenn `result` leer ist und Kraken keinen eigenen Fehler nennt.
private let krakenEmptyResult = "Empty result"

/// Kraken Spot: Paare aus AssetPairs, Kurse aus Ticker (Einzelpaar, Auswahl oder alle).
final class Kraken: SimpleMarket {
    init() {
        super.init(
            key: "Kraken",
            name: "Kraken",
            pairListURL: krakenPublic + "/AssetPairs",
            tickerURL: krakenTickerFor + "%1$s"
        )
    }

    // MARK: Einzelkurs

    override func pairId(_ info: PairQuery) -> String? {
        info.pairId ?? KrakenAssetCodes.krakenCode(info.base) + KrakenAssetCodes.krakenCode(info.quote)
    }

    override func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {
        let result = try json.object("result")
        // Eine Anfrage = ein Paar: Der einzige Schlüssel ist dessen Kennung; ohne Schlüssel kein Kurs
        guard let onlyKey = result.keys.first else { throw JSONError(message: krakenEmptyResult) }
        let row = try result.object(onlyKey)
        // Schlusskurs 0 (z. B. «cancel_only»): kein Kurs statt 0 — sonst bliebe der alte Kurs still stehen.
        // Ohne Kurs bestätigt die Einzelabfrage «nicht mehr gehandelt» (NotTraded.confirmedBySingle).
        if Kraken.isDormant(row) { return }
        try copyRow(row, into: &ticker)
    }

    override func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        try json.array("error").string(0)
    }

    // MARK: Sammelabfrage

    override var bulkTickersNumOfRequests: Int { 1 }

    /// Gefiltert kommen alle angefragten Paare (sonst lehnt Kraken ab), ungefiltert alle handelbaren. Fehlt eines,
    /// prüft die Einzelabfrage («EQuery:Unknown asset pair» → nicht mehr gehandelt, BulkDelisted).
    override var bulkTickersComplete: Bool { true }

    override func bulkTickersURL(call: Int) -> String? { krakenTicker }

    /// Beobachtete Paare gefiltert, bei langer Liste auf mehrere URLs (< 2000 Zeichen) verteilt.
    override func bulkTickersURL(call: Int, pairIds: [String]) -> String? {
        guard let groups = BulkPairChunks.chunks(prefix: krakenTickerFor, pairIds: pairIds) else { return krakenTicker }
        return call < groups.count ? BulkPairChunks.url(prefix: krakenTickerFor, chunk: groups[call]) : nil
    }

    override func bulkTickersRequestCount(pairIds: [String]) -> Int {
        BulkPairChunks.chunks(prefix: krakenTickerFor, pairIds: pairIds)?.count ?? bulkTickersNumOfRequests
    }

    override func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] {
        let root = try JObject(string: body)
        let rows = try root.optObject("result")?.allNamedObjects() ?? []
        guard !rows.isEmpty else {
            // Ein unbekanntes Paar lässt die ganze gefilterte Anfrage scheitern — dann kommt die ungefilterte
            let reason = root.optArray("error")?.optString(0) ?? ""
            throw JSONError(message: reason.isEmpty ? krakenEmptyResult : reason)
        }
        var tickers: [String: Ticker] = [:]
        for (pairId, row) in rows {
            // Leerer Schlusskurs («c» null oder [0/""/null, …]): ruhender Markt, kein Lesefehler
            if Kraken.isDormant(row) { continue }
            bulkRow(pairId, tickers: &tickers, failed: &failed) { try copyRow(row, into: &$0) }
        }
        return tickers
    }

    // MARK: Paarliste

    override func fillPairList(call: Int, json: JObject) throws -> [MarketPair] {
        var out: [MarketPair] = []
        // Kennungen mit Punkt (z. B. «.d») sind Varianten, keine eigenen Märkte
        for (pairId, asset) in try json.object("result").allNamedObjects() where !pairId.contains(".") {
            // Basis/Gegenwährung aus `wsname` («XBT/USD»), nicht aus `base`/`quote` mit alten Präfixen (KrakenAssetCodes)
            // Nur handelbare Paare («online»); ältere Antworten ohne `status` gelten als handelbar
            let status = asset.optString("status")
            if !status.isEmpty && status != krakenStatusOnline { continue }
            guard let codes = KrakenAssetCodes.fromWsName(asset.optString("wsname")) else { continue }
            out.append(MarketPair(codes.base, codes.quote, pairId))
        }
        return out
    }

    // MARK: Lesen

    /// Einzel- und Sammelabfrage: je Paar dieselben Listenfelder.
    private func copyRow(_ row: JObject, into ticker: inout Ticker) throws {
        // c/b/a: [Kurs, …]
        ticker.last = try Kraken.slot(row, "c", 0)
        ticker.bid = try Kraken.slot(row, "b", 0)
        ticker.ask = try Kraken.slot(row, "a", 0)
        // h/l/v: [seit 00:00 UTC, letzte 24 h] — angezeigt werden 24-h-Werte
        ticker.high = try Kraken.slot(row, "h", 1)
        ticker.low = try Kraken.slot(row, "l", 1)
        ticker.vol = try Kraken.slot(row, "v", 1)
        // «o» ist die Tageseröffnung (UTC), nicht der Kurs vor 24 h — darum keine 24-h-Veränderung
    }

    /// Der Schlusskurs «c» ist leer — als Feld oder als erster Listeneintrag (Einzel- und Sammelabfrage).
    private static func isDormant(_ row: JObject) -> Bool {
        if BulkTickerRows.inactivePrice(row, "c") { return true }
        guard let close = row.optArray("c") else { return false }
        return BulkTickerRows.inactivePrice(close, 0)
    }

    /// Zahl an Position `index` der Liste `key`; Liste fehlt oder zu kurz: «keine Daten» statt 0.
    private static func slot(_ row: JObject, _ key: String, _ index: Int) throws -> Double {
        guard let values = row.optArray(key), index < values.count else { return Ticker.missing }
        return try values.double(index)
    }
}

/// Kraken-Kürzel ↔ gebräuchliche Kürzel.
///
/// Basis und Gegenwährung kommen aus Krakens eigenem Feld `wsname` («XBT/USD») der AssetPairs: Es trennt die beiden
/// Kürzel schon und trägt keine alten «X»/«Z»-Präfixe (`base` «XXBT», `quote` «ZUSD»), darum braucht es keine eigene
/// Liste der Präfix-Assets. Übrig bleiben die zwei Eigennamen, die Kraken heute noch führt (XBT, XDG).
/// Wie `KrakenAssetCodes.kt`.
enum KrakenAssetCodes {
    /// Krakens Eigennamen → übliche Kürzel.
    private static let commonByKraken = ["XBT": "BTC", "XDG": "DOGE"]

    /// Umkehrung für Paar-Kennungen, die aus Kürzeln zusammengesetzt werden.
    private static let krakenByCommon = Dictionary(uniqueKeysWithValues: commonByKraken.map { ($0.value, $0.key) })

    /// «XBT/USD» → BTC und USD. nil, wenn `wsname` nicht genau zwei nicht leere Kürzel enthält — das Paar fällt dann
    /// weg, statt mit einem rohen Kürzel wie «XXBT» als eigene Währung aufzutauchen.
    static func fromWsName(_ wsName: String) -> (base: String, quote: String)? {
        let parts = wsName.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 2, !parts.contains(where: \.isEmpty) else { return nil }
        return (common(parts[0]), common(parts[1]))
    }

    /// Kraken-Kürzel → übliches Kürzel (XBT → BTC, XDG → DOGE); sonst unverändert.
    static func common(_ code: String) -> String { commonByKraken[code] ?? code }

    /// Übliches Kürzel → Kraken-Kürzel für Paar-Kennungen (BTC → XBT, DOGE → XDG); sonst unverändert.
    static func krakenCode(_ code: String) -> String { krakenByCommon[code] ?? code }
}
