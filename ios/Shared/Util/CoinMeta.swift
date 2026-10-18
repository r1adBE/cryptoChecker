import Foundation

/// Namen, Alpha-Kennzeichen und Bild-Adressen der Coins: Zusammenführen der Listen und Ersatz, wenn
/// die Binance-Listen direkt nicht kommen (Build 46). Gleich in Android `CoinMeta.kt` (gemeinsame
/// Testfälle `testdata/parity/coin_meta.json`).
///
/// - `merge`: Rangliste (CoinGecko), Binance-Symbolliste und Alpha-Liste → Logo-Adressen, Namen und
///   Alpha-Kürzel, mit derselben Vorrangregel wie bisher (CoinGecko Rang 1–1000 → Binance → Alpha →
///   CoinGecko Rang 1001–2500; erstes Vorkommen gewinnt).
/// - Ersatz `url`: `coin_meta.json` der Release «data» auf GitHub, stündlich von der GitHub Action
///   «Market data» gebaut — **nur** aus den öffentlichen Binance-Listen (Kürzel, Namen, Bild-Adressen,
///   Alpha-Kennzeichen; keine Bilder, keine CoinGecko-Daten). Die App holt sie nur, wenn die direkte
///   Binance- oder Alpha-Liste ausfällt (`CoinLists`); Bilder kommen weiter direkt von `bin.bnbstatic.com`.
/// - Mitgeliefert `snapshotName`: kleiner Auszug derselben Datei (Binance-Kürzel und Alpha-Token, erzeugt
///   mit `android/tools/gen_coin_assets.py`), damit eine frische Installation ohne Netz sofort Namen und
///   Alpha-Kennzeichen zeigt.
enum CoinMeta {
    static let file = "coin_meta.json"
    static let url = DataMirror.base + file

    /// Mitgelieferter Auszug (`Shared/Resources`, Android `assets/`).
    static let snapshotName = "coin_meta_snapshot"
    static let snapshotFile = snapshotName + ".json"

    static let version = 1

    /// Ersatzdatei höchstens so alt (Action ausgefallen: lieber Initialen als uralte Alpha-Liste).
    static let maxAgeMillis: Int64 = 14 * 24 * 60 * 60 * 1000

    /// Direkt geholte Binance-Listen gelten so lange; älter (oder gescheitert) → Ersatzdatei.
    static let directStaleMillis: Int64 = 48 * 60 * 60 * 1000

    /// Zeitpunkt in der Zukunft bis zu so viel gilt noch (Uhren gehen nicht genau gleich).
    private static let clockSkewMillis: Int64 = 5 * 60 * 1000

    /// Zeile der CoinGecko-Rangliste (`/coins/markets`): Kürzel, Bild, Name.
    struct GeckoRow: Equatable {
        var symbol: String
        var image: String?
        var name: String?
    }

    /// Gelesene Ersatzdatei; `generatedAt` in ms (0 = unbekannt).
    struct Meta {
        var generatedAt: Int64
        var binance: [CoinLogos.BinanceEntry]
        var alpha: [CoinLogos.AlphaEntry]
    }

    /// Ergebnis von `merge` mit Reihenfolge; `alpha` = nil, wenn keine Alpha-Liste vorlag.
    struct Lists {
        var map: [String: String] = [:]
        var order: [String] = []
        var names: [String: String] = [:]
        var nameOrder: [String] = []
        var alpha: Set<String>?
    }

    /// Seite der Rangliste (JSON-Array) → Zeilen; nil = unlesbar.
    static func parseMarketsPage(_ text: String?) -> [GeckoRow]? {
        guard let text, let array = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [Any] else { return nil }
        var out: [GeckoRow] = []
        for item in array {
            guard let o = item as? [String: Any] else { continue }
            out.append(GeckoRow(symbol: o["symbol"] as? String ?? "", image: o["image"] as? String, name: o["name"] as? String))
        }
        return out
    }

