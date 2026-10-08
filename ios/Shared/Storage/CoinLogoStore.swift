import Combine
import Foundation
import UIKit

/// Coin-Logos von CoinGecko (Regeln in `CoinLogos`), gleich wie Android `CoinLogoRepository`.
/// Nichts ist mitgeliefert, und nichts wird einzeln geholt — so erfährt CoinGecko nie, welche
/// Coins in einer Merkliste stehen:
///
/// 1. Rangliste der grössten 1000 Coins, Lücken (TradFi wie Gold, Silber, Aktien) aus der
///    Symbolliste der Binance-Website; höchstens einmal pro Woche, für alle Nutzer gleich.
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

    // «v2»: Zuordnung mit Binance-Lücken; ältere Testinstallationen holen sie einmal neu
    private static let mapTimeKey = "coin_logos_map_time_v2"
    private static let mapAttemptKey = "coin_logos_map_attempt_v2"
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

    private static var mapURL: URL { directory.appendingPathComponent("map2.txt") }

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
    /// gibt die Zahl neuer Logos zurück.
    @discardableResult
    func syncAll() async -> Int {
        guard !syncing else { return 0 }
        syncing = true
        defer { syncing = false }

        let wanted = await symbolMap()
        guard !wanted.isEmpty else { return 0 }
        let now = TimeUtils.nowMillis
        var failed = Self.loadFailures(now: now)
        let fm = FileManager.default
        let missing: [(name: String, url: String)] = wanted.compactMap { symbol, url in
            guard let name = CoinLogos.fileName(symbol), failed[name] == nil,
                  !fm.fileExists(atPath: Self.directory.appendingPathComponent(name).path)
            else { return nil }
            return (name, url)
        }
        guard !missing.isEmpty else { return 0 }

        var added = 0
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

    /// Erst die kleine Fassung, sonst das Bild aus der Rangliste.
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

    /// Zuordnung aus Datei bzw. frisch von CoinGecko; bei Fehlern die alte (auch abgelaufen).
    private func symbolMap() async -> [String: String] {
        let defaults = SharedStorage.defaults
        let now = TimeUtils.nowMillis
        let known = map ?? CoinLogos.decode(try? String(contentsOf: Self.mapURL, encoding: .utf8))
        map = known
        let savedAt = Int64(defaults.double(forKey: Self.mapTimeKey))
        if CoinLogos.isFresh(savedAt: savedAt, now: now), !known.isEmpty { return known }
        let attempt = Int64(defaults.double(forKey: Self.mapAttemptKey))
        if CoinLogos.isFresh(savedAt: attempt, now: now, ttl: Self.mapRetryMillis) { return known }
        defaults.set(Double(now), forKey: Self.mapAttemptKey)

        var fresh: [String: String] = [:]
        var order: [String] = []
        for page in 1...CoinLogos.pages {
            guard let text = try? await MarketHTTP.call(CoinLogos.marketsURL(page: page)),
                  let array = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [[String: Any]]
            else { break }
            let ranked = array.map { (symbol: $0["symbol"] as? String ?? "", image: $0["image"] as? String) }
            CoinLogos.pick(ranked, into: &fresh, order: &order)
            if array.count < CoinLogos.perPage { break }
        }
        // Lücken (TradFi wie Gold, Silber, Aktien) aus der Symbolliste der Binance-Website
        if let text = try? await MarketHTTP.call(CoinLogos.binanceListURL),
           let object = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any],
           let array = object["data"] as? [[String: Any]] {
            let entries = array.map { entry -> (symbol: String, image: String?) in
                let name = entry["name"] as? String ?? ""
                return (symbol: name.isEmpty ? (entry["baseAsset"] as? String ?? "") : name, image: entry["logo"] as? String)
            }
            CoinLogos.pick(entries, into: &fresh, order: &order)
        }
        guard !fresh.isEmpty else { return known }
        try? CoinLogos.encode(fresh, order: order).write(to: Self.mapURL, atomically: true, encoding: .utf8)
        defaults.set(Double(now), forKey: Self.mapTimeKey)
        map = fresh
        return fresh
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

/// Zähler, der steigt, sobald neue Logos auf dem Gerät liegen; `CoinBadge` lädt dann fehlende neu.
@MainActor
final class CoinLogoRevision: ObservableObject {
    static let shared = CoinLogoRevision()
    @Published private(set) var value = 0

    static func bump() {
        shared.value += 1
    }
}
