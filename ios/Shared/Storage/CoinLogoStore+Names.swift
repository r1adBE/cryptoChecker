import Foundation

// Namen und Kennzeichen der Coins (Teil von `CoinLogoStore`): Aktiennamen (Nasdaq-Symbolliste), Namen aus Listen
// und Rangliste, TradFi-Paare und -Kürzel, Alpha-Kennzeichen und -Token, Art eines Paars.

extension CoinLogoStore {
    // MARK: Aktiennamen (Nasdaq-Symbolliste)

    private static let stockNamesTimeKey = "coin_logos_stock_names_time_v1"
    private static let stockNamesAttemptKey = "coin_logos_stock_names_attempt_v1"
    fileprivate static var stockNamesURL: URL { directory.appendingPathComponent("stock_names1.txt") }

    /// Aktiennamen («CAT» → «Caterpillar, Inc.») aus der offiziellen Nasdaq-Symbolliste (zwei
    /// Textdateien mit allen US-Aktien, für alle gleich) — nur wenn es TradFi-Kürzel gibt; höchstens
    /// einmal pro Woche, nach einem Fehler frühestens nach einer Stunde. true = neue Namen.
    @discardableResult
    func refreshStockNames() async -> Bool {
        guard !Self.tradFiBases.isEmpty else { return false }
        let defaults = SharedStorage.defaults
        let now = EpochTime.nowMillis
        let known = Self.stockNames
        if CoinLogos.isFresh(savedAt: Int64(defaults.double(forKey: Self.stockNamesTimeKey)), now: now,
                             ttl: CoinLogos.stockNamesTTLMillis), !known.isEmpty { return false }
        if CoinLogos.isFresh(savedAt: Int64(defaults.double(forKey: Self.stockNamesAttemptKey)), now: now,
                             ttl: Self.mapRetryMillis) { return false }
        defaults.set(Double(now), forKey: Self.stockNamesAttemptKey)
        var fresh: [String: String] = [:]
        for url in [CoinLogos.nasdaqListedURL, CoinLogos.otherListedURL] {
            guard let text = try? await ExchangeHTTP.call(url) else { continue }
            CoinLogos.parseSymbolDirectory(text, into: &fresh)
        }
        guard !fresh.isEmpty else { return false }
        try? CoinLogos.encodeNames(fresh, order: fresh.keys.sorted())
            .write(to: Self.stockNamesURL, atomically: true, encoding: .utf8)
        defaults.set(Double(now), forKey: Self.stockNamesTimeKey)
        Self.stockNamesBox.store(fresh)
        await CoinLogoRevision.bump()
        return true
    }
}

// MARK: Namen («Bitcoin», «NVIDIA»)

extension CoinLogoStore {
    static let namesBox = NamesBox()
    fileprivate static let stockNamesBox = NamesBox()

    /// Alle US-Aktien der Nasdaq-Symbolliste: Kürzel → Name (leer, bis geladen).
    static var stockNames: [String: String] {
        stockNamesBox.read { CoinLogos.decodeNames(try? String(contentsOf: stockNamesURL, encoding: .utf8)) }
    }

    /// Namen der Coins und TradFi-Paare (Schlüssel `CoinLogos.nameKey`); bis zur ersten Liste aus dem
    /// mitgelieferten Auszug (`CoinMeta.snapshotName`), damit schon eine frische Installation Namen zeigt.
    static var names: [String: String] {
        namesBox.read(
            { CoinLogos.decodeNames(readNonBlank(namesURL)) },
            snapshot: { snapshotMeta.map { CoinMeta.listNames(binance: $0.binance, alpha: $0.alpha).names } ?? [:] }
        )
    }

    /// Mitgelieferter Auszug der Binance-Listen (App und Widgets); nil, wenn nicht lesbar.
    fileprivate static let snapshotMeta: CoinMeta.Meta? = {
        guard let url = Bundle.main.url(forResource: CoinMeta.snapshotName, withExtension: "json") else { return nil }
        return CoinMeta.parse(try? String(contentsOf: url, encoding: .utf8))
    }()

    /// Inhalt der Datei; fehlend, unlesbar oder leer → nil.
    static func readNonBlank(_ url: URL) -> String? {
        guard let text = try? String(contentsOf: url, encoding: .utf8),
              !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return text
    }

    /// Name für ein Paar der Merkliste (TradFi beachtet); nil bei DEX-Pools und unbekannten Coins.
    static func name(for watch: Watch) -> String? {
        guard CoinLogos.allowed(forExchange: watch.exchangeKey) else { return nil }
        let tradFi = tradFiPairs.contains(watch.logoPairKey)
        if let name = names[CoinLogos.nameKey(watch.baseAsset, tradFi: tradFi)] { return name }
        // Aktie ohne Namen in der Binance-Liste: aus der Nasdaq-Symbolliste
        return tradFi ? stockNames[watch.baseAsset.uppercased()] : nil
    }
}

