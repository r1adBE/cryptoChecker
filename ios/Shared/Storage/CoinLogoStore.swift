import Combine
import Foundation
import UIKit

/// Coin-Logos (Regeln in `CoinLogos`), gleich wie Android `CoinLogoRepository`.
/// Nichts ist mitgeliefert, und nichts wird einzeln geholt — so erfährt CoinGecko nie, welche
/// Coins in einer Merkliste stehen:
///
/// 1. Eigene Logo-Liste auf GitHub Pages (`CoinLogos.indexURL`, täglich von einer GitHub Action aus
///    CoinGecko, Binance, OKX und Nasdaq gebaut, Krypto und TradFi, Bilder daneben; beim ersten
///    Laden alle Bilder in einem Abruf aus `CoinLogos.packURL`). Nur solange es die Liste nie gab: Rangliste von CoinGecko,
///    Binance-Liste, Alpha-Liste direkt. Höchstens einmal pro Woche, für alle Nutzer gleich. Fehlte
///    eine Quelle, bleibt Bekanntes erhalten (erneut nach einigen Stunden); geänderte Bild-Adressen
///    laden das Bild neu.
/// 2. `syncAll` lädt die Logos **aller** Coins dieser Liste (kleine Fassung, auf
///    `CoinLogos.storedPixels` begrenzt) in den gemeinsamen Ordner (App Group) — so finden auch
///    die Widgets sie ohne Netz. Später fehlen nur neu dazugekommene Coins.
/// 3. Angezeigt wird nur aus Speicher und Datei (`stored`, `logo`); Coins ausserhalb der Liste,
///    DEX-Pools und Fehler → Initialen. Ein Fehlschlag wird einen Tag lang nicht wiederholt.
///
/// Alle drei Schalter «Coin-Logos» aus → `syncAll` wird nicht aufgerufen, also nichts geladen.
actor CoinLogoStore {
    static let shared = CoinLogoStore()

    /// Zuletzt benutzte Bilder; `NSCache` ist threadsicher, daher ohne Actor-Sprung lesbar.
    private static let memory: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.countLimit = 300
        return cache
    }()

    // «v5»: Logo-Liste von GitHub (davor CoinGecko direkt); ältere Installationen holen sie einmal neu
    private static let mapTimeKey = "coin_logos_map_time_v5"
    private static let mapAttemptKey = "coin_logos_map_attempt_v5"
    private static let failedKey = "coin_logos_failed"
    /// Rangliste nicht erreichbar: frühestens nach einer Stunde erneut.
    private static let mapRetryMillis: Int64 = 60 * 60 * 1000
    private static let maxImageBytes = 512 * 1024
    private static let maxParallelDownloads = 4
    /// Anzeigen alle so viele neue Logos auffrischen.
    private static let revisionStep = 25

    private static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 15
        c.timeoutIntervalForResource = 25
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.urlCache = nil
        c.httpAdditionalHeaders = ["User-Agent": "cryptoChecker-iOS/16"]
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()

    private var map: [String: String]?
    private var syncing = false

    // MARK: Ordner

    /// Ordner im App-Group-Container; nicht in Geräte-Backups (lässt sich jederzeit neu laden).
    private static let directory: URL = {
        var url = SharedStorage.directory.appendingPathComponent("coin_logos", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
        return url
    }()

    // «5»: mit Alpha-Liste; eine unvollständige v4-Liste galt eine Woche lang — einmal neu holen
    private static var mapURL: URL { directory.appendingPathComponent("map5.txt") }
    private static var namesURL: URL { directory.appendingPathComponent("names5.txt") }
    private static let oldFiles = ["map4.txt", "names4.txt"]

    // MARK: Anzeigen (nie Netz; auch für Widgets)

    /// Sofort aus dem Speicher; nil = noch nicht geladen.
    static func cached(_ symbol: String) -> UIImage? {
        guard let name = CoinLogos.fileName(symbol) else { return nil }
        return memory.object(forKey: name as NSString)
    }

    /// Aus Speicher oder Datei.
    static func stored(_ symbol: String) -> UIImage? {
        guard let name = CoinLogos.fileName(symbol) else { return nil }
        if let image = memory.object(forKey: name as NSString) { return image }
        let url = directory.appendingPathComponent(name)
        guard let data = try? Data(contentsOf: url), let image = UIImage(data: data) else { return nil }
        memory.setObject(image, forKey: name as NSString)
        return image
    }

    /// Wie `stored`, aber ausserhalb des Haupt-Threads (Datei lesen).
    func logo(_ symbol: String) -> UIImage? {
        Self.stored(symbol)
    }

    /// Logos mehrerer Symbole vom Gerät (Widgets).
    static func storedLogos(_ symbols: [String]) -> [String: UIImage] {
        var out: [String: UIImage] = [:]
        for symbol in Set(symbols) { if let image = stored(symbol) { out[symbol] = image } }
        return out
    }

    // MARK: Abgleich aller Logos

    /// Lädt die Logos aller Coins der Rangliste, die noch fehlen. Läuft nur einmal gleichzeitig;
    /// gibt die Zahl neuer Logos zurück. `images` false («Namen anzeigen» ohne Logos): nur die
    /// Liste mit den Namen, keine Bilder.
    @discardableResult
    func syncAll(images: Bool = true) async -> Int {
        guard !syncing else { return 0 }
        syncing = true
        defer { syncing = false }

        let current = await symbolMap()
        let fromIndex = CoinLogos.isFromIndex(current)
        // Ohne GitHub-Liste: Aktien-Logos für alle TradFi-Kürzel ohne Eintrag dazu (mit Liste sind sie drin)
        let wanted = fromIndex ? current : CoinLogos.withStockLogos(current, tradFiBases: Self.tradFiBases)
        guard images else { return 0 }
        guard !wanted.isEmpty else { return 0 }
        let now = TimeUtils.nowMillis
        var failed = Self.loadFailures(now: now)
        let fm = FileManager.default
        var missing: [(name: String, url: String)] = wanted.compactMap { symbol, url in
            guard let name = CoinLogos.fileName(symbol), failed[name] == nil,
                  !fm.fileExists(atPath: Self.directory.appendingPathComponent(name).path)
            else { return nil }
            return (name, url)
        }
        guard !missing.isEmpty else { return 0 }

        var added = 0
        // Viele fehlen (erstes Laden): alle Logos in einem Abruf aus dem Paket
        if fromIndex, missing.count >= CoinLogos.packMinMissing {
            let fromPack = await Self.fillFromPack(missing)
            if !fromPack.isEmpty {
                added += fromPack.count
                await CoinLogoRevision.bump()
                missing.removeAll { fromPack.contains($0.name) }
            }
            if missing.isEmpty { return added }
        }
        var pending = missing.makeIterator()
        // Höchstens vier gleichzeitig
        await withTaskGroup(of: (name: String, ok: Bool).self) { group in
            for _ in 0..<Self.maxParallelDownloads {
                guard let item = pending.next() else { break }
                group.addTask { (item.name, await Self.download(name: item.name, url: item.url)) }
            }
            while let result = await group.next() {
                if result.ok {
                    added += 1
                    if added % Self.revisionStep == 0 { await CoinLogoRevision.bump() }
                } else {
                    failed[result.name] = now
                }
                if let item = pending.next() {
                    group.addTask { (item.name, await Self.download(name: item.name, url: item.url)) }
                }
            }
        }
        Self.saveFailures(failed)
        if added % Self.revisionStep != 0 { await CoinLogoRevision.bump() }
        return added
    }

    /// Erster Start: die Logos der Start-Coins (`symbols`) vor dem übrigen Abgleich holen, damit die
    /// Start-Auswahl nicht erst Initialen zeigt und nach ein paar Sekunden umspringt. Die Start-Coins
    /// sind für alle gleich (die grössten Coins) — die Abrufe verraten also nichts über eine
    /// Merkliste; einzelne Coins einer Merkliste werden weiterhin nie einzeln geholt. Wie Android
    /// `ensureStarterLogos`. true = danach sind alle da (fehlt eines, zeigt die Zeile Initialen).
    @discardableResult
    func ensureStarterLogos(_ symbols: [String]) async -> Bool {
        let current = await symbolMap()
        let fm = FileManager.default
        var jobs: [(name: String, url: String)] = []
        var complete = true
        for symbol in symbols {
            guard let name = CoinLogos.fileName(symbol) else { continue }
            if fm.fileExists(atPath: Self.directory.appendingPathComponent(name).path) { continue }
            guard let url = current[symbol.uppercased()] else {
                complete = false
                continue
            }
            jobs.append((name, url))
        }
        guard !jobs.isEmpty else { return complete }
        var added = 0
        await withTaskGroup(of: Bool.self) { group in
            for job in jobs { group.addTask { await Self.download(name: job.name, url: job.url) } }
            for await ok in group {
                if ok { added += 1 } else { complete = false }
            }
        }
        if added > 0 { await CoinLogoRevision.bump() }
        return complete
    }

    /// Erst die kleine Fassung, sonst das Bild aus der Rangliste.
    /// Lädt das Paket (`CoinLogos.packURL`) und legt die Bilder für `missing` ab; gibt die Dateinamen
    /// der abgelegten Bilder zurück (leer bei Fehler).
    private static func fillFromPack(_ missing: [(name: String, url: String)]) async -> Set<String> {
        guard let u = URL(string: CoinLogos.packURL),
              let loaded = try? await session.data(from: u),
              let http = loaded.1 as? HTTPURLResponse, (200..<300).contains(http.statusCode),
              loaded.0.count <= CoinLogos.packMaxBytes,
              let pack = CoinLogos.parsePack(loaded.0)
        else { return [] }
        var done = Set<String>()
        for item in missing where item.url.hasPrefix(CoinLogos.indexBase) {
            guard let bytes = pack[String(item.url.dropFirst(CoinLogos.indexBase.count))],
                  let image = scaled(bytes), let png = image.pngData() else { continue }
            if (try? png.write(to: directory.appendingPathComponent(item.name), options: [.atomic])) != nil {
                done.insert(item.name)
            }
        }
        return done
    }

    private static func download(name: String, url: String) async -> Bool {
        var candidates = [CoinLogos.smallURL(url)]
        if candidates[0] != url { candidates.append(url) }
        for candidate in candidates {
            guard let u = URL(string: candidate),
                  let loaded = try? await session.data(from: u),
                  let http = loaded.1 as? HTTPURLResponse, (200..<300).contains(http.statusCode),
                  loaded.0.count <= maxImageBytes,
                  let image = scaled(loaded.0),
                  let png = image.pngData()
            else { continue }
            if (try? png.write(to: directory.appendingPathComponent(name), options: [.atomic])) != nil {
                return true
            }
        }
        return false
    }

    // MARK: Aktiennamen (Nasdaq-Symbolliste)

    private static let stockNamesTimeKey = "coin_logos_stock_names_time_v1"
    private static let stocksETagKey = "coin_logos_stocks_etag_v1"
    private static let stockNamesAttemptKey = "coin_logos_stock_names_attempt_v1"
    fileprivate static var stockNamesURL: URL { directory.appendingPathComponent("stock_names1.txt") }

    /// Aktiennamen («CAT» → «Caterpillar, Inc.») aus der offiziellen Nasdaq-Symbolliste (zwei
    /// Textdateien mit allen US-Aktien, für alle gleich) — nur wenn es TradFi-Kürzel gibt; höchstens
    /// einmal pro Woche, nach einem Fehler frühestens nach einer Stunde. true = neue Namen.
    @discardableResult
    func refreshStockNames() async -> Bool {
        guard !Self.tradFiBases.isEmpty else { return false }
        let defaults = SharedStorage.defaults
        let now = TimeUtils.nowMillis
        let known = Self.stockNames
        if CoinLogos.isFresh(savedAt: Int64(defaults.double(forKey: Self.stockNamesTimeKey)), now: now), !known.isEmpty { return false }
        if CoinLogos.isFresh(savedAt: Int64(defaults.double(forKey: Self.stockNamesAttemptKey)), now: now,
                             ttl: Self.mapRetryMillis) { return false }
        defaults.set(Double(now), forKey: Self.stockNamesAttemptKey)
        var fresh: [String: String] = [:]
        var stocksETag: String?
        if CoinLogos.isFromIndex(await symbolMap()) {
            // Mit GitHub-Liste: eine Datei mit allen Aktiennamen statt der beiden Nasdaq-Dateien;
            // mit der Kennung des letzten Stands — «304» = unverändert, nichts geladen
            let etag = known.isEmpty ? nil : defaults.string(forKey: Self.stocksETagKey)
            if let url = URL(string: CoinLogos.stocksURL) {
                var request = URLRequest(url: url)
                request.cachePolicy = .reloadIgnoringLocalCacheData
                if let etag { request.setValue(etag, forHTTPHeaderField: "If-None-Match") }
                if let loaded = try? await MarketHTTP.session.data(for: request),
                   let http = loaded.1 as? HTTPURLResponse {
                    if http.statusCode == 304, let etag {
                        stocksETag = etag
                        fresh = known
                    } else if (200..<300).contains(http.statusCode) {
                        stocksETag = http.value(forHTTPHeaderField: "ETag")
                        fresh = CoinLogos.decodeNames(String(data: loaded.0, encoding: .utf8))
                    }
                }
            }
        } else {
            for url in [CoinLogos.nasdaqListedURL, CoinLogos.otherListedURL] {
                guard let text = try? await MarketHTTP.call(url) else { continue }
                CoinLogos.parseSymbolDirectory(text, into: &fresh)
            }
        }
        guard !fresh.isEmpty else { return false }
        try? CoinLogos.encodeNames(fresh, order: fresh.keys.sorted())
            .write(to: Self.stockNamesURL, atomically: true, encoding: .utf8)
        defaults.set(Double(now), forKey: Self.stockNamesTimeKey)
        if let stocksETag { defaults.set(stocksETag, forKey: Self.stocksETagKey) } else { defaults.removeObject(forKey: Self.stocksETagKey) }
        Self.stockNamesBox.store(fresh)
        await CoinLogoRevision.bump()
        return true
    }

    /// Zuordnung aus Datei bzw. frisch von CoinGecko; bei Fehlern die alte (auch abgelaufen).
    private func symbolMap() async -> [String: String] {
        let defaults = SharedStorage.defaults
        let now = TimeUtils.nowMillis
        // Nach dem Update: bis zum ersten Abruf die Liste der Vorgängerfassung
        let known = map ?? CoinLogos.decode(Self.readFirst([Self.mapURL, Self.directory.appendingPathComponent(Self.oldFiles[0])]))
        map = known
        let savedAt = Int64(defaults.double(forKey: Self.mapTimeKey))
        if CoinLogos.isFresh(savedAt: savedAt, now: now), !known.isEmpty { return known }
        let attempt = Int64(defaults.double(forKey: Self.mapAttemptKey))
        if CoinLogos.isFresh(savedAt: attempt, now: now, ttl: Self.mapRetryMillis) { return known }
        defaults.set(Double(now), forKey: Self.mapAttemptKey)

        var fresh: [String: String] = [:]
        var order: [String] = []
        var names: [String: String] = [:]
        var nameOrder: [String] = []
        var complete: Bool
        var etag: String?
        let result = await Self.fetchIndex(knownFromIndex: CoinLogos.isFromIndex(known))
        if result.unchanged {
            // «Nichts geändert» (304): nichts geladen, Bekanntes gilt wieder eine Woche
            defaults.set(Double(now), forKey: Self.mapTimeKey)
            return known
        }
        if let index = result.index {
            etag = result.etag
            // 1. Eigene Logo-Liste auf GitHub: alle Logos und Namen (Krypto und TradFi) in einer Datei
            fresh = index.logos
            order = index.logos.keys.sorted()
            names = index.names
            nameOrder = index.names.keys.sorted()
            complete = true
        } else if CoinLogos.isFromIndex(known) {
            // Schon auf der GitHub-Liste: nicht auf die alten Quellen wechseln — später erneut
            return known
        } else {
            // 2. Ersatz, solange es die Liste nie gab: CoinGecko, Binance, Alpha direkt
            complete = await Self.fetchCoinGecko(pages: 1...CoinLogos.pages, into: &fresh, order: &order,
                                                 names: &names, nameOrder: &nameOrder)
            let crypto = Set(fresh.keys)
            if !(await Self.fetchBinance(into: &fresh, order: &order, names: &names, nameOrder: &nameOrder,
                                         crypto: crypto, tradFiOnly: false)) {
                complete = false
            }
            if !(await Self.fetchAlpha(into: &fresh, order: &order, names: &names, nameOrder: &nameOrder)) {
                complete = false
            }
            // Kleinere Coins (Rang 1001–2500) nur noch für die Lücken
            let extra = (CoinLogos.pages + 1)...(CoinLogos.pages + CoinLogos.extraPages)
            if !(await Self.fetchCoinGecko(pages: extra, into: &fresh, order: &order,
                                           names: &names, nameOrder: &nameOrder)) {
                complete = false
            }
        }
        guard !fresh.isEmpty else { return known }
        // Unvollständig (eine Quelle fehlte): Bekanntes behalten und in einigen Stunden erneut
        if !complete {
            CoinLogos.withKnown(&fresh, order: &order, known: known)
            CoinLogos.withKnown(&names, order: &nameOrder, known: Self.names)
        }
        try? CoinLogos.encode(fresh, order: order).write(to: Self.mapURL, atomically: true, encoding: .utf8)
        for old in Self.oldFiles { try? FileManager.default.removeItem(at: Self.directory.appendingPathComponent(old)) }
        if !names.isEmpty {
            try? CoinLogos.encodeNames(names, order: nameOrder).write(to: Self.namesURL, atomically: true, encoding: .utf8)
            Self.namesBox.store(names)
            await CoinLogoRevision.bump()
        }
        defaults.set(Double(CoinLogos.savedAt(now: now, complete: complete)), forKey: Self.mapTimeKey)
        if let etag { defaults.set(etag, forKey: Self.indexETagKey) } else { defaults.removeObject(forKey: Self.indexETagKey) }
        // Geänderte Bild-Adressen (anderes Logo, andere Quelle): gespeichertes Bild neu laden
        let changed = CoinLogos.changedKeys(old: known, new: fresh)
        if !changed.isEmpty {
            for key in changed {
                guard let name = CoinLogos.fileName(key) else { continue }
                Self.memory.removeObject(forKey: name as NSString)
                try? FileManager.default.removeItem(at: Self.directory.appendingPathComponent(name))
            }
            defaults.removeObject(forKey: Self.failedKey)
        }
        map = fresh
        return fresh
    }

    private static let indexETagKey = "coin_logos_index_etag_v5"

    /// Logo-Liste von GitHub Pages (`CoinLogos.indexURL`). Mit der Kennung des letzten Stands
    /// («If-None-Match»): ist nichts neu, antwortet GitHub nur «304» ohne Inhalt.
    private static func fetchIndex(knownFromIndex: Bool) async
        -> (index: CoinLogos.Index?, unchanged: Bool, etag: String?) {
        guard let url = URL(string: CoinLogos.indexURL) else { return (nil, false, nil) }
        let etag = knownFromIndex ? SharedStorage.defaults.string(forKey: indexETagKey) : nil
        var request = URLRequest(url: url)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        if let etag { request.setValue(etag, forHTTPHeaderField: "If-None-Match") }
        guard let loaded = try? await MarketHTTP.session.data(for: request),
              let http = loaded.1 as? HTTPURLResponse else { return (nil, false, nil) }
        let data = loaded.0
        if http.statusCode == 304, etag != nil { return (nil, true, etag) }
        guard (200..<300).contains(http.statusCode) else { return (nil, false, nil) }
        return (CoinLogos.parseIndex(String(data: data, encoding: .utf8)), false,
                http.value(forHTTPHeaderField: "ETag"))
    }

    /// Symbolliste der Binance-Website: TradFi-Logos und -Namen (Aktien, Gold, Silber), ohne
    /// `tradFiOnly` auch Lücken der Krypto-Logos. false = nicht erreichbar.
    private static func fetchBinance(into fresh: inout [String: String], order: inout [String],
                                     names: inout [String: String], nameOrder: inout [String],
                                     crypto: Set<String>, tradFiOnly: Bool) async -> Bool {
        guard let text = try? await MarketHTTP.call(CoinLogos.binanceListURL),
              let object = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any],
              let array = object["data"] as? [[String: Any]]
        else { return false }
        let entries = array.map { entry -> CoinLogos.BinanceEntry in
            let name = entry["name"] as? String ?? ""
            return CoinLogos.BinanceEntry(name: name.isEmpty ? (entry["baseAsset"] as? String ?? "") : name,
                                          logo: entry["logo"] as? String,
                                          tags: entry["tags"] as? [String] ?? [],
                                          onlyFutures: entry["onlyFutures"] as? Bool ?? false,
                                          fullName: entry["fullName"] as? String)
        }
        if !tradFiOnly {
            CoinLogos.pick(entries.map { (symbol: $0.name, image: $0.logo) }, into: &fresh, order: &order)
        }
        CoinLogos.pickTradFi(entries, crypto: crypto, into: &fresh, order: &order)
        if !tradFiOnly {
            CoinLogos.pickNames(entries.map { (symbol: $0.name, name: $0.fullName) }, into: &names, order: &nameOrder)
        }
        CoinLogos.pickTradFiNames(entries, crypto: crypto, into: &names, order: &nameOrder)
        return true
    }

    /// Token-Liste von Binance Alpha (`CoinLogos.alphaListURL`): Lücken wie AIA, AGT, AIO.
    private static func fetchAlpha(into fresh: inout [String: String], order: inout [String],
                                   names: inout [String: String], nameOrder: inout [String]) async -> Bool {
        guard let text = try? await MarketHTTP.call(CoinLogos.alphaListURL),
              let object = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any],
              let array = object["data"] as? [[String: Any]]
        else { return false }
        let entries = CoinLogos.rankAlpha(array.map { entry in
            CoinLogos.AlphaEntry(symbol: entry["symbol"] as? String ?? "",
                                 name: entry["name"] as? String,
                                 icon: entry["iconUrl"] as? String,
                                 marketCap: (entry["marketCap"] as? String).flatMap { Double($0) }
                                     ?? (entry["marketCap"] as? NSNumber)?.doubleValue,
                                 offline: entry["offline"] as? Bool ?? false)
        })
        CoinLogos.pick(entries.map { (symbol: $0.symbol, image: $0.icon) }, into: &fresh, order: &order)
        CoinLogos.pickNames(entries.map { (symbol: $0.symbol, name: $0.name) }, into: &names, order: &nameOrder)
        return true
    }

    /// Seiten der Rangliste mit Pause dazwischen (bei «429» einmal nach der verlangten Pause
    /// wiederholt); false = eine Seite fehlte (Abbruch).
    private static func fetchCoinGecko(pages: ClosedRange<Int>,
                                       into fresh: inout [String: String], order: inout [String],
                                       names: inout [String: String], nameOrder: inout [String]) async -> Bool {
        for page in pages {
            if page > 1 { try? await Task.sleep(nanoseconds: UInt64(CoinLogos.pagePauseMillis) * 1_000_000) }
            let url = CoinLogos.marketsURL(page: page)
            var text: String?
            do {
                text = try await MarketHTTP.call(url)
            } catch let error as HttpMarketError where error.httpCode == 429 {
                let wait = CoinLogos.rateLimitWaitMillis(retryAfterSeconds: error.retryAfterSeconds)
                try? await Task.sleep(nanoseconds: UInt64(wait) * 1_000_000)
                text = try? await MarketHTTP.call(url)
            } catch {
                text = nil
            }
            guard let text,
                  let array = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [[String: Any]]
            else { return false }
            CoinLogos.pick(array.map { (symbol: $0["symbol"] as? String ?? "", image: $0["image"] as? String) },
                           into: &fresh, order: &order)
            CoinLogos.pickNames(array.map { (symbol: $0["symbol"] as? String ?? "", name: $0["name"] as? String) },
                                into: &names, order: &nameOrder)
            if array.count < CoinLogos.perPage { return true }
        }
        return true
    }

    // MARK: Fehlschläge (Name → Zeitpunkt), einen Tag lang nicht wiederholen

    private static func loadFailures(now: Int64) -> [String: Int64] {
        let text = SharedStorage.defaults.string(forKey: failedKey) ?? ""
        var out: [String: Int64] = [:]
        for part in text.split(separator: ";") {
            let pieces = part.split(separator: ":", maxSplits: 1)
            guard pieces.count == 2, let at = Int64(pieces[1]), !pieces[0].isEmpty,
                  CoinLogos.isFresh(savedAt: at, now: now, ttl: CoinLogos.failureTTLMillis)
            else { continue }
            out[String(pieces[0])] = at
        }
        return out
    }

    private static func saveFailures(_ failures: [String: Int64]) {
        let text = failures.map { "\($0.key):\($0.value)" }.joined(separator: ";")
        SharedStorage.defaults.set(text, forKey: failedKey)
    }

    // MARK: Hilfen

    /// Verkleinert auf `CoinLogos.storedPixels` (Seitenverhältnis bleibt), Massstab 1.
    private static func scaled(_ data: Data) -> UIImage? {
        guard let image = UIImage(data: data), image.size.width > 0, image.size.height > 0 else { return nil }
        let pixels = CGFloat(CoinLogos.storedPixels)
        let width = image.size.width * image.scale
        let height = image.size.height * image.scale
        let longest = max(width, height)
        guard longest > pixels else { return image }
        let factor = pixels / longest
        let size = CGSize(width: max(1, (width * factor).rounded()), height: max(1, (height * factor).rounded()))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }
}

