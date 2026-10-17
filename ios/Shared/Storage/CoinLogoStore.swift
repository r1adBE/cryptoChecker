import Combine
import Foundation
import Network
import UIKit

/// Coin-Logos, Namen und Alpha-Kennzeichen (Regeln in `CoinLogos`, `CoinMeta`, `CoinLists`), gleich
/// wie Android `CoinLogoRepository`. Nichts wird einzeln geholt — so erfährt niemand, welche Coins in
/// einer Merkliste stehen:
///
/// 1. Zuerst und gleichzeitig Binance-Symbolliste und Alpha-Liste (`refreshLists`): Namen und
///    Alpha-Kennzeichen sind wenige Sekunden nach dem Start da — unabhängig von CoinGecko, den
///    Paarlisten und den Bildern. Fehlt eine direkt, kommt sie aus `coin_meta.json` (Release «data»,
///    nur Binance-Daten). Vor der ersten Liste gelten gespeicherte Namen bzw. der mitgelieferte Auszug.
/// 2. Rangliste von CoinGecko (Rang 1–2500, höchstens einmal pro Tag — CoinGecko: höchstens 24 h
///    zwischenspeichern), zusammengeführt mit den Listen aus 1 (`CoinMeta.merge`).
/// 3. `syncAll` lädt die Logos **aller** Coins dieser Liste von den Bildservern von CoinGecko und
///    Binance in den gemeinsamen Ordner (App Group, auch für die Widgets), mehrere gleichzeitig je
///    Server (`perHostParallel`); die Bilder der grössten 1000 beginnen schon, während die übrigen
///    Seiten der Rangliste laden. Später fehlen nur neu dazugekommene oder geänderte.
/// 4. Angezeigt wird nur aus Speicher und Datei (`stored`, `logo`); fehlt ein geladenes Logo, das
///    mitgelieferte Icon-Paket (`CoinLogos.iconPackName`, CC0), sonst Initialen.
///
/// Alle drei Schalter «Coin-Logos» aus → `syncAll` lädt keine Bilder.
actor CoinLogoStore {
    static let shared = CoinLogoStore()

    /// Zuletzt benutzte Bilder; `NSCache` ist threadsicher, daher ohne Actor-Sprung lesbar.
    private static let memory: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.countLimit = 300
        return cache
    }()

    // «v5»: Einträge der früheren GitHub-Liste fallen beim Lesen weg (`isAllowedURL`), dann neu direkt
    private static let mapTimeKey = "coin_logos_map_time_v5"
    private static let mapAttemptKey = "coin_logos_map_attempt_v5"
    private static let failedKey = "coin_logos_failed"
    /// Kürzel, die nur die Zusatzseiten der Rangliste bringen (`CoinLogos.extraOnlyKeys`).
    private static let extraOnlyKey = "coin_logos_extra_only_v1"
    /// Zeitpunkt der zuletzt direkt geholten Binance-Symbolliste bzw. des letzten Versuchs.
    private static let binanceTimeKey = "coin_logos_binance_list_time_v1"
    private static let binanceAttemptKey = "coin_logos_binance_list_attempt_v1"
    /// Letzter unbrauchbarer Abruf der Ersatzdatei `coin_meta.json` (dann frühestens nach einer Stunde).
    private static let metaAttemptKey = "coin_logos_coin_meta_attempt_v1"
    /// Rangliste nicht erreichbar: frühestens nach einer Stunde erneut.
    private static let mapRetryMillis: Int64 = 60 * 60 * 1000
    private static let maxImageBytes = 512 * 1024
    /// Bild-Abrufe gleichzeitig je Server (CoinGecko, Binance).
    private static let perHostParallel = 6
    /// Bremst ein Server höchstens so kurz, werden seine Bilder nach der Pause gleich nachgeholt.
    private static let shortPauseMaxMillis: Int64 = 2 * 60_000
    private static let pauseRounds = 3
    /// Mitgelieferte Icons im Speicher (eigene Vorsilbe: Vorrang haben die geladenen Logos).
    private static let bundledMemoryPrefix = "B_"
    /// Laufende Pausen je Bild-Server (`CoinLogoRetry.pausesImages`).
    fileprivate static let pauses = ImagePauseBox()

    private static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 15
        c.timeoutIntervalForResource = 25
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.urlCache = nil
        c.httpAdditionalHeaders = [HttpUserAgent.header: HttpUserAgent.value]
        c.waitsForConnectivity = false
        // Mehrere Bilder gleichzeitig je Server (`perHostParallel`)
        c.httpMaximumConnectionsPerHost = 6
        return URLSession(configuration: c)
    }()

    private var map: [String: String]?
    private var syncing = false
    /// Letzter Abgleich scheiterte am fehlenden Netz (z. B. kein Internet direkt nach der Installation):
    /// `AppData` holt ihn nach, sobald wieder Netz da ist (`CoinLogoRetry.syncNeeded`). Nur im Speicher —
    /// nach einem Neustart läuft der Abgleich ohnehin beim Öffnen. Wie Android `retryPending`.
    private(set) var retryPending = false

    /// Zuletzt bekannte Binance- und Alpha-Listen (direkt, Ersatzdatei oder gespeichert).
    private var lists: CoinMeta.Meta?
    private var listsRead = false
    private var listsRunning = false
    /// Stand der Listen; die Zuordnung übernimmt neue Listen auch ohne neue Rangliste (`withListsMerged`).
    private var listsVersion = 0
    private var mergedListsVersion = -1
    /// Letzte fällige Liste bzw. Rangliste scheiterte am Netz (für `retryPending`).
    private var listsNetworkFailed = false
    private var mapNetworkFailed = false

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
    /// Zuletzt geholte Binance- und Alpha-Liste (Format wie `coin_meta.json`, `CoinMeta.encode`).
    private static var listsURL: URL { directory.appendingPathComponent("lists1.json") }

    /// Ablage der früheren Ersatz-Logos über jsDelivr: einmal löschen.
    private static func removeOldFallback() {
        let fm = FileManager.default
        let old = SharedStorage.directory.appendingPathComponent("coin_logos_fallback", isDirectory: true)
        guard fm.fileExists(atPath: old.path) else { return }
        try? fm.removeItem(at: old)
        try? fm.removeItem(at: directory.appendingPathComponent("fallback_manifest_0_18_1.json"))
        SharedStorage.defaults.removeObject(forKey: "coin_logos_fallback_attempt_v1")
    }

    // MARK: Anzeigen (nie Netz; auch für Widgets)

    /// Sofort aus dem Speicher; nil = noch nicht geladen (mitgeliefertes Icon, falls nur das da ist).
    static func cached(_ symbol: String) -> UIImage? {
        guard let name = CoinLogos.fileName(symbol) else { return nil }
        if let image = memory.object(forKey: name as NSString) { return image }
        guard let icon = CoinLogos.bundledIconName(symbol) else { return nil }
        return memory.object(forKey: (bundledMemoryPrefix + icon) as NSString)
    }

    /// Aus Speicher oder Datei. Vorrang hat das geladene Logo der Listen (CoinGecko, Binance, Alpha),
    /// sonst das mitgelieferte Icon (`bundled`) — wie Android `logo`.
    static func stored(_ symbol: String) -> UIImage? {
        guard let name = CoinLogos.fileName(symbol) else { return nil }
        if let image = readImage(directory.appendingPathComponent(name), key: name) { return image }
        return bundled(symbol)
    }

    /// Bild aus Datei in den Speicher (`key`).
    private static func readImage(_ url: URL, key: String) -> UIImage? {
        if let image = memory.object(forKey: key as NSString) { return image }
        guard let data = try? Data(contentsOf: url), let image = UIImage(data: data) else { return nil }
        memory.setObject(image, forKey: key as NSString)
        return image
    }

    /// Mitgeliefertes Icon-Paket (App und Widgets): Bytes und Inhaltsverzeichnis, einmal gelesen.
    private static let iconPack: IconPack? = {
        guard let url = Bundle.main.url(forResource: CoinLogos.iconPackName, withExtension: CoinLogos.iconPackExtension),
              let data = try? Data(contentsOf: url, options: .mappedIfSafe)
        else { return nil }
        let bytes = [UInt8](data)
        guard let index = CoinLogos.parseIconPack(bytes) else { return nil }
        return IconPack(bytes: bytes, index: index)
    }()

    /// Mitgeliefertes Icon (CC0) für `symbol`; nil, wenn das Paket es nicht kennt.
    static func bundled(_ symbol: String) -> UIImage? {
        guard let name = CoinLogos.bundledIconName(symbol) else { return nil }
        let key = bundledMemoryPrefix + name
        if let image = memory.object(forKey: key as NSString) { return image }
        guard let pack = iconPack, let slice = pack.index[name] else { return nil }
        let data = Data(pack.bytes[slice.offset..<(slice.offset + slice.length)])
        guard let image = UIImage(data: data) else { return nil }
        memory.setObject(image, forKey: key as NSString)
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

    // MARK: 1. Binance-Symbolliste und Alpha-Liste — vor allem anderen

    private func savedLists() -> CoinMeta.Meta? {
        if !listsRead {
            lists = CoinMeta.parse(try? String(contentsOf: Self.listsURL, encoding: .utf8))
            listsRead = true
        }
        return lists
    }

    /// Binance-Symbolliste (ohne `includeBinance` nur die Alpha-Liste) und Alpha-Liste gleichzeitig holen
    /// (`CoinLists.fetch`), je höchstens einmal pro Tag, nach einem Server-Fehler frühestens nach einer
    /// Stunde; kein Netz zählt nicht als Versuch. Alpha-Kennzeichen und Namen gelten **sofort** — vor
    /// Paarlisten, Rangliste und Bildern. true = neue Listen. Wie Android `refreshLists`.
    @discardableResult
    func refreshLists(includeBinance: Bool = true) async -> Bool {
        guard !listsRunning else { return false }
        listsRunning = true
        defer { listsRunning = false }
        let defaults = SharedStorage.defaults
        let now = TimeUtils.nowMillis
        let saved = savedLists()
        let binanceAt = Int64(defaults.double(forKey: Self.binanceTimeKey))
        let alphaAt = Int64(defaults.double(forKey: Self.alphaTimeKey))
        let hasBinance = !(saved?.binance.isEmpty ?? true)
        let binanceDue = includeBinance
            && CoinLogos.alphaListDue(savedAt: binanceAt, attemptAt: Int64(defaults.double(forKey: Self.binanceAttemptKey)),
                                      now: now, hasSymbols: hasBinance)
        // Ohne Kennungen (Liste einer älteren Version): einmal direkt neu, sonst fehlen die Alpha-Paare
        let alphaDue = CoinLogos.alphaListDue(savedAt: alphaAt, attemptAt: Int64(defaults.double(forKey: Self.alphaAttemptKey)),
                                              now: now, hasSymbols: Self.hasAlphaSymbols && CoinLogos.hasAlphaIds(saved?.alpha))
        guard binanceDue || alphaDue else {
            listsNetworkFailed = false
            return false
        }
        let metaAttempt = Int64(defaults.double(forKey: Self.metaAttemptKey))
        let request = CoinLists.Request(binanceDue: binanceDue, alphaDue: alphaDue,
                                        lastBinanceAt: binanceAt, lastAlphaAt: alphaAt, now: now,
                                        mirrorAllowed: !CoinLogos.isFresh(savedAt: metaAttempt, now: now, ttl: Self.mapRetryMillis))
        let result = await CoinLists.fetch({ url in try await MarketHTTP.call(url, session: MarketHTTP.bulkSession) },
                                           request: request,
                                           isNetworkError: { $0 is URLError })
        let network = result.networkError || !NetworkStatus.shared.isOnline
        let recordFailure = CoinLogoRetry.recordsAttempt(networkFailure: network)
        // Versuche merken (kein Netz zählt nicht), Erfolg direkt mit Zeitpunkt
        if binanceDue {
            if result.binanceSource == .direct { defaults.set(Double(now), forKey: Self.binanceTimeKey) }
            if result.binanceSource == .direct || recordFailure { defaults.set(Double(now), forKey: Self.binanceAttemptKey) }
        }
        if alphaDue, result.alphaSource == .direct || recordFailure {
            defaults.set(Double(now), forKey: Self.alphaAttemptKey)
        }
        if result.mirrorFailed, !network { defaults.set(Double(now), forKey: Self.metaAttemptKey) }
        listsNetworkFailed = network && result.missing(request)
        guard result.binance != nil || result.alpha != nil else { return false }
        let freshAlpha = result.alpha.map { CoinLogos.keepAlphaIds($0, saved: saved?.alpha ?? []) }
        // Alpha-Kennzeichen sofort (direkt mit Zeitpunkt; aus der Ersatzdatei ohne, dann bald erneut direkt)
        if let fresh = freshAlpha {
            Self.setAlphaSymbols(CoinLogos.alphaSymbols(fresh))
            if result.alphaSource == .direct { defaults.set(Double(now), forKey: Self.alphaTimeKey) }
            // Alpha-Paare von Binance und Namen für «α» sofort
            Self.alphaTokensBox.store(CoinLogos.alphaTokens(fresh))
        }
        // Binance-Namen für «gleicher Token» (K-8): Spot vor Futures
        if let fresh = result.binance { TokenIdentityStore.shared.saveBinance(CoinMeta.binanceFullNames(fresh)) }
        let meta = CoinMeta.Meta(generatedAt: now,
                                 binance: result.binance ?? saved?.binance ?? [],
                                 alpha: freshAlpha ?? saved?.alpha ?? [])
        lists = meta
        listsVersion += 1
        try? CoinMeta.encode(meta).write(to: Self.listsURL, atomically: true, encoding: .utf8)
        // Namen sofort (gespeicherte mit CoinGecko-Namen behalten Vorrang)
        let fromLists = CoinMeta.listNames(binance: meta.binance, alpha: meta.alpha)
        let published = CoinLists.publishNames(known: Self.names, knownIsSnapshot: Self.namesBox.isSnapshot,
                                               listNames: fromLists.names, listOrder: fromLists.nameOrder)
        try? CoinLogos.encodeNames(published.names, order: published.order)
            .write(to: Self.namesURL, atomically: true, encoding: .utf8)
        Self.namesBox.store(published.names)
        await CoinLogoRevision.bump()
        return true
    }

    // MARK: 2./3. Rangliste und Bilder

    /// Lädt die Logos aller Coins der Rangliste, die noch fehlen (die grössten 1000 schon während die
    /// übrigen Seiten laden). Läuft nur einmal gleichzeitig; gibt die Zahl neuer Logos zurück. `images`
    /// false («Namen anzeigen» ohne Logos): nur die Liste mit den Namen, keine Bilder. Die Binance-Listen
    /// holt `AppData` vorher (`refreshLists`).
    @discardableResult
    func syncAll(images: Bool = true) async -> Int {
        guard !syncing else { return 0 }
        syncing = true
        defer { syncing = false }
        Self.removeOldFallback()

        let tradFiBases = Self.tradFiBases
        var earlyTask: Task<DownloadResult, Never>?
        let current = await symbolMap { partial in
            guard images else { return }
            let wanted = CoinLogos.withStockLogos(partial, tradFiBases: tradFiBases)
            earlyTask = Task.detached(priority: .utility) { await CoinLogoStore.downloadMissing(wanted) }
        }
        guard images else {
            retryPending = listsNetworkFailed || mapNetworkFailed
            return 0
        }
        // Aktien-Logos für alle TradFi-Kürzel ohne Eintrag dazu
        let wanted = CoinLogos.withStockLogos(current, tradFiBases: Self.tradFiBases)
        var result = DownloadResult()
        if let earlyTask { result = await earlyTask.value }
        let rest = await Self.downloadMissing(wanted)
        result.added += rest.added
        result.network = result.network || rest.network
        retryPending = listsNetworkFailed || mapNetworkFailed || result.network
        return result.added
    }

    /// Ergebnis eines Bild-Durchgangs; `network` = am fehlenden Netz gescheitert.
    struct DownloadResult: Sendable {
        var added = 0
        var network = false
    }

    /// Ergebnis der Bilder eines Servers.
    private struct HostResult: Sendable {
        var added = 0
        var failed: [String] = []
        var paused: [DownloadJob] = []
    }

    fileprivate struct DownloadJob: Sendable {
        let name: String
        let url: String
    }

    /// Lädt alle Logos aus `wanted`, die noch fehlen (nicht fehlgeschlagen, keine Datei): je Server
    /// `perHostParallel` gleichzeitig. Bremst ein Server nur kurz (bis `shortPauseMaxMillis`), werden
    /// seine Bilder nach der Pause gleich nachgeholt — wie Android `downloadMissing`.
    private static func downloadMissing(_ all: [String: String]) async -> DownloadResult {
        // Mobile Daten: Logos, die nur die Zusatzseiten der Rangliste bringen, erst im WLAN
        let extraOnly = Set(SharedStorage.defaults.stringArray(forKey: extraOnlyKey) ?? [])
        let unmetered = await UnmeteredNetwork.shared.isUnmetered()
        let wanted = CoinLogos.downloadable(all, extraOnly: extraOnly, unmetered: unmetered)
        guard !wanted.isEmpty else { return DownloadResult() }
        let now = TimeUtils.nowMillis
        let failedBefore = loadFailures(now: now)
        let fm = FileManager.default
        var seen = Set<String>()
        var pending: [DownloadJob] = []
        for (symbol, url) in wanted {
            guard let name = CoinLogos.fileName(symbol), failedBefore[name] == nil, !seen.contains(name),
                  !fm.fileExists(atPath: directory.appendingPathComponent(name).path)
            else { continue }
            seen.insert(name)
            pending.append(DownloadJob(name: name, url: url))
        }
        guard !pending.isEmpty else { return DownloadResult() }
        let attempted = pending.count
        var added = 0
        var newFailures: [String] = []
        var paused: [DownloadJob] = []
        for round in 1...pauseRounds {
            let byHost = Dictionary(grouping: pending) { CoinLogoRetry.hostOf($0.url) ?? "" }
            var roundPaused: [DownloadJob] = []
            await withTaskGroup(of: HostResult.self) { group in
                for list in byHost.values {
                    group.addTask { await CoinLogoStore.downloadHost(list) }
                }
                for await r in group {
                    added += r.added
                    newFailures.append(contentsOf: r.failed)
                    roundPaused.append(contentsOf: r.paused)
                }
            }
            paused = roundPaused
            if paused.isEmpty || round == pauseRounds { break }
            guard let wait = shortestPauseMillis(paused), wait <= shortPauseMaxMillis else { break }
            try? await Task.sleep(nanoseconds: UInt64(wait) * 1_000_000)
            pending = paused
        }
        // Am fehlenden Netz gescheitert: nicht einen Tag sperren, sondern mit dem Netz nachholen
        // (gebremste Bilder zählen nicht als Versuch — sonst galt «alle gebremst» als Netzfehler)
        let network = CoinLogoRetry.downloadsFailedForNetwork(online: NetworkStatus.shared.isOnline,
                                                              failed: newFailures.count, attempted: attempted - paused.count)
        if !newFailures.isEmpty, !network {
            var failed = loadFailures(now: TimeUtils.nowMillis)
            for name in newFailures { failed[name] = now }
            saveFailures(failed)
        }
        if added > 0 { await CoinLogoRevision.bump() }
        return DownloadResult(added: added, network: network)
    }

    /// Bilder eines Servers, `perHostParallel` gleichzeitig.
    private static func downloadHost(_ jobs: [DownloadJob]) async -> HostResult {
        var out = HostResult()
        var iterator = jobs.makeIterator()
        await withTaskGroup(of: (job: DownloadJob, result: Fetched).self) { group in
            for _ in 0..<perHostParallel {
                guard let job = iterator.next() else { break }
                group.addTask { (job, await CoinLogoStore.download(name: job.name, url: job.url)) }
            }
            while let done = await group.next() {
                switch done.result {
                case .ok:
                    out.added += 1
                    await CoinLogoRevision.bumpIfDue()
                case .failed:
                    out.failed.append(done.job.name)
                case .paused:
                    // Server bremst: nicht als Fehler
                    out.paused.append(done.job)
                }
                if let job = iterator.next() {
                    group.addTask { (job, await CoinLogoStore.download(name: job.name, url: job.url)) }
                }
            }
        }
        return out
    }

    /// Kürzeste verbleibende Pause der Server von `jobs` (ms); nil = keiner pausiert.
    private static func shortestPauseMillis(_ jobs: [DownloadJob]) -> Int64? {
        let hosts = Set(jobs.compactMap { CoinLogoRetry.hostOf($0.url) })
        let waits: [Int64] = hosts.compactMap { pauses.remainingMillis($0) }
        return waits.min()
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
            for job in jobs { group.addTask { await Self.download(name: job.name, url: job.url) == .ok } }
            for await ok in group {
                if ok { added += 1 } else { complete = false }
            }
        }
        if added > 0 { await CoinLogoRevision.bump() }
        return complete
    }

    /// Ergebnis eines Bild-Abrufs; `paused` = Bild-Server bremst (`CoinLogoRetry.pausesImages`), gilt
    /// nicht als fehlgeschlagen — wie Android `Fetched`.
    fileprivate enum Fetched: Sendable { case ok, failed, paused }

    /// Erst die kleine Fassung, sonst das Bild aus der Rangliste.
    private static func download(name: String, url: String) async -> Fetched {
        if let host = CoinLogoRetry.hostOf(url), pauses.isPaused(host) { return .paused }
        var candidates = [CoinLogos.smallURL(url)]
        if candidates[0] != url { candidates.append(url) }
        for candidate in candidates {
            guard let u = URL(string: candidate),
                  let loaded = try? await session.data(from: u),
                  let http = loaded.1 as? HTTPURLResponse
            else { continue }
            if CoinLogoRetry.pausesImages(httpCode: http.statusCode) {
                let retry = ExchangeBackoff.parseRetryAfterSeconds(http.value(forHTTPHeaderField: "Retry-After"),
                                                                   now: TimeUtils.nowMillis)
                pauses.pause(CoinLogoRetry.hostOf(candidate) ?? "", retryAfterSeconds: retry)
                return .paused
            }
            guard (200..<300).contains(http.statusCode),
                  loaded.0.count <= maxImageBytes,
                  let png = pngData(loaded.0)
            else { continue }
            if (try? png.write(to: directory.appendingPathComponent(name), options: [.atomic])) != nil {
                return .ok
            }
        }
        return .failed
    }

    /// Zu speichernde PNG-Daten: schon ein PNG in passender Grösse (CoinGecko «small», viele
    /// Binance-Logos) → unverändert, ohne Umrechnen; sonst verkleinert als PNG — wie Android `pngBytes`.
    private static func pngData(_ data: Data) -> Data? {
        guard let image = UIImage(data: data), image.size.width > 0, image.size.height > 0 else { return nil }
        let longest = max(image.size.width * image.scale, image.size.height * image.scale)
        if isPNG(data), longest <= CGFloat(CoinLogos.storedPixels) { return data }
        return scaled(image)?.pngData()
    }

    /// Anfang jeder PNG-Datei.
    private static let pngSignature: [UInt8] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]

    private static func isPNG(_ data: Data) -> Bool {
        data.count > pngSignature.count && Array(data.prefix(pngSignature.count)) == pngSignature
    }

    // MARK: Alpha-Liste allein

    /// Nur die Alpha-Liste holen (`CoinLogos.alphaOnly`: Logos und Namen aus, «inkl. Alpha-Tokens» an) —
    /// ohne Rangliste und ohne Bilder, für alle gleich; höchstens einmal pro Tag (`refreshLists`).
    func refreshAlphaOnly() async {
        await refreshLists(includeBinance: false)
    }

    // MARK: Aktiennamen (Nasdaq-Symbolliste)

    private static let stockNamesTimeKey = "coin_logos_stock_names_time_v1"
    /// Kennungen der früheren GitHub-Liste (nicht mehr benutzt, werden gelöscht).
    private static let oldStocksETagKey = "coin_logos_stocks_etag_v1"
    private static let oldIndexETagKey = "coin_logos_index_etag_v5"
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
        if CoinLogos.isFresh(savedAt: Int64(defaults.double(forKey: Self.stockNamesTimeKey)), now: now,
                             ttl: CoinLogos.stockNamesTTLMillis), !known.isEmpty { return false }
        if CoinLogos.isFresh(savedAt: Int64(defaults.double(forKey: Self.stockNamesAttemptKey)), now: now,
                             ttl: Self.mapRetryMillis) { return false }
        defaults.set(Double(now), forKey: Self.stockNamesAttemptKey)
        var fresh: [String: String] = [:]
        for url in [CoinLogos.nasdaqListedURL, CoinLogos.otherListedURL] {
            guard let text = try? await MarketHTTP.call(url) else { continue }
            CoinLogos.parseSymbolDirectory(text, into: &fresh)
        }
        guard !fresh.isEmpty else { return false }
        try? CoinLogos.encodeNames(fresh, order: fresh.keys.sorted())
            .write(to: Self.stockNamesURL, atomically: true, encoding: .utf8)
        defaults.set(Double(now), forKey: Self.stockNamesTimeKey)
        defaults.removeObject(forKey: Self.oldStocksETagKey)
        Self.stockNamesBox.store(fresh)
        await CoinLogoRevision.bump()
        return true
    }

    /// Zuordnung aus Datei bzw. frisch von CoinGecko, zusammengeführt mit den Binance-Listen aus
    /// `refreshLists`; bei Fehlern die alte (auch abgelaufen). Einträge der früheren eigenen Logo-Liste
    /// (GitHub Pages) fallen beim Lesen weg (`CoinLogos.isAllowedURL`) — dann wird die Liste neu geholt.
    /// `onTop`: Zuordnung nach den grössten 1000 (früher Start der Bilder), nur bei neuem Abruf.
    private func symbolMap(onTop: ([String: String]) -> Void = { _ in }) async -> [String: String] {
        let defaults = SharedStorage.defaults
        let now = TimeUtils.nowMillis
        // Nach dem Update: bis zum ersten Abruf die Liste der Vorgängerfassung
        let known = map ?? CoinLogos.decode(Self.readFirst([Self.mapURL, Self.directory.appendingPathComponent(Self.oldFiles[0])]))
        map = known
        let saved = savedLists()
        mapNetworkFailed = false
        let savedAt = Int64(defaults.double(forKey: Self.mapTimeKey))
        if CoinLogos.isFresh(savedAt: savedAt, now: now), !known.isEmpty { return withListsMerged(known) }
        let attempt = Int64(defaults.double(forKey: Self.mapAttemptKey))
        if CoinLogos.isFresh(savedAt: attempt, now: now, ttl: Self.mapRetryMillis) { return withListsMerged(known) }
        // Schon vor dem Abruf setzen (ein zweiter Aufruf währenddessen fragt nicht erneut); scheitert
        // er am Netz, nimmt `mapFetchFailed` das wieder zurück
        defaults.set(Double(now), forKey: Self.mapAttemptKey)

        let binance: [CoinLogos.BinanceEntry]? = (saved?.binance.isEmpty ?? true) ? nil : saved?.binance
        let alpha: [CoinLogos.AlphaEntry]? = (saved?.alpha.isEmpty ?? true) ? nil : saved?.alpha
        // Eine Quelle scheiterte am Netz (keine Verbindung), nicht am Server
        var networkError = false
        var top: [CoinMeta.GeckoRow] = []
        var complete = await Self.fetchCoinGecko(pages: 1...CoinLogos.pages, into: &top, networkError: &networkError)
        guard !top.isEmpty else {
            mapFetchFailed(networkError: networkError, previousAttempt: attempt)
            // Binance-Listen trotzdem für die Lücken
            return withListsMerged(known)
        }
        // Geänderte Bild-Adressen der grössten 1000 gleich löschen, dann ihre Bilder schon laden
        let partial = CoinMeta.merge(geckoTop: top, binance: binance, alpha: alpha)
        let handled = Self.dropChangedImages(old: known, new: partial.map, skip: [])
        onTop(partial.map)
        // Kleinere Coins (Rang 1001–2500) nur noch für die Lücken — und nur ohne teures Netz (mobile
        // Daten): sonst gilt die Liste als unvollständig und wird nach einigen Stunden erneut versucht
        var extra: [CoinMeta.GeckoRow] = []
        var extraFetched = false
        if complete {
            if await UnmeteredNetwork.shared.isUnmetered() {
                let range = (CoinLogos.pages + 1)...(CoinLogos.pages + CoinLogos.extraPages)
                complete = await Self.fetchCoinGecko(pages: range, into: &extra, networkError: &networkError)
                extraFetched = complete
            } else {
                complete = false
            }
        }
        var merged = CoinMeta.merge(geckoTop: top, binance: binance, alpha: alpha, geckoExtra: extra)
        // Kürzel nur aus den Zusatzseiten merken: ihre Logos lädt der Sammel-Abruf nur ohne teures Netz
        if extraFetched {
            defaults.set(Array(CoinLogos.extraOnlyKeys(all: merged.map, top: partial.map)), forKey: Self.extraOnlyKey)
        }
        // Unvollständig (eine Quelle fehlte): Bekanntes behalten und in einigen Stunden erneut
        let full = complete && binance != nil && alpha != nil
        if !full {
            CoinLogos.withKnown(&merged.map, order: &merged.order, known: known)
            CoinLogos.withKnown(&merged.names, order: &merged.nameOrder, known: Self.names)
        }
        try? CoinLogos.encode(merged.map, order: merged.order).write(to: Self.mapURL, atomically: true, encoding: .utf8)
        for old in Self.oldFiles { try? FileManager.default.removeItem(at: Self.directory.appendingPathComponent(old)) }
        if !merged.names.isEmpty {
            try? CoinLogos.encodeNames(merged.names, order: merged.nameOrder)
                .write(to: Self.namesURL, atomically: true, encoding: .utf8)
            Self.namesBox.store(merged.names)
            await CoinLogoRevision.bump()
        }
        defaults.set(Double(CoinLogos.savedAt(now: now, complete: full)), forKey: Self.mapTimeKey)
        defaults.removeObject(forKey: Self.oldIndexETagKey)
        // Geänderte Bild-Adressen (anderes Logo, andere Quelle): gespeichertes Bild neu laden
        _ = Self.dropChangedImages(old: known, new: merged.map, skip: handled)
        map = merged.map
        mergedListsVersion = listsVersion
        return merged.map
    }

    /// Gespeicherte Bilder der Kürzel löschen, deren Bild-Adresse sich zwischen `old` und `new`
    /// geändert hat (ausser `skip`); Ergebnis: diese Kürzel — wie Android `dropChangedImages`.
    private static func dropChangedImages(old: [String: String], new: [String: String], skip: Set<String>) -> Set<String> {
        let changed = CoinLogos.changedKeys(old: old, new: new).subtracting(skip)
        guard !changed.isEmpty else { return changed }
        for key in changed {
            guard let name = CoinLogos.fileName(key) else { continue }
            memory.removeObject(forKey: name as NSString)
            try? FileManager.default.removeItem(at: directory.appendingPathComponent(name))
        }
        SharedStorage.defaults.removeObject(forKey: failedKey)
        return changed
    }

    /// Zuordnung ohne neue Rangliste: neue Binance-/Alpha-Einträge (`refreshLists`) füllen nur Lücken
    /// (Vorhandenes bleibt) — wie Android `withListsMerged`.
    private func withListsMerged(_ known: [String: String]) -> [String: String] {
        guard let l = lists, mergedListsVersion != listsVersion else { return known }
        mergedListsVersion = listsVersion
        let fromLists = CoinMeta.merge(geckoTop: [], binance: l.binance, alpha: l.alpha, crypto: CoinMeta.listCrypto(l.binance))
        var out = known
        var order = known.keys.sorted()
        CoinLogos.withKnown(&out, order: &order, known: fromLists.map)
        guard out.count != known.count else { return known }
        map = out
        try? CoinLogos.encode(out, order: order).write(to: Self.mapURL, atomically: true, encoding: .utf8)
        return out
    }

    /// Rangliste nicht geholt: Kein Netz (z. B. direkt nach der Installation) zählt nicht als Versuch —
    /// der vorige Zeitpunkt gilt wieder, nachgeholt wird, sobald wieder Netz da ist; sonst kämen eine
    /// Stunde lang keine Logos. Ein Server-Fehler sperrt wie bisher eine Stunde.
    private func mapFetchFailed(networkError: Bool, previousAttempt: Int64) {
        let network = networkError || !NetworkStatus.shared.isOnline
        if !CoinLogoRetry.recordsAttempt(networkFailure: network) {
            SharedStorage.defaults.set(Double(previousAttempt), forKey: Self.mapAttemptKey)
        }
        mapNetworkFailed = network
    }

    /// Fehler eines Abrufs am Netz (keine Verbindung, Zeitüberschreitung) statt am Server?
    private static func isNetworkError(_ error: Error) -> Bool {
        error is URLError
    }

    /// Seiten der Rangliste mit Pause dazwischen nach `rows` (bei «429» einmal nach der verlangten Pause
    /// wiederholt); false = eine Seite fehlte (Abbruch).
    private static func fetchCoinGecko(pages: ClosedRange<Int>, into rows: inout [CoinMeta.GeckoRow],
                                       networkError: inout Bool) async -> Bool {
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
                if isNetworkError(error) { networkError = true }
                text = nil
            }
            guard let parsed = CoinMeta.parseMarketsPage(text) else { return false }
            rows.append(contentsOf: parsed)
            if parsed.count < CoinLogos.perPage { return true }
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
    private static func scaled(_ image: UIImage) -> UIImage? {
        guard image.size.width > 0, image.size.height > 0 else { return nil }
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

/// Mitgeliefertes Icon-Paket im Speicher (`CoinLogos.parseIconPack`).
private struct IconPack {
    let bytes: [UInt8]
    let index: [String: CoinLogos.IconSlice]
}

// MARK: Namen («Bitcoin», «NVIDIA»)

extension CoinLogoStore {
    fileprivate static let namesBox = NamesBox()
    fileprivate static let stockNamesBox = NamesBox()

    /// Alle US-Aktien der Nasdaq-Symbolliste: Kürzel → Name (leer, bis geladen).
    static var stockNames: [String: String] {
        stockNamesBox.read { CoinLogos.decodeNames(try? String(contentsOf: stockNamesURL, encoding: .utf8)) }
    }

    /// Namen der Coins und TradFi-Paare (Schlüssel `CoinLogos.nameKey`); bis zur ersten Liste aus dem
    /// mitgelieferten Auszug (`CoinMeta.snapshotName`), damit schon eine frische Installation Namen zeigt.
    static var names: [String: String] {
        namesBox.read(
            { CoinLogos.decodeNames(readFirst([namesURL, directory.appendingPathComponent(oldFiles[1])])) },
            snapshot: { snapshotMeta.map { CoinMeta.listNames(binance: $0.binance, alpha: $0.alpha).names } ?? [:] }
        )
    }

    /// Mitgelieferter Auszug der Binance-Listen (App und Widgets); nil, wenn nicht lesbar.
    fileprivate static let snapshotMeta: CoinMeta.Meta? = {
        guard let url = Bundle.main.url(forResource: CoinMeta.snapshotName, withExtension: "json") else { return nil }
        return CoinMeta.parse(try? String(contentsOf: url, encoding: .utf8))
    }()

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

    /// Schlüssel für die Plakette eines Paars (TradFi oder Krypto).
    static func logoKey(for watch: Watch) -> String {
        CoinLogos.logoKey(watch.baseAsset, tradFi: tradFiPairs.contains(watch.logoPairKey))
    }

    private static let alphaSymbolsKey = "coin_logos_alpha_symbols_v1"
    /// Zeitpunkt der zuletzt geholten Alpha-Liste bzw. des letzten Versuchs allein (`refreshAlphaOnly`).
    private static let alphaTimeKey = "coin_logos_alpha_time_v1"
    private static let alphaAttemptKey = "coin_logos_alpha_attempt_v1"

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
    fileprivate static let alphaTokensBox = AlphaTokensBox(url: directory.appendingPathComponent("alpha_tokens1.json"))

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
final class AlphaTokensBox: @unchecked Sendable {
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
        CoinLogos.pairKey(marketKey: marketKey, base: baseAsset, quote: quoteAsset, contractType: String(contractType.rawValue))
    }

    /// Art des Paars (`AssetKind.of`) aus den gespeicherten Listen; für viele Paare lieber
    /// `assetKind(tradFiPairs:alphaSymbols:)` mit einmal gelesenen Listen.
    var assetKind: AssetKind {
        assetKind(tradFiPairs: CoinLogoStore.tradFiPairs, alphaSymbols: CoinLogoStore.alphaSymbols,
                  alphaNames: CoinLogoStore.alphaNames,
                  pairName: TokenIdentityStore.shared.pairName(marketKey: marketKey, base: baseAsset))
    }

    /// Art des Paars: TradFi aus der Liste der TradFi-Paare oder der Gruppe «TradFi» (die Liste pflegt
    /// nur der Logo-Abgleich), Alpha aus der Alpha-Liste bzw. der Kennung eines Binance-Alpha-Paars;
    /// `alphaNames` und `pairName` (Name des Coins an der Börse) gegen Fehltreffer — wie Android
    /// `WatchEntity.assetKind`.
    func assetKind(tradFiPairs: Set<String>, alphaSymbols: Set<String>,
                   alphaNames: [String: String] = [:], pairName: String? = nil) -> AssetKind {
        AssetKind.of(marketKey: marketKey, base: baseAsset,
                     tradFi: groupName == AutoGroup.tradFi || tradFiPairs.contains(logoPairKey),
                     alphaSymbols: alphaSymbols, pairId: pairId, pairName: pairName, alphaNames: alphaNames)
    }
}

/// Zähler, der steigt, sobald neue Logos auf dem Gerät liegen; `CoinBadge` lädt dann fehlende neu.
@MainActor
final class CoinLogoRevision: ObservableObject {
    static let shared = CoinLogoRevision()
    @Published private(set) var value = 0

    /// Zeitpunkt der letzten Auffrischung (ms) für `bumpIfDue`.
    private static var lastBumpAt: Int64 = 0

    static func bump() {
        lastBumpAt = TimeUtils.nowMillis
        shared.value += 1
    }

    /// Während des Ladens vieler Logos: höchstens einmal pro Sekunde auffrischen.
    static func bumpIfDue() {
        if TimeUtils.nowMillis - lastBumpAt >= 1_000 { bump() }
    }
}

/// Laufende Pausen je Bild-Server (Host → bis, ms) im gemeinsamen Speicher, threadsicher (Bilder laden
/// parallel) — wie Android `imagePauses`.
final class ImagePauseBox: @unchecked Sendable {
    private let lock = NSLock()
    private var pauses: [String: Int64]?
    private let key = "coin_logos_image_pauses_v1"

    /// Nur mit gehaltenem `lock` aufrufen.
    private func currentLocked(now: Int64) -> [String: Int64] {
        pauses ?? CoinLogoRetry.decodePauses(SharedStorage.defaults.string(forKey: key), now: now)
    }

    /// Verbleibende Pause von `host` in ms; nil = nicht pausiert.
    func remainingMillis(_ host: String) -> Int64? {
        lock.lock(); defer { lock.unlock() }
        let now = TimeUtils.nowMillis
        let map = currentLocked(now: now)
        pauses = map
        guard let until = map[host], until > now else { return nil }
        return until - now
    }

    func isPaused(_ host: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        let now = TimeUtils.nowMillis
        var map = currentLocked(now: now)
        let paused = (map[host] ?? 0) > now
        if !paused { map[host] = nil }
        pauses = map
        return paused
    }

    func pause(_ host: String, retryAfterSeconds: Int64?) {
        guard !host.isEmpty else { return }
        lock.lock(); defer { lock.unlock() }
        let now = TimeUtils.nowMillis
        var map = currentLocked(now: now).filter { $0.value > now }
        // Schon pausiert (parallele Abrufe): Pause nicht verlängern
        if (map[host] ?? 0) > now {
            pauses = map
            return
        }
        map[host] = now + CoinLogoRetry.imagePauseMillis(retryAfterSeconds: retryAfterSeconds)
        pauses = map
        SharedStorage.defaults.set(CoinLogoRetry.encodePauses(map), forKey: key)
    }
}

/// Nicht teures Netz (WLAN, Ethernet: `isExpensive` und `isConstrained` false)? Nur für die Zusatzseiten der
/// Rangliste und deren Logos (`CoinLogos.downloadable`) — wie Android `isActiveNetworkMetered`. Eigener
/// Beobachter, damit `NetworkStatus` (online/offline für die Aktualisierung) unverändert bleibt.
final class UnmeteredNetwork: @unchecked Sendable {
    static let shared = UnmeteredNetwork()

    private let monitor = NWPathMonitor()
    private let lock = NSLock()
    private var known: Bool?

    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let unmetered = path.status == .satisfied && !path.isExpensive && !path.isConstrained
            self?.lock.lock()
            self?.known = unmetered
            self?.lock.unlock()
        }
        monitor.start(queue: DispatchQueue(label: "cryptochecker.unmetered-network"))
    }

    private var current: Bool? {
        lock.lock()
        defer { lock.unlock() }
        return known
    }

    /// Wartet höchstens 1 s auf den ersten gemeldeten Pfad; unbekannt → false (lieber sparsam).
    func isUnmetered() async -> Bool {
        for _ in 0..<10 {
            if let value = current { return value }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        return current ?? false
    }
}
