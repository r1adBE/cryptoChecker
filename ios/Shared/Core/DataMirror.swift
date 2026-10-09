import Foundation

/// Spiegel öffentlicher Marktdaten, die für alle Nutzer gleich sind (CoinGecko `/global` und
/// Top-Coins, Fear & Greed, Coin Metrics). Eine GitHub Action (`.github/workflows/market-data.yml`)
/// holt sie stündlich und legt sie als Anhänge der Release «data» ab. `MarketHTTP.call` fragt für
/// diese Adressen zuerst den Spiegel und nur, wenn er fehlt oder zu alt ist, den Anbieter selbst.
/// Gleich wie Android `DataMirror.kt`, gemeinsame Testfälle `data_mirror.json`.
///
/// Datei: erste Zeile «CCDM1 <Abrufzeit in ms>», danach die Antwort des Anbieters unverändert.
enum DataMirror {
    static let base = "https://github.com/r1adBE/cryptoChecker/releases/download/data/"
    private static let magic = "CCDM1"
    private static let hour: Int64 = 60 * 60 * 1000
    /// Zeitpunkt in der Zukunft bis zu so viel gilt noch (Uhren gehen nicht genau gleich).
    private static let clockSkewMillis: Int64 = 5 * 60 * 1000

    /// Spiegel-Datei und wie alt sie höchstens sein darf.
    struct Entry: Equatable {
        let file: String
        let maxAgeMillis: Int64
        var url: String { DataMirror.base + file }
    }

    private static let exact: [String: Entry] = [
        "https://api.coingecko.com/api/v3/global": Entry(file: "global.txt", maxAgeMillis: 2 * hour),
        "https://api.alternative.me/fng/?limit=31": Entry(file: "fng.txt", maxAgeMillis: 4 * hour),
        "https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&order=market_cap_desc&per_page=30&page=1":
            Entry(file: "coins30.txt", maxAgeMillis: 12 * hour),
        "https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&order=market_cap_desc&per_page=40&page=1":
            Entry(file: "coins40.txt", maxAgeMillis: 12 * hour),
        "https://community-api.coinmetrics.io/v4/timeseries/asset-metrics"
            + "?assets=btc&metrics=PriceUSD&frequency=1d&start_time=2016-06-01&page_size=10000":
            Entry(file: "btcprice.txt", maxAgeMillis: 36 * hour),
    ]

    /// On-Chain-Werte: das Startdatum der Adresse wandert täglich mit.
    private static let onChainPrefix = "https://community-api.coinmetrics.io/v4/timeseries/asset-metrics"
        + "?assets=btc&metrics=CapMVRVCur,IssTotUSD,HashRate&frequency=1d&start_time="
    private static let onChain = Entry(file: "onchain.txt", maxAgeMillis: 36 * hour)

    /// Spiegel für diese Adresse (nur GET); nil = keiner, direkt beim Anbieter.
    static func entry(for url: String) -> Entry? {
        if let e = exact[url] { return e }
        return url.hasPrefix(onChainPrefix) && url.hasSuffix("&page_size=1000") ? onChain : nil
    }

    /// Altcoin-Saison, fertig berechnet (BTC und 20 Altcoins, 90 Tage — gleiche Regel wie in der App):
    /// `{"outperformers":12,"total":20,"provider":"Binance"}`. Spart pro Handy gut 20 Kurs-Abrufe.
    static let altSeason = Entry(file: "altseason.txt", maxAgeMillis: 6 * hour)

    /// Gelesene Altcoin-Saison; `provider` nur, wenn ein bekannter Anbieter der Kurse.
    struct AltSeasonValue: Equatable {
        let outperformers: Int
        let total: Int
        let provider: String?
    }

    private static let altSeasonProviders: Set<String> = ["Binance", "Binance.US", "Coinbase"]

    /// Inhalt von `altSeason`; nil, wenn ungültig (dann rechnet die App selbst) — wie Android.
    static func parseAltSeason(_ body: String?) -> AltSeasonValue? {
        guard let body, let o = try? JSONSerialization.jsonObject(with: Data(body.utf8)) as? [String: Any],
              let outN = o["outperformers"] as? NSNumber, let totalN = o["total"] as? NSNumber,
              outN.doubleValue == outN.doubleValue.rounded(), totalN.doubleValue == totalN.doubleValue.rounded()
        else { return nil }
        let out = outN.intValue, total = totalN.intValue
        guard (10...50).contains(total), (0...total).contains(out) else { return nil }
        let provider = (o["provider"] as? String).flatMap { altSeasonProviders.contains($0) ? $0 : nil }
        return AltSeasonValue(outperformers: out, total: total, provider: provider)
    }

    /// Antwort des Anbieters aus der Spiegel-Datei, wenn sie gültig und frisch genug ist; sonst nil.
    static func unwrap(_ text: String?, now: Int64, maxAgeMillis: Int64) -> String? {
        guard let text, let nl = text.firstIndex(of: "\n"), nl > text.startIndex else { return nil }
        let head = text[..<nl].trimmingCharacters(in: .whitespaces).split(separator: " ", omittingEmptySubsequences: false)
        guard head.count == 2, head[0] == magic, let fetched = Int64(head[1]),
              fetched > 0, fetched <= now + clockSkewMillis, now - fetched < maxAgeMillis
        else { return nil }
        let body = String(text[text.index(after: nl)...])
        return body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : body
    }
}
