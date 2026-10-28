#if DEBUG
import Foundation

/// Ein Coin der Bildschirmfoto-Tour: Kürzel, Name, Kurs in USD(T), Marktkapitalisierung, 24-h-Veränderung in % und
/// `beta` = wie stark sein Verlauf dem von BTC folgt (0 = Stablecoin, fester Kurs). Wie `TourCoin` (Android).
struct UITestExchangeCoin: Sendable {
    let symbol: String
    let name: String
    let price: Double
    let marketCap: Double
    let change24h: Double
    let beta: Double
}

/// Feste, in sich stimmige Marktdaten der Bildschirmfoto-Tour (`UITestExchange.marketData`) — gleiche Werte wie
/// `FakeMarketSeries.kt` (Android): rund 34 grosse Coins (BTC 95’000, ETH 3’500, SOL 200 wie die Merkliste,
/// Gesamtmarkt rund 3,2 Billionen USD) und ein künstlicher, aber plausibler BTC-Verlauf seit Juni 2016.
///
/// Der Verlauf läuft geometrisch durch Eckpunkte (Halvings, Hochs und Tiefs der Zyklen) bis zum heutigen Kurs, mit
/// kleinen festen Schwingungen (Sinus) — so ergeben Durchschnitte, RSI, Zyklus-Vergleich und Altcoin-Saison echte
/// Werte. Andere Coins folgen BTC mit ihrem `beta`. Alles wird je Abfrage zur aktuellen Uhrzeit erzeugt: feste
/// JSON-Dateien veralteten (die App verwirft Kerzen- und Marktdaten, die nicht bis jetzt reichen), und die App kann
/// keine Testdateien neben der Testquelle lesen.
extension UITestExchange {
    static let hourMillis: Int64 = 3_600_000
    static let dayMillis: Int64 = 24 * hourMillis
    static let weekMillis: Int64 = 7 * dayMillis
    /// Binance-Wochen beginnen Montag 00:00 UTC; der 1.1.1970 war ein Donnerstag.
    private static let mondayOffset: Int64 = 4 * dayMillis
    /// Erste Woche von BTCUSDT bei Binance (Montag 14.8.2017): davor keine Kerzen, wie bei der echten Börse.
    private static let listing = LocalDay(2017, 8, 14).epochMillisUTC

