#if DEBUG
import Foundation

/// Antworten der Quellen hinter Markt-Tab und Portfolio für die Bildschirmfoto-Tour (`UITestExchange.marketData`) —
/// im Format der echten Dienste, Werte aus `UITestExchange+Series.swift`; gleich in `FakeMarketData.kt` (Android):
///  - CoinGecko: `/global` (Gesamtmarkt, Dominanz) und `/coins/markets` (Rangliste: Marktbreite, Gewinner/Verlierer,
///    «Heute auffällig», Logos)
///  - alternative.me: Fear & Greed (31 Tage)
///  - Coin Metrics: BTC-Verlauf seit 2016 und On-Chain-Werte (Zyklus-Vergleich, Kennzahlen)
///  - Binance USDⓈ-M: Funding und Open Interest (Pulse, «Heute auffällig»)
///  - mempool.space und die Ethereum-/EVM-Knoten: Gebühren und Blockhöhe (Gas-Karte, Halving)
///  - Binance-Website: Symbolliste mit vollen Namen — damit gilt der Binance-Kurs im Portfolio als derselbe Token
///    (sonst «Kurs ungeprüft»)
///  - Kraken: Tageskerzen USDT/USD (Portfolio-Verlauf und Einstand zum USDT-Kurs des Tages statt «USDT = 1»)
/// Binance-Spiegel (Kurse, 24-h-Ticker, Kerzen) und Coinbase beantwortet `UITestExchange.answer`. Der Daten-Spiegel
/// auf GitHub bleibt 404: Seine Dateien gelten nur mit gültig signiertem Manifest — die App holt dann direkt beim Anbieter.
extension UITestExchange {

    /// Antwort einer Markt-Quelle auf `url`; nil = 404.
    static func marketAnswer(_ url: URL) -> String? {
        let path = url.path
        switch url.host?.lowercased() {
        case "api.coingecko.com":
            switch path {
            case "/api/v3/global": return coinGeckoGlobal()
            case "/api/v3/coins/markets": return coinGeckoMarkets(url)
            default: return nil
            }
        case "api.alternative.me":
            return path.hasPrefix("/fng") ? fearGreed(url) : nil
        case "community-api.coinmetrics.io":
            return path == "/v4/timeseries/asset-metrics" ? coinMetrics(url) : nil
        case "fapi.binance.com":
            switch path {
            case "/fapi/v1/premiumIndex": return premiumIndex(url)
            case "/fapi/v1/openInterest": return openInterest(url)
            // Kein Kontrakt mit abweichendem Intervall: alle 8 Std.
            case "/fapi/v1/fundingInfo": return "[]"
            default: return nil
            }
        case "mempool.space":
            switch path {
            case "/api/v1/fees/recommended":
                return #"{"fastestFee":6,"halfHourFee":4,"hourFee":3,"economyFee":2,"minimumFee":1}"#
            case "/api/blocks/tip/height": return String(blockHeight)
            case "/api/v1/difficulty-adjustment":
                return #"{"progressPercent":41.2,"difficultyChange":1.8,"remainingBlocks":1185,"timeAvg":589000}"#
            default: return nil
            }
        case "www.binance.com":
            return path == "/bapi/composite/v1/public/marketing/symbol/list" ? binanceSymbolList() : nil
        case "api.kraken.com":
            return path == "/0/public/OHLC" ? krakenUsdtUsd(url) : nil
        case let host?:
            return gas[host].map(feeHistory)
        case nil:
            return nil
        }
    }

    // MARK: CoinGecko

    private static func coinGeckoGlobal() -> String {
        func share(_ symbol: String) -> Double {
            (tourCoin(symbol)?.marketCap ?? 0) / totalMarketCap * 100
        }
        // Beträge auch in den Anzeigewährungen (Kurse wie die Umrechnung in der App ungefähr)
        let fx: [(String, Double)] = [("usd", 1.0), ("eur", 0.92), ("chf", 0.88), ("gbp", 0.78), ("jpy", 150.0)]
        func amounts(_ usd: Double) -> String {
            "{" + fx.map { "\"\($0.0)\":\(usd * $0.1)" }.joined(separator: ",") + "}"
        }
        let shares = "{\"btc\":\(share("BTC")),\"eth\":\(share("ETH")),\"usdt\":\(share("USDT"))}"
        let fields: [String] = [
            #""active_cryptocurrencies":17500,"markets":1250"#,
            "\"total_market_cap\":\(amounts(totalMarketCap))",
            "\"total_volume\":\(amounts(totalVolume))",
            "\"market_cap_percentage\":\(shares)",
            "\"market_cap_change_percentage_24h_usd\":\(marketCapChange24h)",
            "\"updated_at\":\(EpochTime.nowMillis / 1000)",
        ]
        return "{\"data\":{" + fields.joined(separator: ",") + "}}"
    }

