import Foundation

/// Regeln für die Coin-Logos (gleich in Android `CoinLogos.kt`, gemeinsame Testfälle
/// `testdata/parity/coin_logos.json`):
///
/// - Zuordnung Symbol → Bild-Adresse aus der öffentlichen CoinGecko-Rangliste (`/coins/markets`,
///   Feld `image`), nach Marktkapitalisierung sortiert. Teilen sich Coins ein Symbol, gewinnt der
///   grösste (der erste in der Rangliste).
/// - Nichts mitgeliefert, aber auch nichts einzeln: Die App lädt die Logos **aller** Coins der
///   Rangliste auf einmal (danach nur neu dazugekommene) und zeigt sie nur aus dem Speicher auf
///   dem Gerät. So sieht CoinGecko bei jedem Nutzer dieselben Abrufe und erfährt nie, welche Coins
///   in einer Merkliste stehen. Coins ausserhalb der Rangliste bekommen Initialen.
/// - Zweite Quelle für Lücken: die öffentliche Symbolliste der Binance-Website (`binanceListURL`,
///   Feld `logo`) — vor allem TradFi (Gold, Silber, Aktien), die CoinGecko nicht führt. Sie füllt
///   nur Symbole, die CoinGecko nicht kennt (`pick` lässt Vorhandenes stehen). Inoffizielle
///   Schnittstelle: Fällt sie weg, bleiben dort Initialen.
/// - Nur Bilder von CoinGecko- und Binance-Bildservern über HTTPS (`isAllowedURL`).
/// - Kein Logo (unbekannt, Fehler, Schalter aus): Kreis mit `initials` — nie ein kaputtes Bild.
enum CoinLogos {
    /// Zuordnung eine Woche lang gültig.
    static let mapTTLMillis: Int64 = 7 * 24 * 60 * 60 * 1000
    /// Fehlgeschlagenes Bild erst nach einem Tag erneut versuchen.
    static let failureTTLMillis: Int64 = 24 * 60 * 60 * 1000
    /// Seiten à 250 Coins: die grössten 1000.
    static let pages = 4
    static let perPage = 250
    /// Kantenlänge der gespeicherten Bilder in Pixeln.
    static let storedPixels = 128

    /// Symbolliste der Binance-Website (inoffiziell, ohne Schlüssel), je Eintrag `name` und `logo`.
    static let binanceListURL = "https://www.binance.com/bapi/composite/v1/public/marketing/symbol/list"

    static func marketsURL(page: Int) -> String {
        "https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&order=market_cap_desc"
            + "&per_page=\(perPage)&page=\(page)&sparkline=false"
    }

    private static let multiplierPrefixes = ["1000000", "100000", "10000", "1000", "1M"]
    private static let aliases = ["XBT": "BTC", "XDG": "DOGE"]

    /// Symbol, unter dem das Logo gesucht wird: gross, ohne Leerraum, ohne Hebel-Präfix
    /// («1000PEPE» → «PEPE»), Börsen-Kürzel vereinheitlicht («XBT» → «BTC»).
    static func normalize(_ symbol: String) -> String {
        var s = symbol.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        for prefix in multiplierPrefixes where s.hasPrefix(prefix) {
            let rest = s.dropFirst(prefix.count)
            if rest.count >= 2, let first = rest.first, first.isLetter {
                s = String(rest)
                break
            }
        }
        return aliases[s] ?? s
    }

    /// Initialen für den Ersatz-Kreis: bis vier Zeichen ganz, sonst die ersten drei.
    static func initials(_ symbol: String) -> String {
        let s = symbol.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        return s.count <= 4 ? s : String(s.prefix(3))
    }

    /// Börse, deren Symbole jeder frei wählen kann (DEX-Pools).
    static let dexMarketKey = "DexScreener"

    /// Logo für Paare dieser Börse? Bei DEX-Pools nie: Ein fremder Token darf «BTC» heissen —
    /// dann nie das Bitcoin-Logo daneben, nur Initialen.
    static func allowed(forMarket marketKey: String?) -> Bool { marketKey != dexMarketKey }