    /// Die Coins nach Marktkapitalisierung; die fünf Start-Coins mit den Kursen aus `prices`.
    static let tourCoins: [UITestExchangeCoin] = [
        UITestExchangeCoin(symbol: "BTC", name: "Bitcoin", price: 95_000.0, marketCap: 1.895e12, change24h: 1.25, beta: 1.0),
        UITestExchangeCoin(symbol: "ETH", name: "Ethereum", price: 3_500.0, marketCap: 4.22e11, change24h: 2.1, beta: 1.15),
        UITestExchangeCoin(symbol: "USDT", name: "Tether", price: 1.0002, marketCap: 1.85e11, change24h: 0.01, beta: 0),
        UITestExchangeCoin(symbol: "XRP", name: "XRP", price: 2.5, marketCap: 1.49e11, change24h: -0.8, beta: 0.9),
        UITestExchangeCoin(symbol: "BNB", name: "BNB", price: 900.0, marketCap: 1.26e11, change24h: 0.6, beta: 0.7),
        UITestExchangeCoin(symbol: "SOL", name: "Solana", price: 200.0, marketCap: 1.09e11, change24h: 3.4, beta: 1.3),
        UITestExchangeCoin(symbol: "USDC", name: "USDC", price: 0.9998, marketCap: 7.8e10, change24h: -0.01, beta: 0),
        UITestExchangeCoin(symbol: "DOGE", name: "Dogecoin", price: 0.38, marketCap: 5.7e10, change24h: 6.8, beta: 1.4),
        UITestExchangeCoin(symbol: "ADA", name: "Cardano", price: 0.95, marketCap: 3.42e10, change24h: 3.4, beta: 1.2),
        UITestExchangeCoin(symbol: "TRX", name: "TRON", price: 0.29, marketCap: 2.75e10, change24h: -0.6, beta: 0.5),
        UITestExchangeCoin(symbol: "LINK", name: "Chainlink", price: 24.5, marketCap: 1.66e10, change24h: 4.9, beta: 1.1),
        UITestExchangeCoin(symbol: "AVAX", name: "Avalanche", price: 38.0, marketCap: 1.6e10, change24h: -2.7, beta: 1.35),
        UITestExchangeCoin(symbol: "SUI", name: "Sui", price: 4.1, marketCap: 1.44e10, change24h: 8.9, beta: 1.5),
        UITestExchangeCoin(symbol: "SHIB", name: "Shiba Inu", price: 0.000024, marketCap: 1.41e10, change24h: 5.5, beta: 1.3),
        UITestExchangeCoin(symbol: "TON", name: "Toncoin", price: 5.6, marketCap: 1.4e10, change24h: -3.8, beta: 0.75),
        UITestExchangeCoin(symbol: "XLM", name: "Stellar", price: 0.42, marketCap: 1.3e10, change24h: 0.8, beta: 0.85),
        UITestExchangeCoin(symbol: "DOT", name: "Polkadot", price: 7.8, marketCap: 1.25e10, change24h: 1.9, beta: 1.25),
        UITestExchangeCoin(symbol: "HBAR", name: "Hedera", price: 0.27, marketCap: 1.13e10, change24h: 2.2, beta: 1.05),
        UITestExchangeCoin(symbol: "BCH", name: "Bitcoin Cash", price: 520.0, marketCap: 1.03e10, change24h: -1.4, beta: 0.95),
        UITestExchangeCoin(symbol: "LTC", name: "Litecoin", price: 115.0, marketCap: 8.7e9, change24h: -0.9, beta: 0.8),
        UITestExchangeCoin(symbol: "UNI", name: "Uniswap", price: 13.5, marketCap: 8.1e9, change24h: 7.2, beta: 1.1),
        UITestExchangeCoin(symbol: "PEPE", name: "Pepe", price: 0.000019, marketCap: 8.0e9, change24h: 11.2, beta: 1.6),
        UITestExchangeCoin(symbol: "NEAR", name: "NEAR Protocol", price: 6.2, marketCap: 7.8e9, change24h: -4.6, beta: 1.2),
        UITestExchangeCoin(symbol: "APT", name: "Aptos", price: 11.8, marketCap: 7.1e9, change24h: 3.1, beta: 1.3),
        UITestExchangeCoin(symbol: "ICP", name: "Internet Computer", price: 12.4, marketCap: 6.6e9, change24h: -5.9, beta: 1.15),
        UITestExchangeCoin(symbol: "POL", name: "POL (ex-MATIC)", price: 0.55, marketCap: 5.8e9, change24h: -1.1, beta: 1.2),
        UITestExchangeCoin(symbol: "AAVE", name: "Aave", price: 330.0, marketCap: 5.0e9, change24h: 4.4, beta: 1.0),
        UITestExchangeCoin(symbol: "ETC", name: "Ethereum Classic", price: 31.0, marketCap: 4.7e9, change24h: 0.4, beta: 0.9),
        UITestExchangeCoin(symbol: "FIL", name: "Filecoin", price: 6.4, marketCap: 4.5e9, change24h: -3.3, beta: 1.1),
        UITestExchangeCoin(symbol: "RENDER", name: "Render", price: 8.2, marketCap: 4.3e9, change24h: -7.4, beta: 1.45),
        UITestExchangeCoin(symbol: "ARB", name: "Arbitrum", price: 0.85, marketCap: 3.8e9, change24h: 2.6, beta: 1.3),
        UITestExchangeCoin(symbol: "ATOM", name: "Cosmos Hub", price: 8.9, marketCap: 3.6e9, change24h: -2.2, beta: 1.05),
        UITestExchangeCoin(symbol: "OP", name: "Optimism", price: 2.1, marketCap: 3.6e9, change24h: -6.7, beta: 1.35),
        UITestExchangeCoin(symbol: "INJ", name: "Injective", price: 28.0, marketCap: 2.7e9, change24h: 9.6, beta: 1.4),
    ]

    /// Gesamtmarkt in USD, 24-h-Umsatz und Veränderung (CoinGecko `/global`).
    static let totalMarketCap = 3.2e12
    static let totalVolume = 1.25e11
    static let marketCapChange24h = 1.4

    static func tourCoin(_ symbol: String) -> UITestExchangeCoin? {
        tourCoins.first { $0.symbol == symbol }
    }

    /// Coins für die 24-h-Ticker und Kurse der Fake-Börsen: alle ausser USDT (kein Paar USDTUSDT).
    static var tradableTourCoins: [String] {
        tourCoins.map(\.symbol).filter { $0 != "USDT" }
    }

    /// Umsatz-Anteil am Tag: grössere Bewegung, mehr Umsatz (für «Heute auffällig»).
    static func turnover(_ coin: UITestExchangeCoin) -> Double {
        0.03 + 0.012 * abs(coin.change24h)
    }

    // MARK: Verlauf

