import Foundation

/// Logos der Börsen in den Listen («Paar hinzufügen»): Bild-Adressen (Feld `image`) aus der öffentlichen Börsenliste von
/// CoinGecko — Seite 1 ist dieselbe Abfrage wie für die Reihenfolge (`ExchangeOrder.spotURL`); fehlen zugeordnete
/// Börsen, folgen weitere Seiten (`needsNextPage`, höchstens `maxPages`), für bei CoinGecko inaktive Börsen ohne Eintrag
/// in der Liste die Einzelseite (`detailURL`). Für alle dieselben Abfragen in fester Reihenfolge. Futures zeigen das Logo
/// der Mutterbörse (Binance Futures → Binance). DexScreener und die EZB-Referenzkurse bekommen ein festes Symbol
/// (`Symbol`); Börsen ohne Eintrag bei CoinGecko behalten den Kreis mit den Buchstaben (`noLogo`). Geladen wird über die
/// Pipeline der Coin-Logos unter dem Schlüssel `logoKey` (`CoinLogos.exchangePrefix`). Reine Logik — wie Android
/// `ExchangeLogos` (`testdata/parity/exchange_logos.json`).
enum ExchangeLogos {
    /// Festes Symbol statt Logo.
    enum Symbol: String { case dex = "DEX", forex = "FOREX" }

    /// Börsen-Schlüssel → Kennung in der Börsenliste (`pageURL`); Futures: die Mutterbörse. Feste Reihenfolge.
    static let spotIds: [(key: String, id: String)] = [
        ("Coinbase", "gdax"), ("Kraken", "kraken"), ("GateIo", "gate"), ("Binance", "binance"),
        ("BinanceUs", "binance_us"), ("Kucoin", "kucoin"), ("Okex", "okex"), ("OkexFutures", "okex"),
        ("BinanceFutures", "binance"), ("Bybit", "bybit_spot"), ("BybitFutures", "bybit_spot"),
        ("Bitget", "bitget"), ("BitgetFutures", "bitget"), ("Mexc", "mxc"), ("MexcFutures", "mxc"),
        ("Htx", "huobi"), ("HtxFutures", "huobi"), ("Bitfinex", "bitfinex"), ("Bitstamp", "bitstamp"),
        ("Bitvavo", "bitvavo"), ("CryptoCom", "crypto_com"), ("Gemini", "gemini"), ("Hyperliquid", "hyperliquid-spot"),
        ("Aster", "aster-spot"),
        ("BingX", "bingx"), ("BingXFutures", "bingx"), ("Lighter", "lighter-spot"), ("Woo", "wootrade"),
        ("WooFutures", "wootrade"), ("Deribit", "deribit_spot"), ("Phemex", "phemex"), ("PhemexFutures", "phemex"),
        ("BitunixFutures", "bitunix"), ("Poloniex", "poloniex"), ("OneTrading", "bitpanda"), ("Upbit", "upbit"),
        ("Bithumb", "bithumb"), ("BitFlyer", "bitflyer"), ("Bitbank", "bitbank"), ("Coincheck", "coincheck"),
        ("Btcturk", "btcturk"), ("Paribu", "paribu"), ("Icrypex", "icrypex"), ("Bitso", "bitso"),
        ("MercadoBitcoin", "mercado_bitcoin"), ("Indodax", "indodax"), ("ZebPay", "zebpay"), ("CoinDcx", "coindcx"),
        ("HashKey", "hashkey"), ("IndependentReserve", "independent_reserve"), ("BtcMarkets", "btcmarkets"),
        ("Luno", "luno"), ("Bitkub", "bitkub"), ("BinanceTh", "binance"), ("CoinsPh", "coinspro"),
        ("Coinone", "coinone"), ("WhiteBit", "whitebit"), ("MaxMaicoin", "max_maicoin"),
        ("BitoPro", "bitopro"), ("BinanceTr", "binance"), ("Foxbit", "foxbit"), ("Bit2Me", "bit2me"),
        ("Valr", "valr"), ("WazirX", "wazirx"), ("Buda", "buda"),
        ("Tokocrypto", "toko_crypto"), ("UpbitTh", "upbit"), ("UpbitId", "upbit_indonesia"),
        ("CoinJar", "coinjar"), ("GmoCoin", "gmo_japan"), ("Latoken", "latoken"), ("NonKyc", "nonkyc_io"),
    ]