    /// Nur HTTPS-Bilder von CoinGecko (`coin-images.coingecko.com` u. ä.) und Binance (`bin.bnbstatic.com`).
    static func isAllowedURL(_ url: String?) -> Bool {
        guard let url, url.hasPrefix("https://") else { return false }
        let rest = url.dropFirst("https://".count)
        let host = String(rest.prefix { $0 != "/" && $0 != "?" }).lowercased()
        if host.isEmpty || host.contains("@") || host.contains(":") { return false }
        guard host == "coingecko.com" || host.hasSuffix(".coingecko.com") || host.hasSuffix(".bnbstatic.com")
        else { return false }
        // Platzhalter für Coins ohne Logo («missing_large.png»): lieber Initialen
        return !url.contains("/missing_")
    }

    /// Kleine Fassung des Bildes (CoinGecko liefert «thumb», «small», «large»; die Rangliste nennt
    /// «large»). «small» reicht für 24–48 pt und spart beim Laden aller Logos viel Datenvolumen.
    static func smallURL(_ url: String) -> String {
        guard let range = url.range(of: "/large/") else { return url }
        return url.replacingCharacters(in: range, with: "/small/")
    }

    /// Rangliste (Reihenfolge = Marktkapitalisierung) → Symbol → Adresse; erstes Vorkommen gewinnt.
    /// `order` hält die Reihenfolge fest (für Tests und den Zwischenspeicher).
    static func pick(_ ranked: [(symbol: String, image: String?)],
                     into map: inout [String: String], order: inout [String]) {
        for (symbol, url) in ranked {
            let key = normalize(symbol)
            guard !key.isEmpty, map[key] == nil, isAllowedURL(url), let url else { continue }
            map[key] = url.trimmingCharacters(in: .whitespacesAndNewlines)
            order.append(key)
        }
    }

    // MARK: Zwischenspeicher: eine Zeile je Coin, «BTC\thttps://…»

    static func encode(_ map: [String: String], order: [String]) -> String {
        order.compactMap { key in map[key].map { "\(key)\t\($0)" } }.joined(separator: "\n")
    }

    static func decode(_ text: String?) -> [String: String] {
        guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return [:] }
        var out: [String: String] = [:]
        for line in text.split(separator: "\n", omittingEmptySubsequences: true) {
            let parts = line.split(separator: "\t", maxSplits: 1, omittingEmptySubsequences: false)
            guard parts.count == 2 else { continue }
            let symbol = parts[0].trimmingCharacters(in: .whitespaces)
            let url = parts[1].trimmingCharacters(in: .whitespaces)
            if !symbol.isEmpty, isAllowedURL(url), out[symbol] == nil { out[symbol] = url }
        }
        return out
    }

    static func isFresh(savedAt: Int64, now: Int64, ttl: Int64 = mapTTLMillis) -> Bool {
        savedAt >= 1 && savedAt <= now && now - savedAt < ttl
    }

    /// Dateiname im Bild-Zwischenspeicher; nur Symbole aus A–Z und 0–9 (sonst nil → Initialen).
    static func fileName(_ symbol: String) -> String? {
        let key = normalize(symbol)
        let allowed = key.unicodeScalars.allSatisfy { ("A"..."Z").contains($0) || ("0"..."9").contains($0) }
        guard !key.isEmpty, allowed else { return nil }
        return key + ".png"
    }
}

/// Wo Logos gezeigt werden (Schalter unter Darstellung › Coin-Logos), wie Android `CoinLogoUse`.
enum CoinLogoUse {
    /// Portfolio-Logos zählen nur mit eingeschaltetem Portfolio-Tab.
    static func portfolio(_ s: AppSettings) -> Bool { s.portfolioCoinLogos && s.portfolioEnabled }

    static func needed(_ s: AppSettings) -> Bool { s.coinLogos || s.widgetCoinLogos || portfolio(s) }
}