    /// Eckpunkte des BTC-Verlaufs (Tag, USD): Halvings 2016/2020/2024, Hochs und Tiefs; danach der heutige Kurs.
    private static let anchors: [(time: Int64, price: Double)] = [
        (LocalDay(2016, 6, 1).epochMillisUTC, 530), (LocalDay(2016, 7, 9).epochMillisUTC, 650),
        (LocalDay(2017, 12, 17).epochMillisUTC, 19_300), (LocalDay(2018, 12, 15).epochMillisUTC, 3_200),
        (LocalDay(2019, 6, 26).epochMillisUTC, 12_900), (LocalDay(2020, 3, 13).epochMillisUTC, 4_900),
        (LocalDay(2020, 5, 11).epochMillisUTC, 8_600), (LocalDay(2021, 4, 14).epochMillisUTC, 63_500),
        (LocalDay(2021, 7, 20).epochMillisUTC, 29_800), (LocalDay(2021, 11, 10).epochMillisUTC, 68_500),
        (LocalDay(2022, 11, 21).epochMillisUTC, 15_800), (LocalDay(2024, 3, 14).epochMillisUTC, 73_000),
        (LocalDay(2024, 4, 20).epochMillisUTC, 64_000), (LocalDay(2024, 8, 5).epochMillisUTC, 54_000),
        (LocalDay(2024, 12, 17).epochMillisUTC, 106_000), (LocalDay(2025, 4, 8).epochMillisUTC, 76_500),
        (LocalDay(2025, 10, 6).epochMillisUTC, 124_500),
    ]

    /// Heutiger BTC-Kurs (wie die Merkliste).
    private static var btcNow: Double { price("BTC") ?? 95_000 }

    /// BTC in USD zum Zeitpunkt `t` (ms) ohne Schwingung; ab `now` der heutige Kurs.
    private static func btcTrend(_ t: Int64, now: Int64) -> Double {
        let points = anchors.filter { $0.time < now - dayMillis } + [(time: now, price: btcNow)]
        guard let first = points.first, t > first.time else { return points.first?.price ?? btcNow }
        if t >= now { return btcNow }
        guard let i = points.firstIndex(where: { $0.time >= t }), i > 0 else { return btcNow }
        let (t0, p0) = points[i - 1]
        let (t1, p1) = points[i]
        return p0 * pow(p1 / p0, Double(t - t0) / Double(t1 - t0))
    }

    /// Feste Schwingung um 1, `days` Tage vor jetzt; klingt vom vierten zum letzten Tag aus und fehlt in den letzten
    /// 24 Stunden (dort gilt allein die 24-h-Veränderung, siehe `priceAt`).
    private static func wiggle(days: Double, phase: Double) -> Double {
        let taper = min(max((days - 1.0) / 3.0, 0), 1)
        let slow = 0.035 * sin(2 * Double.pi * days / 23.0 + phase)
        let fast = 0.018 * sin(2 * Double.pi * days / 6.7 + phase)
        let hourly = 0.003 * sin(2 * Double.pi * days * 24.0 / 5.3)
        return 1.0 + taper * (slow + fast + hourly)
    }

    /// Aktueller Kurs: BTC wie die Merkliste, sonst aus der Tabelle.
    static func tourPrice(_ coin: UITestExchangeCoin) -> Double {
        coin.symbol == "BTC" ? btcNow : coin.price
    }

    /// Kurs von `coin` zum Zeitpunkt `t`; jetzt genau `tourPrice`, vor 24 h um die 24-h-Veränderung tiefer (wie Ticker
    /// und Rangliste; dazwischen gleichmässig). Stablecoins bleiben fest.
    static func priceAt(_ coin: UITestExchangeCoin, _ t: Int64, now: Int64) -> Double {
        let current = tourPrice(coin)
        if coin.beta == 0 { return current }
        let days = Double(max(now - t, 0)) / Double(dayMillis)
        let ratio = btcTrend(t, now: now) / btcNow
        let index = tourCoins.firstIndex { $0.symbol == coin.symbol } ?? 0
        let lastDay = 1.0 + coin.change24h / 100.0 * min(days, 1.0)
        return current * pow(ratio, coin.beta) * wiggle(days: days, phase: Double(index) * 0.9) / lastDay
    }

    // MARK: Binance-Kerzen

