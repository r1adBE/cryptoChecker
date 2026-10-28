import Foundation
import os

/// Faktoren der Phemex-Spot-Produkte aus `/public/products` — mit der Paarliste geladen, im Speicher gehalten und als
/// Datei in der App Group abgelegt (`storeURL`). Wie `PhemexScales` (Android): `priceScale` je Symbol (Ep; ohne Feld kein
/// Kurs — nie geraten), `valueScale` je Währung (Ev, Liste `currencies`), `pairs` Symbol → Basis/Kotierung. Ohne
/// Produktliste (`loaded` false) zeigt Phemex keinen Kurs; die Sammelabfrage holt dann zuerst die Liste — nie ein
/// geratener Faktor. `documentedValueScales` gelten nur für Währungen, die die Liste nicht nennt.
struct PhemexScales: Sendable {
    var priceScale: [String: Int] = [:]
    var valueScale: [String: Int] = [:]
    var pairs: [String: (base: String, quote: String)] = [:]
    /// Aus der Produktliste (oder ihrer gespeicherten Kopie); false = nie geladen.
    var loaded = false
    /// Zeitpunkt der Produktliste in ms; 0 = unbekannt (ältere Datei) → gilt als veraltet.
    var loadedAt: Int64 = 0

    static let defaultScale = 8
    static let formatVersion = 1
    /// Fehlertext, solange die Produktliste fehlt (lieber kein Kurs als einer um Zehnerpotenzen daneben).
    static let notLoaded = "Phemex: product list not loaded yet"
    /// Fehlertext für ein Symbol ohne `priceScale` in der Produktliste. Wie Android `UNKNOWN_SCALE`.
    static func unknownScale(_ symbol: String) -> String { "Phemex: no price scale for \(symbol) in the product list" }
    /// Fehlertext der Einzelabfrage, deren erste Anfrage eben die fehlende Produktliste geladen hat.
    static let justLoaded = "Phemex: product list loaded, price follows with the next update"
    /// Faktoren einmal täglich neu laden.
    static let refreshMillis: Int64 = 24 * 60 * 60 * 1000
    /// Public-Spot-API, Tabelle «All Spot trading currencies»: Wertfaktoren ungleich 8.
    static let documentedValueScales: [String: Int] = ["SHIB": 2, "VPAD": 4]
    /// Kotierungen, an denen ein unbekanntes Symbol getrennt wird.
    private static let quoteSuffixes = ["USDT", "USDC", "BTC", "ETH", "TRY", "BRZ"]

