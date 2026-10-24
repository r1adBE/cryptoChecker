import Foundation

/// Spot-Kennzahlen eines Coins aus CoinGecko `/coins/markets` (alle Beträge in USD) — wie `SpotData` (Android).
struct SpotData: Equatable, Sendable {
    /// CoinGecko-Kennung («bitcoin»).
    let id: String
    /// Kürzel gross («BTC»).
    let symbol: String
    let name: String?
    let priceUsd: Double?
    let marketCap: Double?
    /// Veränderung der Marktkapitalisierung über 24 h in Prozent.
    let marketCapChange24h: Double?
    let volume24h: Double?
    /// Voll verwässerte Bewertung (Fully Diluted Valuation).
    let fdv: Double?
    let circulatingSupply: Double?
    let totalSupply: Double?
    /// nil: keine Obergrenze oder unbekannt.
    let maxSupply: Double?
    /// Stand bei CoinGecko (Epoch-ms).
    let lastUpdated: Int64?
}

/// Regeln für den Spot-Tab der Karte «Marktdaten» im Aktionsblatt (gleich in Android `SpotMarket.kt`,
/// gemeinsame Testfälle `Tests/Parity/spot_market.json`):
///
/// - Quelle zuerst die CoinGecko-Rangliste (Top 40, `topURL`, für alle Nutzer gleich, direkt bei CoinGecko),
///   sonst EINE Anfrage `/coins/markets?vs_currency=usd&symbols=<kürzel>` (`url`) — nur, wenn der
///   Nutzer den Spot-Tab öffnet, ~10 min im Speicher (`cacheTTLMillis`).
/// - Kein Abruf für TradFi-Paare und DEX-Pools (`eligible`) und für Kürzel ausserhalb A–Z/0–9
///   (`lookupSymbol`): Dort heisst oft ein fremder Token gleich.
/// - Teilen sich Coins ein Kürzel, gewinnt der mit der grössten Marktkapitalisierung (`pick`).
/// - Beträge ≤ 0 (CoinGecko meldet 0 für «unbekannt») gelten als fehlend; Volumen 0 bleibt.
enum SpotMarket {

    /// Top-40-Rangliste, für alle Nutzer gleich (direkt bei CoinGecko, nicht gespiegelt).
    static let topURL =
        "https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&order=market_cap_desc&per_page=40&page=1"

    /// Einzelabfrage nach Kürzel; CoinGecko liefert dazu den grössten Coin mit diesem Kürzel.
    static let symbolURLPrefix = "https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&symbols="

    /// So lange gilt ein Ergebnis (auch «keine Daten») im Speicher.
    static let cacheTTLMillis: Int64 = 10 * 60 * 1000

    /// Spot-Daten für dieses Paar überhaupt suchen? Nie für TradFi und DEX-Pools.
    static func eligible(marketKey: String?, tradFi: Bool) -> Bool {
        !tradFi && CoinLogos.allowed(forMarket: marketKey)
    }

    /// Kürzel für die Suche («1000PEPE» → «PEPE», «XBT» → «BTC»); nil, wenn nicht nur A–Z/0–9.
    static func lookupSymbol(_ baseAsset: String) -> String? {
        let s = CoinLogos.normalize(baseAsset)
        guard (1...15).contains(s.count), s.unicodeScalars.allSatisfy(isSymbolScalar) else { return nil }
        return s
    }

    /// A–Z oder 0–9 (ASCII).
    private static func isSymbolScalar(_ c: Unicode.Scalar) -> Bool {
        let v = c.value
        return (65...90).contains(v) || (48...57).contains(v)
    }

    /// Adresse der Einzelabfrage für ein Kürzel aus `lookupSymbol`.
    static func url(_ symbol: String) -> String {
        symbolURLPrefix + symbol.lowercased(with: Locale(identifier: "en_US_POSIX"))
    }

    /// Antwort von `/coins/markets` lesen; Einträge ohne Kennung oder Kürzel fallen weg.
    static func parse(_ text: String?) -> [SpotData] {
        guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let root = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [Any] else { return [] }
        return root.compactMap { ($0 as? [String: Any]).flatMap(entry) }
    }

    private static func entry(_ o: [String: Any]) -> SpotData? {
        guard let id = trimmed(o["id"]),
              let symbol = trimmed(o["symbol"])?.uppercased(with: Locale(identifier: "en_US_POSIX")) else { return nil }
        let marketCap = positive(o["market_cap"])
        // Ohne Marktkapitalisierung ist auch ihre Veränderung bedeutungslos
        let change = marketCap == nil ? nil : finite(o["market_cap_change_percentage_24h"])
        let volume = finite(o["total_volume"]).flatMap { $0 >= 0 ? $0 : nil }
        return SpotData(
            id: id,
            symbol: symbol,
            name: trimmed(o["name"]),
            priceUsd: positive(o["current_price"]),
            marketCap: marketCap,
            marketCapChange24h: change,
            volume24h: volume,
            fdv: positive(o["fully_diluted_valuation"]),
            circulatingSupply: positive(o["circulating_supply"]),
            totalSupply: positive(o["total_supply"]),
            maxSupply: positive(o["max_supply"]),
            lastUpdated: isoMillis(o["last_updated"] as? String)
        )
    }

