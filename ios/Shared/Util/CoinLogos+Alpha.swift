import Foundation

// Binance-Alpha-Liste (Teil von `CoinLogos`) — wie `CoinLogosAlpha.kt` (Android).

extension CoinLogos {
    /// Kürzel der Alpha-Token (`AssetKind.ALPHA`): handelbare Einträge der Alpha-Liste (nicht
    /// `offline`), die Binance nicht regulär listet (`listingCex`), wie `normalize` — wie Android.
    static func alphaSymbols(_ entries: [AlphaEntry]) -> Set<String> {
        var out = Set<String>()
        for e in entries where !e.offline && !e.listingCex {
            let key = normalize(e.symbol)
            if !key.isEmpty, fileName(key) != nil { out.insert(key) }
        }
        return out
    }

    /// Nur die Alpha-Liste holen (ohne Rangliste, ohne Bilder)? Wenn alle Logo-Schalter (`logos`) und
    /// «Namen anzeigen» (`names`) aus sind, «inkl. Alpha-Tokens» (`includeAlpha`) aber an — wie Android.
    static func alphaOnly(logos: Bool, names: Bool, includeAlpha: Bool) -> Bool {
        !logos && !names && includeAlpha
    }

    /// Alpha-Liste allein nicht erreichbar (Server): frühestens nach einer Stunde erneut.
    static let alphaRetryMillis: Int64 = 60 * 60 * 1000

    /// Alpha-Liste allein fällig (`alphaOnly`)? Nein, solange sie gespeichert (`hasSymbols`) und höchstens
    /// einen Tag alt ist (`savedAt`, wie `mapTTLMillis`) oder der letzte Versuch (`attemptAt`) weniger als
    /// `alphaRetryMillis` zurückliegt — wie Android `alphaListDue`.
    static func alphaListDue(savedAt: Int64, attemptAt: Int64, now: Int64, hasSymbols: Bool) -> Bool {
        !(hasSymbols && isFresh(savedAt: savedAt, now: now)) && !isFresh(savedAt: attemptAt, now: now, ttl: alphaRetryMillis)
    }

    /// Token für die Alpha-Paare von Binance (`BinanceAlpha.pairs`) und «gleicher Token» (`alphaNames`):
    /// handelbare, nicht regulär gelistete Einträge (wie `alphaSymbols`) in der Reihenfolge von `entries`
    /// (`rankAlpha`: bei gleichem Kürzel der bekanntere zuerst) — wie Android.
    static func alphaTokens(_ entries: [AlphaEntry]) -> [BinanceAlpha.Token] {
        entries.filter { !$0.offline && !$0.listingCex }
            .map { BinanceAlpha.Token(symbol: $0.symbol, name: $0.name, alphaId: $0.alphaId) }
    }

    /// Neue Alpha-Liste ohne Kennungen (Ersatzdatei `coin_meta.json` führt kein `alphaId`): Kennungen der
    /// bisherigen Liste `saved` je Kürzel übernehmen, damit die Alpha-Paare nicht bis zum nächsten
    /// direkten Abruf verschwinden. Einträge mit eigener Kennung bleiben unverändert — wie Android.
    static func keepAlphaIds(_ fresh: [AlphaEntry], saved: [AlphaEntry]) -> [AlphaEntry] {
        var ids: [String: String] = [:]
        for e in saved { if let id = e.alphaId, ids[e.symbol] == nil { ids[e.symbol] = id } }
        if ids.isEmpty { return fresh }
        return fresh.map { e in
            guard e.alphaId == nil, let id = ids[e.symbol] else { return e }
            var copy = e
            copy.alphaId = id
            return copy
        }
    }

    /// Hat die Liste Kennungen für die Alpha-Paare (sonst einmal direkt neu holen, z. B. nach dem Update)?
    static func hasAlphaIds(_ entries: [AlphaEntry]?) -> Bool {
        entries?.contains { $0.alphaId != nil } ?? false
    }

    /// Namen der Alpha-Token je Kürzel (wie `normalize`); bei gleichem Kürzel gilt der erste.
    static func alphaNames(_ tokens: [BinanceAlpha.Token]) -> [String: String] {
        var out: [String: String] = [:]
        for t in tokens {
            guard let name = t.name?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty else { continue }
            let key = normalize(t.symbol)
            if !key.isEmpty && out[key] == nil { out[key] = name }
        }
        return out
    }