    /// Rangliste nach Marktkapitalisierung; `per_page`, `page` (ab Seite 2 leer) und `symbols=` wie bei CoinGecko.
    private static func coinGeckoMarkets(_ url: URL) -> String {
        let page = Int(queryValue(url, "page") ?? "") ?? 1
        let perPage = Int(queryValue(url, "per_page") ?? "") ?? 100
        let symbols = queryValue(url, "symbols").map {
            Set($0.lowercased().split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) })
        }
        let ranked = tourCoins.enumerated().filter { symbols?.contains($0.element.symbol.lowercased()) ?? true }
        let shown = page == 1 ? Array(ranked.prefix(max(perPage, 0))) : []
        let rows = shown.map { index, coin -> String in
            let id = coin.name.lowercased().replacingOccurrences(of: " ", with: "-")
            let head = "{\"id\":\"\(id)\",\"symbol\":\"\(coin.symbol.lowercased())\",\"name\":\"\(coin.name)\","
            let values = "\"current_price\":\(tourPrice(coin)),\"market_cap\":\(coin.marketCap),"
                + "\"market_cap_rank\":\(index + 1),\"total_volume\":\(coin.marketCap * turnover(coin)),"
            return head + values + "\"price_change_percentage_24h\":\(coin.change24h)}"
        }
        return "[" + rows.joined(separator: ",") + "]"
    }

    // MARK: alternative.me

    /// Fear & Greed, heute zuerst (heute 55, gestern 52, vor einer Woche 48, vor 30 Tagen 38).
    private static let fearGreedValues = [
        55, 52, 50, 51, 49, 47, 46, 48, 45, 44, 46, 43, 41, 42, 40, 39,
        41, 38, 37, 39, 40, 38, 36, 37, 39, 41, 40, 38, 37, 39, 38,
    ]

    private static func fearGreed(_ url: URL) -> String {
        let now = EpochTime.nowMillis
        let count = min(max(Int(queryValue(url, "limit") ?? "") ?? 1, 1), fearGreedValues.count)
        let rows = fearGreedValues.prefix(count).enumerated().map { i, value in
            "{\"value\":\"\(value)\",\"value_classification\":\"\(fearGreedClass(value))\","
                + "\"timestamp\":\"\(dayStartSeconds(now: now, days: i))\"}"
        }
        return #"{"name":"Fear and Greed Index","data":["# + rows.joined(separator: ",") + #"],"metadata":{"error":null}}"#
    }

    private static func fearGreedClass(_ value: Int) -> String {
        switch value {
        case ..<25: return "Extreme Fear"
        case ..<47: return "Fear"
        case ..<55: return "Neutral"
        case ..<76: return "Greed"
        default: return "Extreme Greed"
        }
    }

    // MARK: Binance USDⓈ-M

    /// Coins mit Perpetual (alle ausser Stablecoins).
    private static var perpetuals: [UITestExchangeCoin] { tourCoins.filter { $0.beta > 0 } }

    /// Funding je Coin: bei steigendem Kurs etwas höher, bei fallendem tiefer (0.01 % Grundwert je 8 Std.).
    private static func fundingRate(_ coin: UITestExchangeCoin) -> Double {
        0.0001 * (1.0 + coin.change24h / 4.0)
    }

    private static func premiumIndex(_ url: URL) -> String? {
        let now = EpochTime.nowMillis
        let period = 8 * hourMillis
        let nextFunding = (now / period + 1) * period
        func entry(_ coin: UITestExchangeCoin) -> String {
            let mark = num(tourPrice(coin))
            return "{\"symbol\":\"\(coin.symbol)USDT\",\"markPrice\":\"\(mark)\",\"indexPrice\":\"\(mark)\","
                + "\"lastFundingRate\":\"\(num(fundingRate(coin)))\",\"interestRate\":\"0.00010000\","
                + "\"nextFundingTime\":\(nextFunding),\"time\":\(now)}"
        }
        guard let symbol = queryValue(url, "symbol") else {
            return "[" + perpetuals.map(entry).joined(separator: ",") + "]"
        }
        return perpetuals.first { "\($0.symbol)USDT" == symbol }.map(entry)
    }

    /// Offene Kontrakte (in Coins): rund 4 % der Marktkapitalisierung.
    private static func openInterest(_ url: URL) -> String? {
        guard let symbol = queryValue(url, "symbol"),
              let coin = perpetuals.first(where: { "\($0.symbol)USDT" == symbol }) else { return nil }
        let contracts = coin.marketCap * 0.004 / tourPrice(coin)
        return "{\"symbol\":\"\(symbol)\",\"openInterest\":\"\(num(contracts))\",\"time\":\(EpochTime.nowMillis)}"
    }

    // MARK: Gebühren

    /// Blockhöhe Ende Oktober 2026 (nächstes Halving bei 1’050’000).
    private static let blockHeight = 972_104

    /// Gebühren je Knoten in gwei: Grundgebühr, dann Trinkgeld langsam/normal/schnell.
    private static let gas: [String: [Double]] = [
        "ethereum-rpc.publicnode.com": [1.8, 0.02, 0.1, 0.8],
        "base-rpc.publicnode.com": [0.006, 0.001, 0.003, 0.01],
        "arbitrum-one-rpc.publicnode.com": [0.01, 0, 0, 0.001],
        "polygon-bor-rpc.publicnode.com": [28, 25, 30, 45],
        "bsc-rpc.publicnode.com": [0.1, 0, 0, 0.05],
    ]

    /// `eth_feeHistory`: Grundgebühr des nächsten Blocks und Trinkgelder (10./50./90. Perzentil), alles in gwei.
    private static func feeHistory(_ gwei: [Double]) -> String {
        func hex(_ value: Double) -> String { "\"0x" + String(Int64(value * 1e9), radix: 16) + "\"" }
        let base = Array(repeating: hex(gwei[0]), count: 3).joined(separator: ",")
        let tips = "[" + gwei.dropFirst().map(hex).joined(separator: ",") + "]"
        let reward = Array(repeating: tips, count: 2).joined(separator: ",")
        let result = #""oldestBlock":"0x1","gasUsedRatio":[0.5,0.5],"# + "\"baseFeePerGas\":[\(base)],\"reward\":[\(reward)]"
        return #"{"jsonrpc":"2.0","id":1,"result":{"# + result + "}}"
    }

    // MARK: Kraken

    /// Paar USDT/USD bei Kraken (wie `DepegAlarm.krakenPairId`).
    private static let krakenUsdtUsdPair = "USDTZUSD"

    /// `GET /0/public/OHLC?pair=USDTZUSD&interval=…`: die jüngsten 720 Kerzen USDT/USD (Zeit in Sekunden, Texte), knapp
    /// über 1 USD; andere Paare 404.
    private static func krakenUsdtUsd(_ url: URL) -> String? {
        guard queryValue(url, "pair") == krakenUsdtUsdPair else { return nil }
        let step = max(Int64(queryValue(url, "interval") ?? "") ?? 1, 1) * 60_000
        let current = EpochTime.nowMillis / step * step
        let rows = (0..<720).reversed().map { back -> String in
            let close = 1.0002 + 0.0002 * sin(Double(back) * 0.7)
            let open = 1.0002 + 0.0002 * sin(Double(back + 1) * 0.7)
            let values = [open, max(open, close) + 0.0001, min(open, close) - 0.0001, close, close]
                .map { "\"\(num($0))\"" }.joined(separator: ",")
            return "[\((current - Int64(back) * step) / 1000),\(values),\"25000000.0\",12000]"
        }
        return "{\"error\":[],\"result\":{\"\(krakenUsdtUsdPair)\":[" + rows.joined(separator: ",")
            + "],\"last\":\((current - step) / 1000)}}"
    }

    // MARK: Binance-Website

    /// Symbolliste mit vollen Namen (`fullName`), wie die Website sie für Logos und Namen liefert.
    private static func binanceSymbolList() -> String {
        let rows = tourCoins.map {
            "{\"name\":\"\($0.symbol)\",\"fullName\":\"\($0.name)\",\"logo\":null,\"tags\":[],\"onlyFutures\":false}"
        }
        return #"{"code":"000000","data":["# + rows.joined(separator: ",") + #"],"success":true}"#
    }
}
#endif