// MARK: Namen («Bitcoin», «NVIDIA»)

extension CoinLogoStore {
    fileprivate static let namesBox = NamesBox()
    fileprivate static let stockNamesBox = NamesBox()

    /// Alle US-Aktien der Nasdaq-Symbolliste: Kürzel → Name (leer, bis geladen).
    static var stockNames: [String: String] {
        stockNamesBox.read { CoinLogos.decodeNames(try? String(contentsOf: stockNamesURL, encoding: .utf8)) }
    }

    /// Namen der Coins und TradFi-Paare (Schlüssel `CoinLogos.nameKey`); leer, bis die Liste da ist.
    static var names: [String: String] {
        namesBox.read { CoinLogos.decodeNames(readFirst([namesURL, directory.appendingPathComponent(oldFiles[1])])) }
    }

    /// Inhalt der ersten vorhandenen, nicht leeren Datei.
    fileprivate static func readFirst(_ urls: [URL]) -> String? {
        for url in urls {
            if let text = try? String(contentsOf: url, encoding: .utf8),
               !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return text }
        }
        return nil
    }

    /// Name für ein Paar der Merkliste (TradFi beachtet); nil bei DEX-Pools und unbekannten Coins.
    static func name(for watch: Watch) -> String? {
        guard CoinLogos.allowed(forMarket: watch.marketKey) else { return nil }
        let tradFi = tradFiPairs.contains(watch.logoPairKey)
        if let name = names[CoinLogos.nameKey(watch.baseAsset, tradFi: tradFi)] { return name }
        // Aktie ohne Namen in der Binance-Liste: aus der Nasdaq-Symbolliste
        return tradFi ? stockNames[watch.baseAsset.uppercased()] : nil
    }
}

