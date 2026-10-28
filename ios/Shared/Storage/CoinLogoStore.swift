import Foundation
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
///
/// Hier Zustand, Listen und Rangliste; Anzeigen in `CoinLogoStore+Display.swift`, Bilder laden in
/// `CoinLogoStore+Download.swift`, Namen und Kennzeichen in `CoinLogoStore+Names.swift`.
actor CoinLogoStore {
    static let shared = CoinLogoStore()

    /// Zuletzt benutzte Bilder; `NSCache` ist threadsicher, daher ohne Actor-Sprung lesbar.
    // nonisolated(unsafe): NSCache ist laut Apple threadsicher, im SDK aber nicht als Sendable markiert.
    nonisolated(unsafe) static let memory: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.countLimit = 300
        return cache
    }()

    /// Zeitpunkt der zuletzt geholten Rangliste bzw. des letzten Versuchs.
    private static let mapTimeKey = "coin_logos_map_time"
    private static let mapAttemptKey = "coin_logos_map_attempt"
    /// Fehlgeschlagene Bilder (Name → Zeitpunkt der letzten `CoinLogos.failureTTLMillis`).
    static let failedKey = "coin_logos_failed"
    /// Kürzel, die nur die Zusatzseiten der Rangliste bringen (`CoinLogos.extraOnlyKeys`).
    static let extraOnlyKey = "coin_logos_extra_only"
    /// Zeitpunkt der zuletzt direkt geholten Binance-Symbolliste bzw. des letzten Versuchs.
    private static let binanceTimeKey = "coin_logos_binance_list_time"
    private static let binanceAttemptKey = "coin_logos_binance_list_attempt"
    /// Letzter unbrauchbarer Abruf der Ersatzdatei `coin_meta.json` (dann frühestens nach einer Stunde).
    private static let metaAttemptKey = "coin_logos_coin_meta_attempt"
    /// Rangliste nicht erreichbar: frühestens nach einer Stunde erneut.
    static let mapRetryMillis: Int64 = 60 * 60 * 1000
    private var map: [String: String]?
    private var syncing = false
    /// Letzter Abgleich scheiterte am fehlenden Netz (z. B. kein Internet direkt nach der Installation):
    /// `AppData` holt ihn nach, sobald wieder Netz da ist (`CoinLogoRetry.syncNeeded`). Nur im Speicher —
    /// nach einem Neustart läuft der Abgleich ohnehin beim Öffnen. Wie Android `retryPending`.
    private(set) var retryPending = false
    /// Frühestes Ende der Pausen gebremster Bild-Server nach dem letzten Abgleich mit Bildern (ms; nil = nichts
    /// gebremst): `AppData` holt danach von selbst nach. Nur im Speicher — wie Android `imagesPausedUntil`.
    private(set) var imagesPausedUntil: Int64?

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
    static let directory: URL = {
        var url = SharedStorage.directory.appendingPathComponent("coin_logos", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
        return url
    }()

    private static var mapURL: URL { directory.appendingPathComponent("map.txt") }
    static var namesURL: URL { directory.appendingPathComponent("names.txt") }
    /// Zuletzt geholte Binance- und Alpha-Liste (Format wie `coin_meta.json`, `CoinMeta.encode`).
    private static var listsURL: URL { directory.appendingPathComponent("lists.json") }

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
        let now = EpochTime.nowMillis
        let saved = savedLists()
        let binanceAt = Int64(defaults.double(forKey: Self.binanceTimeKey))
        let alphaAt = Int64(defaults.double(forKey: Self.alphaTimeKey))
        let hasBinance = !(saved?.binance.isEmpty ?? true)
        let binanceDue = includeBinance
            && CoinLogos.alphaListDue(savedAt: binanceAt, attemptAt: Int64(defaults.double(forKey: Self.binanceAttemptKey)),
                                      now: now, hasSymbols: hasBinance)
        // Ohne Kennungen für die Alpha-Paare: einmal direkt neu, sonst fehlen die Alpha-Paare
        let alphaDue = CoinLogos.alphaListDue(savedAt: alphaAt, attemptAt: Int64(defaults.double(forKey: Self.alphaAttemptKey)),
                                              now: now, hasSymbols: Self.hasAlphaSymbols && CoinLogos.hasAlphaIds(saved?.alpha))
        guard binanceDue || alphaDue else {
            listsNetworkFailed = false
            return false
        }
        let metaAttempt = Int64(defaults.double(forKey: Self.metaAttemptKey))
        let request = CoinLists.Request(binanceDue: binanceDue, alphaDue: alphaDue,
                                        lastBinanceAt: binanceAt, lastAlphaAt: alphaAt, now: now,
                                        mirrorAllowed: !CoinLogos.isFresh(savedAt: metaAttempt, now: now, ttl: Self.mapRetryMillis),
                                        savedBinance: saved?.binance ?? [])
        let result = await CoinLists.fetch({ url in try await ExchangeHTTP.call(url, session: ExchangeHTTP.bulkSession) },
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
        // Binance-Namen für «gleicher Token»: Spot vor Futures
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

        let tradFiBases = Self.tradFiBases
        var earlyTask: Task<DownloadResult, Never>?
        let current = await symbolMap { partial in
            guard images else { return }
            let wanted = CoinLogos.withStockLogos(partial, tradFiBases: tradFiBases)
            earlyTask = Task.detached(priority: .utility) { await CoinLogoStore.downloadMissing(wanted) }
        }
        guard images else {
            retryPending = listsNetworkFailed || mapNetworkFailed
            imagesPausedUntil = nil
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
        // Der zweite Durchgang versucht alle noch fehlenden Bilder erneut: seine Pause gilt
        imagesPausedUntil = rest.pausedUntil
        return result.added
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
        let exists = { (name: String) in fm.fileExists(atPath: Self.directory.appendingPathComponent(name).path) }
        let missing = symbols.filter { CoinLogos.fileName($0).map { !exists($0) } ?? false }
        // Adresse wie beim Zusammenführen («1000CAT» → «CAT»); je Datei ein Abruf
        let wanted = missing.compactMap { s in CoinLogos.urlFor(current, symbol: s).map { (s, $0) } }
        var complete = wanted.count == missing.count
        let jobs = CoinLogos.downloadJobs(wanted, skip: exists)
        guard !jobs.isEmpty else { return complete }
        var added = 0
        let streaks = ForbiddenStreaks()
        await withTaskGroup(of: Bool.self) { group in
            for job in jobs { group.addTask { await Self.download(name: job.name, url: job.url, streaks: streaks) == .ok } }
            for await ok in group {
                if ok { added += 1 } else { complete = false }
            }
        }
        if added > 0 { await CoinLogoRevision.bump() }
        return complete
    }

    // MARK: Alpha-Liste allein

    /// Nur die Alpha-Liste holen (`CoinLogos.alphaOnly`: Logos und Namen aus, «inkl. Alpha-Tokens» an) —
    /// ohne Rangliste und ohne Bilder, für alle gleich; höchstens einmal pro Tag (`refreshLists`).
    func refreshAlphaOnly() async {
        await refreshLists(includeBinance: false)
    }

    /// Zuordnung aus Datei bzw. frisch von CoinGecko, zusammengeführt mit den Binance-Listen aus
    /// `refreshLists`; bei Fehlern die alte (auch abgelaufen). Nur freigegebene Bild-Adressen
    /// (`CoinLogos.isAllowedURL`).
    /// `onTop`: Zuordnung nach den grössten 1000 (früher Start der Bilder), nur bei neuem Abruf.
    private func symbolMap(onTop: ([String: String]) -> Void = { _ in }) async -> [String: String] {
        let defaults = SharedStorage.defaults
        let now = EpochTime.nowMillis
        let known = map ?? CoinLogos.decode(Self.readNonBlank(Self.mapURL))
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
        if !merged.names.isEmpty {
            try? CoinLogos.encodeNames(merged.names, order: merged.nameOrder)
                .write(to: Self.namesURL, atomically: true, encoding: .utf8)
            Self.namesBox.store(merged.names)
            await CoinLogoRevision.bump()
        }
        defaults.set(Double(CoinLogos.savedAt(now: now, complete: full)), forKey: Self.mapTimeKey)
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
    /// Stunde lang keine Logos. Ein Server-Fehler sperrt eine Stunde.
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
                text = try await ExchangeHTTP.call(url)
            } catch let error as HttpExchangeError where error.httpCode == 429 {
                let wait = CoinLogos.rateLimitWaitMillis(retryAfterSeconds: error.retryAfterSeconds)
                try? await Task.sleep(nanoseconds: UInt64(wait) * 1_000_000)
                text = try? await ExchangeHTTP.call(url)
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
}
