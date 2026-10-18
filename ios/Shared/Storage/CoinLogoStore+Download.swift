import Foundation
import Network
import UIKit

// Logos laden (Teil von `CoinLogoStore`): Bilder der Rangliste je Server parallel, Pausen gebremster Server,
// Fehlschläge einen Tag sperren, Verkleinern auf `CoinLogos.storedPixels`; dazu die Erkennung von teurem Netz.

extension CoinLogoStore {
    private static let maxImageBytes = 512 * 1024
    /// Bild-Abrufe gleichzeitig je Server (CoinGecko, Binance).
    private static let perHostParallel = 6
    /// Bremst ein Server höchstens so kurz, werden seine Bilder nach der Pause gleich nachgeholt.
    private static let shortPauseMaxMillis: Int64 = 2 * 60_000
    private static let pauseRounds = 3
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
    static func downloadMissing(_ all: [String: String]) async -> DownloadResult {
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

    /// Ergebnis eines Bild-Abrufs; `paused` = Bild-Server bremst (`CoinLogoRetry.pausesImages`), gilt
    /// nicht als fehlgeschlagen — wie Android `Fetched`.
    enum Fetched: Sendable { case ok, failed, paused }

    /// Erst die kleine Fassung, sonst das Bild aus der Rangliste.
    static func download(name: String, url: String) async -> Fetched {
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

/// Laufende Pausen je Bild-Server (Host → bis, ms) im gemeinsamen Speicher, threadsicher (Bilder laden
/// parallel) — wie Android `imagePauses`.
final class ImagePauseBox: @unchecked Sendable { // @unchecked: veränderlicher Zustand nur unter `lock` (NSLock)
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
final class UnmeteredNetwork: @unchecked Sendable { // @unchecked: veränderlicher Zustand nur unter `lock` (NSLock)
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