/// Namen im Speicher (einmal aus der Datei gelesen, nach dem Laden der Liste ersetzt).
final class NamesBox: @unchecked Sendable { // @unchecked: veränderlicher Zustand nur unter `lock` (NSLock)
    private let lock = NSLock()
    private var value: [String: String]?
    private var fromSnapshot = false

    func read(_ load: () -> [String: String]) -> [String: String] {
        read(load, snapshot: { [:] })
    }

    /// Wie `read`; ist die Datei leer, gelten die Namen aus `snapshot` (mitgelieferter Auszug).
    func read(_ load: () -> [String: String], snapshot: () -> [String: String]) -> [String: String] {
        lock.lock(); defer { lock.unlock() }
        if let value { return value }
        var loaded = load()
        if loaded.isEmpty {
            loaded = snapshot()
            fromSnapshot = !loaded.isEmpty
        }
        value = loaded
        return loaded
    }

    /// Stammen die Namen nur aus dem mitgelieferten Auszug?
    var isSnapshot: Bool {
        lock.lock(); defer { lock.unlock() }
        return fromSnapshot
    }

    func store(_ names: [String: String]) {
        lock.lock(); value = names; fromSnapshot = false; lock.unlock()
    }
}

// MARK: TradFi-Paare der Merkliste

extension CoinLogoStore {
    private static let tradFiPairsKey = "coin_logos_tradfi_pairs"

    /// Paare der Merkliste, die TradFi sind (`CoinLogos.pairKey`); ihr Logo kommt nur aus dem
    /// TradFi-Namensraum. Im gemeinsamen Speicher, damit auch die Widgets es wissen.
    static var tradFiPairs: Set<String> {
        // Jedes Mal aus dem gemeinsamen Speicher: Die App ändert sie, das Widget liest sie
        Set(SharedStorage.defaults.stringArray(forKey: tradFiPairsKey) ?? [])
    }

    /// Neue Liste; true, wenn sie sich geändert hat.
    @discardableResult
    static func setTradFiPairs(_ pairs: Set<String>) -> Bool {
        guard pairs != tradFiPairs else { return false }
        SharedStorage.defaults.set(pairs.sorted(), forKey: tradFiPairsKey)
        return true
    }

    private static let tradFiBasesKey = "coin_logos_tradfi_bases"

    /// Alle TradFi-Kürzel der gespeicherten Paarlisten der Futures-Börsen der Merkliste (nicht nur
    /// die beobachteten): für sie lädt `syncAll` Aktien-Logos — für alle gleich, so verrät der
    /// Abruf keine einzelne Aktie.
    static var tradFiBases: Set<String> {
        Set(SharedStorage.defaults.stringArray(forKey: tradFiBasesKey) ?? [])
    }

    @discardableResult
    static func setTradFiBases(_ bases: Set<String>) -> Bool {
        guard bases != tradFiBases else { return false }
        SharedStorage.defaults.set(bases.sorted(), forKey: tradFiBasesKey)
        return true
    }

    /// Ein Paar dazu (gerade hinzugefügt); true, wenn es neu war.
    @discardableResult
    static func addTradFiPair(_ pair: String) -> Bool {
        let current = tradFiPairs
        guard !current.contains(pair) else { return false }
        return setTradFiPairs(current.union([pair]))
    }

    /// Mehrere Paare dazu (Sammelaufnahme) — einmal lesen, höchstens einmal schreiben; true, wenn eines neu war.
    @discardableResult
    static func addTradFiPairs(_ pairs: [String]) -> Bool {
        guard !pairs.isEmpty else { return false }
        let current = tradFiPairs
        let merged = current.union(pairs)
        guard merged.count != current.count else { return false }
        return setTradFiPairs(merged)
    }

    /// Schlüssel für die Plakette eines Paars (TradFi oder Krypto).
    static func logoKey(for watch: Watch) -> String {
        CoinLogos.logoKey(watch.baseAsset, tradFi: tradFiPairs.contains(watch.logoPairKey))
    }

    private static let alphaSymbolsKey = "coin_logos_alpha_symbols_v1"
    /// Zeitpunkt der zuletzt geholten Alpha-Liste bzw. des letzten Versuchs allein (`refreshAlphaOnly`).
    static let alphaTimeKey = "coin_logos_alpha_time_v1"
    static let alphaAttemptKey = "coin_logos_alpha_attempt_v1"

    /// Kürzel der Alpha-Token (`CoinLogos.alphaSymbols`): Binance-Alpha-Liste ohne regulär an
    /// Binance Spot gelistete. Kommt mit demselben Logo-Abgleich (kein eigener Abruf) und liegt im gemeinsamen Speicher; leer, bis die Liste sie einmal geliefert hat. Für
    /// das Symbol «α» (`AssetKind`) und «inkl. Alpha-Tokens» — wie Android `alphaSymbols`.
    static var alphaSymbols: Set<String> {
        if let stored = SharedStorage.defaults.stringArray(forKey: alphaSymbolsKey) { return Set(stored) }
        // Nie eine Liste geholt: Alpha-Token aus dem mitgelieferten Auszug
        return snapshotAlphaSymbols
    }