    private static func trimmed(_ v: Any?) -> String? {
        guard let s = (v as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !s.isEmpty else { return nil }
        return s
    }

    /// Zahl aus JSON (NSNumber, nicht Bool); NSNull, Text und nicht endliche Werte → nil.
    private static func finite(_ v: Any?) -> Double? {
        guard let n = v as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else { return nil }
        let d = n.doubleValue
        return d.isFinite ? d : nil
    }

    private static func positive(_ v: Any?) -> Double? {
        finite(v).flatMap { $0 > 0 ? $0 : nil }
    }

    /// «2026-10-11T12:34:56.789Z» → Epoch-ms; nil, wenn ungültig.
    static func isoMillis(_ text: String?) -> Int64? {
        guard let s = text?.trimmingCharacters(in: .whitespacesAndNewlines), !s.isEmpty else { return nil }
        guard let date = IsoDates.date(s) else { return nil }
        let millis = Int64((date.timeIntervalSince1970 * 1000).rounded())
        return millis > 0 ? millis : nil
    }

    /// Eintrag zum Kürzel: bei mehreren der mit der grössten Marktkapitalisierung (sonst der erste).
    static func pick(_ entries: [SpotData], symbol: String) -> SpotData? {
        var best: SpotData?
        for e in entries where e.symbol == symbol {
            if best == nil || (e.marketCap ?? -1) > (best?.marketCap ?? -1) { best = e }
        }
        return best
    }

    /// Volumen im Verhältnis zur Marktkapitalisierung in Prozent; nil ohne beide Werte.
    static func volumeToMarketCap(volume: Double?, marketCap: Double?) -> Double? {
        guard let volume, let marketCap, volume.isFinite, marketCap.isFinite, marketCap > 0, volume >= 0 else { return nil }
        return volume / marketCap * 100.0
    }

    /// Anteil Umlauf an der Höchstmenge (0…1); nil ohne Höchstmenge.
    static func supplyRatio(circulating: Double?, max: Double?) -> Double? {
        guard let circulating, let max, circulating.isFinite, max.isFinite, max > 0, circulating >= 0 else { return nil }
        return Swift.min(Swift.max(circulating / max, 0), 1)
    }

    private static let units: [(div: Double, unit: String)] = [(1e12, "T"), (1e9, "B"), (1e6, "M"), (1e3, "K")]

    /// Grosse Zahl kurz mit zwei Nachkommastellen: 1.66T, 20.09M, 950.00 — Ziffern und Dezimalzeichen
    /// der App-Sprache (`LocaleNumbers`). Rundet ein Wert auf 1000 einer Einheit, nimmt er die nächste
    /// (999.999M → 1.00B). Nicht endlich → «—».
    static func compact(_ value: Double, locale: Locale = LocaleNumbers.appLocale) -> String {
        guard value.isFinite else { return "—" }
        let a = abs(value)
        var index = units.firstIndex { a >= $0.div }
        // Auf 1000 gerundet: eine Einheit höher
        if let i = index, i > 0, (a / units[i].div * 100).rounded() / 100 >= 1000 { index = i - 1 }
        if index == nil, (a * 100).rounded() / 100 >= 1000 { index = units.count - 1 }
        let div = index.map { units[$0].div } ?? 1
        let unit = index.map { units[$0].unit } ?? ""
        let sign = value < 0 ? "−" : ""
        return sign + LocaleNumbers.decimal(a / div, maxDecimals: 2, locale: locale, grouping: true) + unit
    }

    /// Betrag in USD kurz: «$1.66T».
    static func usd(_ value: Double?, locale: Locale = LocaleNumbers.appLocale) -> String {
        guard let value, value.isFinite else { return "—" }
        return "$" + compact(value, locale: locale)
    }

    /// Menge mit Kürzel: «20.09M BTC».
    static func supply(_ value: Double?, symbol: String, locale: Locale = LocaleNumbers.appLocale) -> String {
        guard let value, value.isFinite else { return "—" }
        return compact(value, locale: locale) + " " + symbol
    }

    /// Prozent mit zwei Nachkommastellen ohne Vorzeichen: «2.73%», «2,73 %» (Schreibweise je Sprache, `PercentText`).
    static func percent(_ value: Double?, locale: Locale = LocaleNumbers.appLocale) -> String {
        guard let value, value.isFinite else { return "—" }
        return PercentText.unsigned(value, decimals: 2, locale: locale)
    }
}