/// Namen im Speicher (einmal aus der Datei gelesen, nach dem Laden der Liste ersetzt).
final class NamesBox: @unchecked Sendable {
    private let lock = NSLock()
    private var value: [String: String]?

    func read(_ load: () -> [String: String]) -> [String: String] {
        lock.lock(); defer { lock.unlock() }
        if let value { return value }
        let loaded = load()
        value = loaded
        return loaded
    }

    func store(_ names: [String: String]) {
        lock.lock(); value = names; lock.unlock()
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

    /// Schlüssel für die Plakette eines Paars (TradFi oder Krypto).
    static func logoKey(for watch: Watch) -> String {
        CoinLogos.logoKey(watch.baseAsset, tradFi: tradFiPairs.contains(watch.logoPairKey))
    }
}

extension Watch {
    /// Kennung für die Liste der TradFi-Paare (`CoinLogos.pairKey`).
    var logoPairKey: String {
        CoinLogos.pairKey(marketKey: marketKey, base: baseAsset, quote: quoteAsset, contractType: String(contractType.rawValue))
    }
}

/// Zähler, der steigt, sobald neue Logos auf dem Gerät liegen; `CoinBadge` lädt dann fehlende neu.
@MainActor
final class CoinLogoRevision: ObservableObject {
    static let shared = CoinLogoRevision()
    @Published private(set) var value = 0

    static func bump() {
        shared.value += 1
    }
}