    /// Antwort der Binance-Symbolliste (`{"data":[…]}`) → Einträge; nil = unbrauchbar (kein `data`-Array).
    static func parseBinanceList(_ text: String?) -> [CoinLogos.BinanceEntry]? {
        guard let text,
              let object = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any],
              let array = object["data"] as? [Any]
        else { return nil }
        return binanceEntries(array)
    }

    /// Einträge eines Binance-`data`-Arrays; `name` leer → `baseAsset` — wie Android.
    static func binanceEntries(_ array: [Any]) -> [CoinLogos.BinanceEntry] {
        var out: [CoinLogos.BinanceEntry] = []
        for item in array {
            guard let o = item as? [String: Any] else { continue }
            let rawName = o["name"] as? String ?? ""
            let name = rawName.isEmpty ? (o["baseAsset"] as? String ?? "") : rawName
            let tags = (o["tags"] as? [Any] ?? []).compactMap { $0 as? String }
            out.append(CoinLogos.BinanceEntry(name: name,
                                              logo: o["logo"] as? String,
                                              tags: tags,
                                              onlyFutures: o["onlyFutures"] as? Bool ?? false,
                                              fullName: o["fullName"] as? String))
        }
        return out
    }

    /// Ersatzdatei bzw. mitgelieferter Auszug → `Meta`; nil bei falscher Version, unlesbar oder ohne
    /// jeden Eintrag — wie Android `parse`.
    static func parse(_ text: String?) -> Meta? {
        guard let text,
              let root = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any]
        else { return nil }
        guard let v = root["v"] as? NSNumber, v.intValue == version else { return nil }
        let binance = binanceEntries(root["binance"] as? [Any] ?? [])
        let alpha = CoinLogos.alphaEntries(root["alpha"] as? [Any] ?? [])
        if binance.isEmpty && alpha.isEmpty { return nil }
        let generated = (root["generated"] as? NSNumber)?.int64Value ?? 0
        return Meta(generatedAt: max(0, generated), binance: binance, alpha: alpha)
    }

    /// Ersatzdatei aus dem Netz brauchbar? Erzeugt vor höchstens `maxAgeMillis`, nicht in der Zukunft.
    static func isUsable(_ meta: Meta, now: Int64) -> Bool {
        meta.generatedAt > 0 && meta.generatedAt <= now + clockSkewMillis && now - meta.generatedAt < maxAgeMillis
    }

    /// Ersatzdatei holen? Wenn die direkte Liste fehlte (`directOk` false) oder ihr letzter direkter
    /// Erfolg (`lastDirectAt`, ms) älter als `directStaleMillis` ist.
    static func needed(directOk: Bool, lastDirectAt: Int64, now: Int64) -> Bool {
        !directOk || !CoinLogos.isFresh(savedAt: lastDirectAt, now: now, ttl: directStaleMillis)
    }

    /// Listen zusammenführen, Vorrang wie bisher: `geckoTop` (Rang 1–1000), dann Binance (Lücken,
    /// TradFi gegen `crypto` bzw. die Kürzel von `geckoTop`), dann Alpha (Lücken), zuletzt `geckoExtra`.
    static func merge(geckoTop: [GeckoRow], binance: [CoinLogos.BinanceEntry]?, alpha: [CoinLogos.AlphaEntry]?,
                      geckoExtra: [GeckoRow] = [], crypto: Set<String>? = nil) -> Lists {
        var out = Lists()
        CoinLogos.pick(geckoTop.map { (symbol: $0.symbol, image: $0.image) }, into: &out.map, order: &out.order)
        CoinLogos.pickNames(geckoTop.map { (symbol: $0.symbol, name: $0.name) }, into: &out.names, order: &out.nameOrder)
        // Krypto-Kürzel für die TradFi-Regel: CoinGecko, sonst (nur Binance-Listen) `listCrypto`
        let cryptoKeys: Set<String> = crypto ?? Set(out.map.keys)
        if let binance {
            CoinLogos.pick(binance.map { (symbol: $0.name, image: $0.logo) }, into: &out.map, order: &out.order)
            CoinLogos.pickTradFi(binance, crypto: cryptoKeys, into: &out.map, order: &out.order)
            CoinLogos.pickNames(binance.map { (symbol: $0.name, name: $0.fullName) }, into: &out.names, order: &out.nameOrder)
            CoinLogos.pickTradFiNames(binance, crypto: cryptoKeys, into: &out.names, order: &out.nameOrder)
        }
        if let alpha {
            let symbols = CoinLogos.applyAlpha(alpha, into: &out.map, order: &out.order,
                                               names: &out.names, nameOrder: &out.nameOrder)
            out.alpha = symbols
        }
        CoinLogos.pick(geckoExtra.map { (symbol: $0.symbol, image: $0.image) }, into: &out.map, order: &out.order)
        CoinLogos.pickNames(geckoExtra.map { (symbol: $0.symbol, name: $0.name) }, into: &out.names, order: &out.nameOrder)
        return out
    }

    /// Krypto-Kürzel ohne CoinGecko-Rangliste: Spot-Einträge der Binance-Liste (nicht nur Futures, keine
    /// tokenisierten Aktien), gross — wie Android `listCrypto`.
    static func listCrypto(_ binance: [CoinLogos.BinanceEntry]?) -> Set<String> {
        var out = Set<String>()
        for e in binance ?? [] where !e.onlyFutures {
            let stock = e.tags.contains { $0.caseInsensitiveCompare(CoinLogos.stockTag) == .orderedSame }
            if !stock { out.insert(e.name.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()) }
        }
        return out
    }

    /// Namen nur aus den Binance-Listen (ohne Rangliste), Vorrang wie `merge` — wie Android `listNames`.
    static func listNames(binance: [CoinLogos.BinanceEntry]?, alpha: [CoinLogos.AlphaEntry]?) -> Lists {
        merge(geckoTop: [], binance: binance, alpha: alpha, crypto: listCrypto(binance))
    }

    /// `Meta` als Datei (Format wie `coin_meta.json`; die App speichert so die zuletzt geholten Listen).
    static func encode(_ meta: Meta) -> String {
        var binance: [[String: Any]] = []
        for e in meta.binance {
            var o: [String: Any] = ["name": e.name]
            if let v = e.fullName { o["fullName"] = v }
            if let v = e.logo { o["logo"] = v }
            if !e.tags.isEmpty { o["tags"] = e.tags }
            if e.onlyFutures { o["onlyFutures"] = true }
            binance.append(o)
        }
        var alpha: [[String: Any]] = []
        for e in meta.alpha {
            var o: [String: Any] = ["symbol": e.symbol]
            if let v = e.name { o["name"] = v }
            if let v = e.icon { o["iconUrl"] = v }
            if let v = e.marketCap, v.isFinite { o["marketCap"] = v }
            if e.offline { o["offline"] = true }
            if e.listingCex { o["listingCex"] = true }
            if let v = e.alphaId { o["alphaId"] = v }
            alpha.append(o)
        }
        let root: [String: Any] = ["v": version, "generated": meta.generatedAt, "binance": binance, "alpha": alpha]
        guard let data = try? JSONSerialization.data(withJSONObject: root) else { return "" }
        return String(decoding: data, as: UTF8.self)
    }

    /// Binance-Namen für «gleicher Token» (K-8): Kürzel gross → `fullName`, Spot vor Futures.
    /// Binance-Liste aus der Ersatzdatei gegen die gespeicherte `saved` abwägen: Die Ersatzdatei kann nur
    /// Kürzel tragen (ohne `fullName`, Bild-Adressen, Schlagworte). Eine Liste ohne Namen ersetzt nie eine
    /// mit Namen — sonst wären nach 48 h ohne direkte Liste Namen und Logos weg —, sie ergänzt nur Kürzel,
    /// die dort fehlen (hinten angehängt). Trägt die Ersatzdatei Namen oder `saved` keine, gilt sie ganz.
    /// Wie Android `mirrorBinance`.
    static func mirrorBinance(_ mirror: [CoinLogos.BinanceEntry], saved: [CoinLogos.BinanceEntry]) -> [CoinLogos.BinanceEntry] {
        if hasNames(mirror) || !hasNames(saved) { return mirror }
        var known = Set(saved.map { symbolKey($0.name) })
        return saved + mirror.filter { known.insert(symbolKey($0.name)).inserted }
    }

    /// Trägt mindestens ein Eintrag einen Namen (`fullName`)?
    static func hasNames(_ entries: [CoinLogos.BinanceEntry]) -> Bool {
        entries.contains { !($0.fullName?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true) }
    }

    private static func symbolKey(_ name: String) -> String {
        name.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    }

    static func binanceFullNames(_ entries: [CoinLogos.BinanceEntry]) -> [String: String] {
        var out: [String: String] = [:]
        for e in entries.filter({ !$0.onlyFutures }) + entries.filter({ $0.onlyFutures }) {
            let k = e.name.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            if let full = e.fullName, out[k] == nil { out[k] = full }
        }
        return out
    }
}