    /// Festes Symbol statt Logo.
    static let symbols: [String: Symbol] = ["DexScreener": .dex, "EcbReference": .forex]

    /// Bewusst ohne Logo (der Kreis mit den Buchstaben bleibt), weil CoinGecko sie nicht führt; «bitpanda» bei CoinGecko
    /// ist One Trading.
    static let noLogo: Set<String> = ["Bitpanda", "CoinSpot", "Quidax", "Swyftx", "Ndax", "BitOasis"]

    /// Einträge je Seite der Börsenliste (wie `ExchangeOrder.spotURL`).
    static let perPage = 250
    /// Höchstens so viele Seiten (CoinGecko führt rund 950 aktive Börsen).
    static let maxPages = 5
    /// Unvollständig (Seite fehlte, Server bremste): frühestens nach so langer Zeit erneut versuchen.
    static let retryMillis: Int64 = 24 * 60 * 60 * 1000

    /// Seite `page` der Börsenliste; Seite 1 = `ExchangeOrder.spotURL`.
    static func pageURL(_ page: Int) -> String { "https://api.coingecko.com/api/v3/exchanges?per_page=\(perPage)&page=\(page)" }

    /// Einzelseite einer Börse (für Börsen, die in der Liste fehlen).
    static func detailURL(_ id: String) -> String { "https://api.coingecko.com/api/v3/exchanges/" + id }

    private static let idByKey = Dictionary(spotIds.map { ($0.key, $0.id) }, uniquingKeysWith: { first, _ in first })

    /// Schlüssel des Logos (Datei `CoinLogos.fileName`); mehrere Börsen teilen eines. nil = keins.
    static func logoKey(_ exchangeKey: String) -> String? { idByKey[exchangeKey].map { CoinLogos.exchangePrefix + $0 } }

    /// Zugeordnete Kennungen in fester Reihenfolge (Reihenfolge der Abrufe und Einzelseiten, für alle gleich).
    static let ids: [String] = {
        var seen = Set<String>()
        return spotIds.map(\.id).filter { seen.insert($0).inserted }
    }()

    /// Alle Logo-Schlüssel in derselben Reihenfolge.
    static let order: [String] = ids.map { CoinLogos.exchangePrefix + $0 }

    /// Kennung → Bild-Adresse einer Seite (nur freigegebene, `CoinLogos.isAllowedURL`) und Zahl der Einträge.
    struct Page: Equatable {
        let images: [String: String]
        let rows: Int
    }

    /// Eine Seite der Börsenliste lesen; unlesbar → Fehler.
    static func parsePage(_ body: String) throws -> Page {
        let rows = try JArray(string: body)
        var out: [String: String] = [:]
        for row in rows.objects {
            let id = row.optString("id")
            if out[id] == nil, let url = imageOf(row) { out[id] = url }
        }
        return Page(images: out, rows: rows.count)
    }

    /// Bild-Adresse der Einzelseite (`detailURL`); keine oder nicht freigegeben → nil, unlesbar → Fehler.
    static func parseDetail(_ body: String) throws -> String? { imageOf(try JObject(string: body)) }

    private static func imageOf(_ row: JObject) -> String? {
        let url = row.optString("image").trimmingCharacters(in: .whitespacesAndNewlines)
        return CoinLogos.isAllowedURL(url) ? url : nil
    }

    /// Noch fehlende Kennungen in fester Reihenfolge.
    static func missing(_ found: [String: String]) -> [String] { ids.filter { found[$0] == nil } }

    /// Nach Seite `page` mit `rows` Einträgen weiterlesen? Nur solange Kennungen fehlen und die Liste weitergeht.
    static func needsNextPage(page: Int, rows: Int, missing: Int) -> Bool {
        missing > 0 && rows >= perPage && page < maxPages
    }

    /// Gefundene Bilder (`found`: Kennung → Adresse) als Logo-Schlüssel → Adresse.
    static func images(_ found: [String: String]) -> [String: String] {
        Dictionary(ids.compactMap { id in found[id].map { (CoinLogos.exchangePrefix + id, $0) } },
                   uniquingKeysWith: { first, _ in first })
    }

    /// Gespeichert sind nicht alle: nach `retryMillis` seit dem letzten Versuch erneut (nie öfter).
    static func retryDue(complete: Bool, attemptAt: Int64, now: Int64) -> Bool {
        !complete && (attemptAt <= 0 || attemptAt > now || now - attemptAt >= retryMillis)
    }
}