    /// Logos und Namen der Alpha-Liste (`entries` in `rankAlpha`-Reihenfolge) füllen nur Lücken
    /// (Vorhandenes von CoinGecko und Binance bleibt, wie `pick`); Ergebnis: die Alpha-Kürzel
    /// (`alphaSymbols`). Der Abgleich holt die Alpha-Liste zuerst und speichert die Kürzel sofort,
    /// wendet die Logos aber erst nach CoinGecko und Binance an — wie Android `applyAlpha`.
    @discardableResult
    static func applyAlpha(_ entries: [AlphaEntry],
                           into map: inout [String: String], order: inout [String],
                           names: inout [String: String], nameOrder: inout [String]) -> Set<String> {
        pick(entries.map { (symbol: $0.symbol, image: $0.icon) }, into: &map, order: &order)
        pickNames(entries.map { (symbol: $0.symbol, name: $0.name) }, into: &names, order: &nameOrder)
        return alphaSymbols(entries)
    }

    /// Antwort der Alpha-Liste (`alphaListURL`, `{"code":"000000","data":[…]}`) → Einträge in
    /// `rankAlpha`-Reihenfolge; nil = unbrauchbar (kein `data`-Array). Kürzel über `cleanSymbol`,
    /// `marketCap` als Text oder Zahl, `iconUrl` auch relativ (`alphaIconURL`); Einträge ohne Kürzel
    /// fallen weg, ein kaputter Eintrag kostet nie die ganze Liste — wie Android `parseAlpha`.
    static func parseAlpha(_ text: String?) -> [AlphaEntry]? {
        guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let object = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any],
              let array = object["data"] as? [Any]
        else { return nil }
        return alphaEntries(array)
    }

    /// Einträge eines Alpha-`data`-Arrays (auch aus `coin_meta.json`, `CoinMeta`) wie `parseAlpha`.
    static func alphaEntries(_ array: [Any]) -> [AlphaEntry] {
        var out: [AlphaEntry] = []
        for item in array {
            guard let entry = item as? [String: Any] else { continue }
            let symbol = cleanSymbol(entry["symbol"] as? String)
            if symbol.isEmpty { continue }
            out.append(AlphaEntry(symbol: symbol,
                                  name: entry["name"] as? String,
                                  icon: alphaIconURL(entry["iconUrl"] as? String),
                                  marketCap: jsonNumber(entry["marketCap"]),
                                  offline: jsonBool(entry["offline"]),
                                  listingCex: jsonBool(entry["listingCex"]),
                                  alphaId: BinanceAlpha.alphaId(entry["alphaId"] as? String)))
        }
        return rankAlpha(out)
    }

    /// Kürzel aus einem Token-Vertrag: ohne Leerraum (auch geschütztes Leerzeichen) und ohne
    /// unsichtbare Formatzeichen (z. B. Zero-Width-Space) — wie Android `cleanSymbol`.
    static func cleanSymbol(_ raw: String?) -> String {
        var out = String.UnicodeScalarView()
        for scalar in (raw ?? "").unicodeScalars
        where !scalar.properties.isWhitespace && scalar.properties.generalCategory != .format {
            out.append(scalar)
        }
        return String(out)
    }

    /// Bild-Adresse der Alpha-Liste: relativ («/images/…») → Bild-Server von Binance; sonst unverändert.
    static func alphaIconURL(_ raw: String?) -> String? {
        let s = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { return nil }
        return s.hasPrefix("/") && !s.hasPrefix("//") ? "https://bin.bnbstatic.com" + s : s
    }

    /// Wahrheitswert wie `optBoolean` (Android): JSON-true/false oder Text «true»/«false», sonst false.
    private static func jsonBool(_ value: Any?) -> Bool {
        if let n = value as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() { return n.boolValue }
        if let s = value as? String { return s.lowercased() == "true" }
        return false
    }

    /// Zahl aus Text oder JSON-Zahl (nicht true/false); sonst nil.
    private static func jsonNumber(_ value: Any?) -> Double? {
        if let s = value as? String { return Double(s.trimmingCharacters(in: .whitespacesAndNewlines)) }
        if let n = value as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() { return n.doubleValue }
        return nil
    }

    /// Reihenfolge für `pick`/`pickNames`: handelbare vor abgemeldeten, dann grösste
    /// Marktkapitalisierung (unbekannt zuletzt); sonst Reihenfolge der Liste — wie Android.
    static func rankAlpha(_ entries: [AlphaEntry]) -> [AlphaEntry] {
        func cap(_ e: AlphaEntry) -> Double {
            guard let c = e.marketCap, c.isFinite, c >= 0 else { return -1 }
            return c
        }
        return entries.enumerated().sorted { a, b in
            let oa = a.element.offline ? 1 : 0, ob = b.element.offline ? 1 : 0
            if oa != ob { return oa < ob }
            let ca = cap(a.element), cb = cap(b.element)
            if ca != cb { return ca > cb }
            return a.offset < b.offset
        }.map(\.element)
    }
}