    /// Fehlt oder älter als `refreshMillis` (oder Zeitpunkt in der Zukunft): neu laden. Wie Android.
    func isStale(now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> Bool {
        guard loaded else { return true }
        let age = now - loadedAt
        return age < 0 || age >= Self.refreshMillis
    }

    // MARK: Geteilter Stand

    /// Geteilte Faktoren, ob die Datei schon versucht wurde, und die Ablage — gemeinsam hinter einer Sperre.
    private struct SharedState: Sendable {
        var scales = PhemexScales()
        var diskChecked = false
        var diskFile: URL?
    }

    private static let state = OSAllocatedUnfairLock(
        initialState: SharedState(diskFile: SharedStorage.directory.appendingPathComponent("phemex_scales.json")))

    /// Ablage auf dem Gerät (App Group, auch für die Widgets); nil = nur im Speicher (Tests). Neu setzen erlaubt
    /// einen neuen Ladeversuch. Wie Android `PhemexScales.store`.
    static var storeURL: URL? {
        get { state.withLock { $0.diskFile } }
        set {
            state.withLock {
                $0.diskFile = newValue
                $0.diskChecked = false
            }
        }
    }

    /// Zuletzt aus der Produktliste gelesene Faktoren (für alle). Noch nichts geladen: einmal die Datei versuchen.
    /// Neue Faktoren aus der Produktliste werden sofort abgelegt.
    static var current: PhemexScales {
        get {
            state.withLock { s in
                guard !s.scales.loaded, !s.diskChecked else { return s.scales }
                s.diskChecked = true
                if let file = s.diskFile, let bytes = try? Data(contentsOf: file),
                   let fromDisk = decode(String(decoding: bytes, as: UTF8.self)) {
                    s.scales = fromDisk
                }
                return s.scales
            }
        }
        set {
            let target: URL? = state.withLock {
                $0.scales = newValue
                return $0.diskFile
            }
            guard newValue.loaded, let file = target, let bytes = newValue.encode().data(using: .utf8) else { return }
            try? bytes.write(to: file, options: .atomic)
        }
    }

    // MARK: Datei

    /// Dateiform `{"v":1,"t":…,"price":{…},"value":{…},"pairs":{"sBTCUSDT":["BTC","USDT"]}}` — wie Android.
    func encode() -> String {
        let pairLists = pairs.mapValues { [$0.base, $0.quote] }
        let root: [String: Any] = ["v": Self.formatVersion, "t": loadedAt, "price": priceScale, "value": valueScale,
                                   "pairs": pairLists]
        guard let bytes = try? JSONSerialization.data(withJSONObject: root, options: [.sortedKeys]) else { return "" }
        return String(decoding: bytes, as: UTF8.self)
    }

    /// Gegenstück zu `encode`; andere Version, unlesbar oder ganz ohne Angaben → nil.
    static func decode(_ text: String?) -> PhemexScales? {
        guard let text, !text.isEmpty, let root = try? JObject(string: text), root.optInt("v") == formatVersion else {
            return nil
        }
        /// Ganzzahlen eines Unterobjekts; null-Werte fallen weg.
        func scales(_ field: String) -> [String: Int] {
            guard let table = root.optObject(field) else { return [:] }
            var out: [String: Int] = [:]
            for key in table.keys where table.has(key) { out[key] = table.optInt(key, defaultScale) }
            return out
        }
        var result = PhemexScales()
        result.priceScale = scales("price")
        result.valueScale = scales("value")
        if let table = root.optObject("pairs") {
            for symbol in table.keys {
                guard let legs = table.optArray(symbol) else { continue }
                let base = legs.optString(0)
                let quote = legs.optString(1)
                if !base.isEmpty && !quote.isEmpty { result.pairs[symbol] = (base: base, quote: quote) }
            }
        }
        guard !(result.priceScale.isEmpty && result.valueScale.isEmpty && result.pairs.isEmpty) else { return nil }
        result.loaded = true
        result.loadedAt = root.optLong("t", 0)
        return result
    }

    // MARK: Abfragen

    /// Teiler der Preise (Ep) von `symbol`; nil, wenn die Produktliste für das Symbol keinen `priceScale` nennt
    /// (fehlt in der Liste, neu gelistet). Dann zeigt das Paar einen Fehler: 8 zu raten läge bei anderen Faktoren
    /// Zehnerpotenzen daneben. Wie Android.
    func priceDivisor(_ symbol: String) -> Double? { priceScale[symbol].map { Self.tenToThe($0) } }

    /// Teiler der Werte (Ev) in `currency`; unbekannt: dokumentierte Ausnahme, sonst 8.
    func valueDivisor(_ currency: String?) -> Double {
        guard let code = currency?.uppercased() else { return Self.tenToThe(Self.defaultScale) }
        return Self.tenToThe(valueScale[code] ?? Self.documentedValueScales[code] ?? Self.defaultScale)
    }

    /// Basis und Kotierung von `symbol`: aus der Produktliste, sonst `base`/`quote`, sonst am Symbol geschätzt.
    func currencies(_ symbol: String, base: String?, quote: String?) -> (base: String?, quote: String?) {
        if let listed = pairs[symbol] { return (listed.base, listed.quote) }
        if let base, let quote, !base.isEmpty, !quote.isEmpty { return (base, quote) }
        let bare = symbol.hasPrefix("s") ? String(symbol.dropFirst()) : symbol
        for suffix in Self.quoteSuffixes where bare.count > suffix.count && bare.hasSuffix(suffix) {
            return (String(bare.dropLast(suffix.count)), suffix)
        }
        return (nil, nil)
    }

    /// Faktoren aus dem Objekt `data` von `/public/products`; fehlende Felder bleiben weg (dann Standard).
    static func parse(_ data: JObject, now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> PhemexScales {
        var result = PhemexScales()
        result.loaded = true
        result.loadedAt = now
        for currency in data.optArray("currencies")?.optObjects() ?? [] {
            let code = currency.optString("currency").uppercased()
            guard !code.isEmpty, currency.has("valueScale") else { continue }
            result.valueScale[code] = currency.optInt("valueScale", defaultScale)
        }
        for product in data.optArray("products")?.optObjects() ?? [] where product.optString("type") == "Spot" {
            let symbol = product.optString("symbol")
            if symbol.isEmpty { continue }
            if product.has("priceScale") { result.priceScale[symbol] = product.optInt("priceScale", defaultScale) }
            let base = product.optString("baseCurrency")
            let quote = product.optString("quoteCurrency")
            if !base.isEmpty && !quote.isEmpty { result.pairs[symbol] = (base.uppercased(), quote.uppercased()) }
        }
        return result
    }

    /// 10 hoch `scale`, begrenzt auf 0…18.
    private static func tenToThe(_ scale: Int) -> Double { pow(10, Double(min(max(scale, 0), 18))) }
}