    /// Alpha-Kürzel des mitgelieferten Auszugs (einmal gelesen).
    private static let snapshotAlphaSymbols: Set<String> = {
        guard let meta = snapshotMeta else { return [] }
        return CoinLogos.alphaSymbols(meta.alpha)
    }()

    /// Wurde die Alpha-Liste schon einmal gespeichert? Sonst einmal die ganze Logo-Liste holen.
    static var hasAlphaSymbols: Bool {
        SharedStorage.defaults.object(forKey: alphaSymbolsKey) != nil
    }

    /// Alpha-Token der zuletzt geholten Alpha-Liste (eigene kleine Datei im App-Group-Ordner).
    static let alphaTokensBox = AlphaTokensBox(url: directory.appendingPathComponent("alpha_tokens1.json"))

    /// Token der Alpha-Liste für die Alpha-Paare von Binance (`BinanceAlpha.pairs`, Kennung `alphaId`) und
    /// «gleicher Token»: gespeichert (nie Netz), nach jeder neuen Alpha-Liste ersetzt — wie Android `alphaTokens`.
    static var alphaTokens: [BinanceAlpha.Token] { alphaTokensBox.value.tokens }

    /// Namen der Alpha-Token je Kürzel (`CoinLogos.alphaNames`) — weniger Fehltreffer bei «α» (`AssetKind`).
    static var alphaNames: [String: String] { alphaTokensBox.value.names }

    /// Neue Alpha-Liste speichern (auch leer: «schon einmal geholt»); true, wenn sie sich geändert hat.
    @discardableResult
    static func setAlphaSymbols(_ symbols: Set<String>) -> Bool {
        let changed = symbols != alphaSymbols
        if changed || !hasAlphaSymbols { SharedStorage.defaults.set(symbols.sorted(), forKey: alphaSymbolsKey) }
        return changed
    }
}

/// Alpha-Token aus der Datei, einmal gelesen und nach jeder neuen Alpha-Liste ersetzt — ohne Actor-Sprung
/// lesbar (Merkliste, Paar-Auswahl).
final class AlphaTokensBox: @unchecked Sendable { // @unchecked: veränderlicher Zustand nur unter `lock` (NSLock)
    private let url: URL
    private let lock = NSLock()
    private var loaded = false
    private var tokens: [BinanceAlpha.Token] = []
    private var names: [String: String] = [:]

    init(url: URL) {
        self.url = url
    }

    var value: (tokens: [BinanceAlpha.Token], names: [String: String]) {
        lock.lock()
        defer { lock.unlock() }
        if !loaded {
            loaded = true
            if let data = try? Data(contentsOf: url),
               let list = try? JSONDecoder().decode([BinanceAlpha.Token].self, from: data) {
                tokens = list
                names = CoinLogos.alphaNames(list)
            }
        }
        return (tokens, names)
    }

    func store(_ list: [BinanceAlpha.Token]) {
        lock.lock()
        tokens = list
        names = CoinLogos.alphaNames(list)
        loaded = true
        lock.unlock()
        if let data = try? JSONEncoder().encode(list) { try? data.write(to: url, options: .atomic) }
    }
}

extension Watch {
    /// Kennung für die Liste der TradFi-Paare (`CoinLogos.pairKey`).
    var logoPairKey: String {
        CoinLogos.pairKey(exchangeKey: exchangeKey, base: baseAsset, quote: quoteAsset, contractType: String(contractType.rawValue))
    }

    /// Art des Paars (`AssetKind.of`) aus den gespeicherten Listen; für viele Paare lieber
    /// `assetKind(tradFiPairs:alphaSymbols:)` mit einmal gelesenen Listen.
    var assetKind: AssetKind {
        assetKind(tradFiPairs: CoinLogoStore.tradFiPairs, alphaSymbols: CoinLogoStore.alphaSymbols,
                  alphaNames: CoinLogoStore.alphaNames,
                  pairName: TokenIdentityStore.shared.pairName(exchangeKey: exchangeKey, base: baseAsset))
    }

    /// Art des Paars: TradFi aus der Liste der TradFi-Paare oder der Gruppe «TradFi» (die Liste pflegt
    /// nur der Logo-Abgleich), Alpha aus der Alpha-Liste bzw. der Kennung eines Binance-Alpha-Paars;
    /// `alphaNames` und `pairName` (Name des Coins an der Börse) gegen Fehltreffer — wie Android
    /// `WatchEntity.assetKind`.
    func assetKind(tradFiPairs: Set<String>, alphaSymbols: Set<String>,
                   alphaNames: [String: String] = [:], pairName: String? = nil) -> AssetKind {
        AssetKind.of(exchangeKey: exchangeKey, base: baseAsset,
                     tradFi: groupName == AutoGroup.tradFi || tradFiPairs.contains(logoPairKey),
                     alphaSymbols: alphaSymbols, pairId: pairId, pairName: pairName, alphaNames: alphaNames)
    }
}