    /// `GET /api/v3/klines?symbol=…&interval=1h|4h|1d|1w&limit=…[&startTime=…]` im Format von Binance (älteste zuerst,
    /// die letzte Kerze läuft noch); nil = unbekanntes Symbol oder Intervall (404).
    static func klines(_ url: URL, now: Int64 = EpochTime.nowMillis) -> String? {
        guard let symbol = queryValue(url, "symbol"), symbol.hasSuffix("USDT"),
              let coin = tourCoin(String(symbol.dropLast(4))) else { return nil }
        let step: Int64
        switch queryValue(url, "interval") {
        case "1h": step = hourMillis
        case "4h": step = 4 * hourMillis
        case "1d": step = dayMillis
        case "1w": step = weekMillis
        default: return nil
        }
        let limit = min(max(Int(queryValue(url, "limit") ?? "") ?? 500, 1), 1000)
        let offset: Int64 = step == weekMillis ? mondayOffset : 0
        let current = (now - offset) / step * step + offset
        var opens: [Int64] = []
        if let startText = queryValue(url, "startTime"), let start = Int64(startText) {
            var t = (start - offset + step - 1) / step * step + offset
            while t <= current && opens.count < limit {
                opens.append(t)
                t += step
            }
        } else {
            opens = (0..<limit).reversed().map { current - Int64($0) * step }
        }
        opens = opens.filter { $0 >= listing }
        let dailyVolume = coin.marketCap * 0.03 / tourPrice(coin)
        var previous: Double?
        var rows: [String] = []
        rows.reserveCapacity(opens.count)
        for (i, open) in opens.enumerated() {
            let close = open == current ? tourPrice(coin) : priceAt(coin, open + step, now: now)
            let first = previous ?? priceAt(coin, open, now: now)
            previous = close
            let volume = dailyVolume * Double(step) / Double(dayMillis) * (1.0 + 0.3 * sin(Double(i) * 1.3))
            let ohlcv = [first, max(first, close) * 1.012, min(first, close) * 0.988, close, volume]
                .map { "\"\(num($0))\"" }.joined(separator: ",")
            let taker = "\"\(num(volume / 2))\",\"\(num(volume * close / 2))\""
            rows.append("[\(open),\(ohlcv),\(open + step - 1),\"\(num(volume * close))\",\(1_000 + i),\(taker),\"0\"]")
        }
        return "[" + rows.joined(separator: ",") + "]"
    }

    // MARK: Coin Metrics

    /// `GET /v4/timeseries/asset-metrics?assets=btc&metrics=…&start_time=…`: Tageswerte bis gestern. «PriceUSD» = BTC-
    /// Schlusskurs; sonst MVRV, Ausgabe der neuen Coins in USD (450 BTC am Tag) und Hashrate (TH/s, steigend).
    static func coinMetrics(_ url: URL, now: Int64 = EpochTime.nowMillis) -> String {
        let metrics = queryValue(url, "metrics") ?? ""
        let start = queryValue(url, "start_time").flatMap { LocalDay(iso: $0) } ?? LocalDay(2016, 6, 1)
        let today = LocalDay(epochMillisUTC: now)
        guard let btc = tourCoin("BTC") else { return #"{"data":[]}"# }
        var rows: [String] = []
        var day = start
        while day < today {
            let dayStart = day.epochMillisUTC
            let close = priceAt(btc, dayStart + dayMillis, now: now)
            let time = #""asset":"btc","time":"\#(day.description)T00:00:00.000000000Z""#
            if metrics.contains("PriceUSD") {
                rows.append("{\(time),\"PriceUSD\":\"\(num(close))\"}")
            } else {
                let daysAgo = Double(now - dayStart) / Double(dayMillis)
                let realized = 45_000.0 - 9.0 * daysAgo
                let hash = 1.05e9 * (1.0 - 0.0009 * daysAgo) * (1.0 + 0.02 * sin(daysAgo / 4.0))
                let values = "\"CapMVRVCur\":\"\(num(close / realized))\",\"IssTotUSD\":\"\(num(450.0 * close))\""
                rows.append("{\(time),\(values),\"HashRate\":\"\(num(hash))\"}")
            }
            day = day.plusDays(1)
        }
        return #"{"data":["# + rows.joined(separator: ",") + "]}"
    }

    // MARK: Hilfen

    /// Zahl als Text wie bei Binance/Coin Metrics: Punkt, acht Nachkommastellen, nie Exponent.
    static func num(_ value: Double) -> String {
        String(format: "%.8f", value)
    }

    /// Wert des Parameters `name` (dekodiert); nil = fehlt.
    static func queryValue(_ url: URL, _ name: String) -> String? {
        URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == name }?.value
    }

    /// Beginn des UTC-Tags `days` Tage vor `now`, in Sekunden (z. B. Fear & Greed).
    static func dayStartSeconds(now: Int64, days: Int) -> Int64 {
        (now / dayMillis - Int64(days)) * dayMillis / 1000
    }
}
#endif
