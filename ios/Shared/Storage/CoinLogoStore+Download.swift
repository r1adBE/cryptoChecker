import Foundation
import Network
import os
import UIKit

// Logos laden (Teil von `CoinLogoStore`): Bilder der Rangliste in fester Reihenfolge (für alle gleich), parallel mit
// Grenzen (`CoinLogoQueue`), Pausen gebremster Server, Fehlschläge einen Tag sperren, Verkleinern auf
// `CoinLogos.storedPixels` (`CoinLogoDecode`); dazu die Erkennung von teurem Netz.

extension CoinLogoStore {
    /// Höchstgrösse eines Bildes (wie Android `MAX_IMAGE_BYTES`); grössere werden gar nicht erst ganz gelesen. 4 MB:
    /// Binance lädt Logos seit 2025 in hoher Auflösung hoch (über 512 KB, dann standen Initialen). Gespeichert wird
    /// nur das verkleinerte Bild; die Pixel-Grenze (`CoinLogos.maxSourcePixels`) bleibt.
    static let maxImageBytes = 4 * 1024 * 1024
    /// Bremst ein Server höchstens so kurz, werden seine Bilder nach der Pause gleich nachgeholt.
    private static let shortPauseMaxMillis: Int64 = 2 * 60_000
    private static let pauseRounds = 3
    /// Kurze Störung: so lange warten, bevor die gestörten Bilder in der nächsten Runde erneut drankommen.
    private static let transientRetryMillis: Int64 = 5_000
    /// Laufende Pausen je Bild-Server (`CoinLogoRetry.pausesImages`).
    fileprivate static let pauses = ImagePauseBox()

    private static let session: URLSession = {
        let c = PrivateSessionConfig.make(timeout: 15, resource: 25)
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.httpAdditionalHeaders = [HttpUserAgent.header: HttpUserAgent.value]
        c.waitsForConnectivity = false
        // Mehrere Bilder gleichzeitig je Server (`CoinLogoQueue.perHostParallel`)
        c.httpMaximumConnectionsPerHost = CoinLogoQueue.perHostParallel
        return URLSession(configuration: c)
    }()

    /// Ergebnis eines Bild-Durchgangs; `network` = am fehlenden Netz gescheitert; `pausedUntil` = frühestes Ende
    /// der Pausen gebremster Server, deren Bilder noch fehlen (nil = keine) — danach holt `AppData` nach.
    struct DownloadResult: Sendable {
        var added = 0
        var network = false
        var pausedUntil: Int64?
        /// Bilder offen geblieben (gestört bzw. Speicher voll): das erste Laden ist damit nicht erledigt (wie Android).
        var incomplete = false
    }

    fileprivate struct DownloadJob: Sendable {
        let name: String
        let url: String
        /// Ersatz-Adressen desselben Kürzels (`CoinMeta.alternates`), wenn `url` scheitert.
        var alternates: [String] = []
    }

    /// Schlüssel der gespeicherten Zuordnung in Datei-Reihenfolge (Binance-Liste, dann Rang;
    /// `CoinLogoQueue.mapFileOrder`).
    static func savedMapOrder() -> [String] { CoinLogoQueue.mapFileOrder(readNonBlank(mapURL)) }

    /// Lädt alle Logos aus `all`, die noch fehlen (nicht fehlgeschlagen, keine Datei), in der Reihenfolge `order`
    /// (`CoinLogoQueue.ordered`) — für alle gleich, nie nach der Merkliste —, mehrere gleichzeitig (`CoinLogoQueue.run`:
    /// je Server `perHostParallel`, insgesamt `maxParallel`). Bremst ein Server nur kurz (bis `shortPauseMaxMillis`),
    /// werden seine Bilder nach der Pause gleich nachgeholt; scheitert ein Bild, kommen die Ersatz-Adressen desselben
    /// Kürzels dran (`alternates`, `CoinMeta.alternates`) — wie Android `downloadMissing`. `lastStage`: letzte Stufe des
    /// Durchgangs (Gesamtzahl für den Hinweis, `CoinLogoLoading`).
    static func downloadMissing(_ all: [String: String], order: [String],
                                alternates: [String: [String]] = [:], lastStage: Bool = false) async -> DownloadResult {
        // Mobile Daten: Logos, die nur die Zusatzseiten der Rangliste bringen, erst im WLAN
        let extraOnly = Set(SharedStorage.defaults.stringArray(forKey: extraOnlyKey) ?? [])
        let unmetered = await UnmeteredNetwork.shared.isUnmetered()
        let wanted = CoinLogos.downloadable(all, extraOnly: extraOnly, unmetered: unmetered)
        guard !wanted.isEmpty else { return DownloadResult() }
        let now = EpochTime.nowMillis
        let failedBefore = loadFailures(now: now)
        let fm = FileManager.default
        let altByFile = Dictionary(alternates.compactMap { e in CoinLogos.fileName(e.key).map { ($0, e.value) } },
                                   uniquingKeysWith: { first, _ in first })
        // Je Datei höchstens ein Abruf (zwei Kürzel, eine Datei: nie gleichzeitig dieselbe Datei schreiben)
        var pending = CoinLogos.downloadJobs(CoinLogoQueue.ordered(wanted, order: order)) { name in
            failedBefore[name] != nil || fm.fileExists(atPath: directory.appendingPathComponent(name).path)
        }.map { DownloadJob(name: $0.name, url: $0.url, alternates: altByFile[$0.name] ?? []) }
        guard !pending.isEmpty else { return DownloadResult() }
        await CoinLogoLoading.imagesPending(pending.count, last: lastStage)
        let names = pending.map(\.name)
        var added = 0
        var unsaved = 0
        var newFailures: [String] = []
        // Länger gebremste Bilder (Pause über `shortPauseMaxMillis`): erst nach der Pause (`pausedUntil`)
        var paused: [DownloadJob] = []
        // Nach der letzten Runde noch gestört (`.transient`): kein Fehlschlag, der nächste Abgleich holt nach
        var transientLeft: [DownloadJob] = []
        // 403 in Folge je Server in diesem Durchgang (erst die dritte pausiert den Server)
        let streaks = ForbiddenStreaks()
        for round in 1...pauseRounds {
            var roundPaused: [DownloadJob] = []
            var roundTransient: [DownloadJob] = []
            await CoinLogoQueue.run(pending, host: { CoinLogoRetry.hostOf($0.url) ?? "" },
                                    work: { await CoinLogoStore.downloadWithAlternates($0, streaks: streaks) }) { job, result in
                switch result {
                case .ok:
                    added += 1
                    await CoinLogoLoading.imageDone()
                    await CoinLogoRevision.bumpIfDue()
                case .failed:
                    newFailures.append(job.name)
                    await CoinLogoLoading.imageDone()
                case .unsaved:
                    // Speicher voll: weder Fehlschlag (Tagessperre) noch Netz — der nächste Abgleich versucht es wieder
                    unsaved += 1
                    await CoinLogoLoading.imageDone()
                case .paused:
                    // Server bremst: nicht als Fehler (und kein stehender Prozentwert im Hinweis)
                    roundPaused.append(job)
                    await CoinLogoLoading.imagesPaused()
                case .transient:
                    // Kurze Störung: in der nächsten Runde noch einmal
                    roundTransient.append(job)
                }
            }
            transientLeft = roundTransient
            // Pause schon vorbei (nil): gleich die nächste Runde
            let pauseWait = roundPaused.isEmpty ? 0 : (shortestPauseMillis(roundPaused) ?? 0)
            let waitForPaused = !roundPaused.isEmpty && pauseWait <= shortPauseMaxMillis && round < pauseRounds
            if !waitForPaused { paused += roundPaused }
            let next = (waitForPaused ? roundPaused : []) + roundTransient
            if next.isEmpty || round == pauseRounds { break }
            let wait = max(waitForPaused ? pauseWait : 0, roundTransient.isEmpty ? 0 : transientRetryMillis)
            try? await Task.sleep(nanoseconds: UInt64(wait) * 1_000_000)
            pending = next
        }
        // Fortschritt schliessen: diese Bilder kommen erst mit dem nächsten Abgleich
        for _ in transientLeft { await CoinLogoLoading.imageDone() }
        // Am fehlenden Netz gescheitert: nicht einen Tag sperren, sondern mit dem Netz nachholen
        // (gebremste Bilder zählen nicht als Versuch — sonst galt «alle gebremst» als Netzfehler)
        let network = CoinLogoRetry.downloadsFailedForNetwork(online: NetworkStatus.shared.isOnline,
                                                              failed: newFailures.count + transientLeft.count,
                                                              attempted: names.count - paused.count - unsaved,
                                                              transient: transientLeft.count)
        // Drei Durchgänge in Folge offen (gestört, gebremst) zählt wie gescheitert, sonst bliebe das erste Laden offen
        let capped: Set<String> = network ? [] : noteOpenPasses(attempted: names, open: (transientLeft + paused).map(\.name))
        newFailures += capped.sorted()
        if !newFailures.isEmpty, !network {
            var failed = loadFailures(now: EpochTime.nowMillis)
            for name in newFailures { failed[name] = now }
            saveFailures(failed)
        }
        if added > 0 { await CoinLogoRevision.bump() }
        // Gebremste Bilder übrig, Pause aber schon vorbei: gleich nachholen (Ende = jetzt)
        let stillPaused = paused.filter { !capped.contains($0.name) }
        let pausedUntil = stillPaused.isEmpty ? nil : EpochTime.nowMillis + (shortestPauseMillis(stillPaused) ?? 0)
        return DownloadResult(added: added, network: network, pausedUntil: pausedUntil,
                              incomplete: transientLeft.contains { !capped.contains($0.name) } || unsaved > 0)
    }

    /// Erst `job.url`, scheitert es, die Ersatz-Adressen der Reihe nach; bremst dabei ein Server, gilt das Bild als
    /// gebremst — wie Android `fetchWithAlternates`.
    private static func downloadWithAlternates(_ job: DownloadJob, streaks: ForbiddenStreaks) async -> Fetched {
        var result = await download(name: job.name, url: job.url, streaks: streaks)
        guard result == .failed else { return result }
        var paused = false
        var transient = false
        for alt in job.alternates where alt != job.url {
            result = await download(name: job.name, url: alt, streaks: streaks)
            if result == .ok || result == .unsaved { return result }
            if result == .paused { paused = true }
            if result == .transient { transient = true }
        }
        return paused ? .paused : (transient ? .transient : .failed)
    }

    /// Kürzeste verbleibende Pause der Server von `jobs` (ms); nil = keiner (mehr) pausiert — wie Android.
    private static func shortestPauseMillis(_ jobs: [DownloadJob]) -> Int64? {
        let hosts = Set(jobs.compactMap { CoinLogoRetry.hostOf($0.url) })
        let waits: [Int64] = hosts.compactMap { pauses.remainingMillis($0) }
        return waits.min()
    }

    /// Ergebnis eines Bild-Abrufs; `failed` = gibt es nicht bzw. unlesbar (Tagessperre); `paused` = Bild-Server bremst
    /// (`CoinLogoRetry.pausesImages`); `transient` = kurze Störung — beide nicht fehlgeschlagen; `unsaved` = geladen, aber
    /// nicht gespeichert (Speicher voll): keine Sperre, kein Netzfehler, der Durchgang lädt nichts mehr. Wie Android `Fetched`.
    enum Fetched: Sendable { case ok, failed, paused, transient, unsaved }

    /// Erst die kleine Fassung, sonst das Bild aus der Rangliste. Ein 403 ohne «Retry-After» ist nur dieses Bild;
    /// erst das dritte Bild des Servers in Folge mit 403 (`streaks`) pausiert den Server — wie Android `fetchTo`.
    static func download(name: String, url: String, streaks: ForbiddenStreaks) async -> Fetched {
        // Speicher voll: nichts mehr herunterladen, das ohnehin verworfen würde
        if await streaks.unsaved { return .unsaved }
        let host = CoinLogoRetry.hostOf(url)
        if let host, pauses.isPaused(host) { return .paused }
        var candidates = [CoinLogos.smallURL(url)]
        if candidates[0] != url { candidates.append(url) }
        // Letzte Antwort war 403 (für die Folge je Server)
        var forbidden = false
        // Eine Fassung scheiterte an einer kurzen Störung (`CoinLogoRetry.isTransient`): später erneut, keine Tagessperre
        var transient = false
        for candidate in candidates {
            forbidden = false
            guard let u = URL(string: candidate) else { continue }
            let loaded: (response: HTTPURLResponse, body: Data?)
            do {
                loaded = try await loadImage(u, session: session)
            } catch {
                // Keine Antwort (Zeitüberschreitung, Verbindung abgebrochen); zu gross bleibt ein Fehlschlag
                if error is URLError { transient = true }
                continue
            }
            let http = loaded.response
            let retry = ExchangeBackoff.parseRetryAfterSeconds(http.value(forHTTPHeaderField: "Retry-After"),
                                                               now: EpochTime.nowMillis)
            if CoinLogoRetry.pausesImages(httpCode: http.statusCode, retryAfter: retry != nil) {
                pauses.pause(CoinLogoRetry.hostOf(candidate) ?? "", retryAfterSeconds: retry)
                return .paused
            }
            forbidden = http.statusCode == 403
            if loaded.body == nil, CoinLogoRetry.isTransient(httpCode: http.statusCode) { transient = true }
            guard let body = loaded.body, let png = CoinLogoDecode.pngData(body) else { continue }
            // `.atomic`: erst eine eigene Hilfsdatei, dann in einem Schritt umbenennen — nie ein halbes Bild, auch
            // wenn zwei Abrufe gleichzeitig dieselbe Datei schreiben. Nicht gespeichert (Speicher voll): eigene Art —
            // keine Tagessperre, kein Netzfehler; der Durchgang lädt nichts mehr — wie Android `LogoFiles.write`
            if (try? png.write(to: directory.appendingPathComponent(name), options: [.atomic])) != nil {
                if let host {
                    _ = await streaks.note(host, forbidden: false)
                    _ = await streaks.noteTransient(host, transient: false)
                }
                return .ok
            }
            await streaks.markUnsaved()
            return .unsaved
        }
        if let host {
            // Dritte 403 bzw. vierte Störung des Servers in Folge: Server pausieren (`CoinLogoRetry.transientStreakPauses`)
            let forbiddenPause = await streaks.note(host, forbidden: forbidden)
            if await streaks.noteTransient(host, transient: transient) || forbiddenPause {
                pauses.pause(host, retryAfterSeconds: nil)
                return .paused
            }
        }
        return transient ? .transient : .failed
    }

    /// Ein Bild laden: erst der Status (Bremse auch bei grossem Körper erkennen), dann höchstens `maxImageBytes`
    /// lesen — mehr → `ResponseTooLargeError`, ohne das ganze Bild in den Speicher zu holen (wie Android `download`).
    /// `body` nil = kein Erfolg (HTTP ≠ 2xx), dann wird nichts gelesen.
    /// Zählt für «Akku & Aktivität» (`UsageTaskMetrics`, Bytes wie übertragen).
    static func loadImage(_ url: URL, session: URLSession) async throws -> (response: HTTPURLResponse, body: Data?) {
        let (bytes, response) = try await session.bytes(from: url, delegate: UsageTaskMetrics.shared)
        guard let http = response as? HTTPURLResponse else {
            bytes.task.cancel()
            throw URLError(.badServerResponse)
        }
        guard (200..<300).contains(http.statusCode) else {
            bytes.task.cancel()
            return (http, nil)
        }
        do {
            let data = try await ExchangeHTTP.collectLimited(bytes, limit: maxImageBytes, expected: http.expectedContentLength)
            return (http, data)
        } catch {
            bytes.task.cancel()
            throw error
        }
    }

    // MARK: Fehlschläge (Name → Zeitpunkt), einen Tag lang nicht wiederholen

    static func loadFailures(now: Int64) -> [String: Int64] {
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

    /// Offene Durchgänge je Bild («name=zahl;…», `CoinLogoRetry.encodeOpenPasses`).
    static let openPassesKey = "coin_logos_open_passes"

    /// Nach einem Durchgang, der nicht am Netz scheiterte: offene Bilder (`open`, gestört bzw. gebremst) zählen, die übrigen
    /// versuchten vergessen (`CoinLogoRetry.nextOpenPasses`). Ergebnis: die Bilder, die jetzt
    /// `CoinLogoRetry.openPassesLimit` Durchgänge in Folge offen sind — sie gelten als gescheitert. Wie Android `noteOpenPasses`.
    private static func noteOpenPasses(attempted: [String], open: [String]) -> Set<String> {
        let stored = CoinLogoRetry.decodeOpenPasses(SharedStorage.defaults.string(forKey: openPassesKey))
        let next = CoinLogoRetry.nextOpenPasses(stored, attempted: attempted, open: open)
        let failed = Set(next.filter { CoinLogoRetry.openPassesFail($0.value) }.keys)
        SharedStorage.defaults.set(CoinLogoRetry.encodeOpenPasses(next.filter { !failed.contains($0.key) }), forKey: openPassesKey)
        return failed
    }
}

/// Bilder je Server in Folge mit 403 in einem Durchgang (`CoinLogoRetry.nextForbiddenStreak`); ein Objekt je
/// Durchgang — wie Android `LogoImagePauses.ForbiddenStreaks`. `note` nach jedem fertigen Bild: true = Server pausieren.
actor ForbiddenStreaks {
    private var streaks: [String: Int] = [:]
    private var transientStreaks: [String: Int] = [:]
    /// Speicher voll: ein Bild liess sich nicht schreiben — der Durchgang lädt danach keines mehr (bis zum nächsten).
    private(set) var unsaved = false

    func note(_ host: String, forbidden: Bool) -> Bool {
        let streak = CoinLogoRetry.nextForbiddenStreak(streaks[host] ?? 0, forbidden: forbidden)
        streaks[host] = streak
        return CoinLogoRetry.forbiddenStreakPauses(streak)
    }

    /// Gestörte Bilder je Server in Folge (`CoinLogoRetry.nextTransientStreak`): true = Server jetzt pausieren.
    func noteTransient(_ host: String, transient: Bool) -> Bool {
        let streak = CoinLogoRetry.nextTransientStreak(transientStreaks[host] ?? 0, transient: transient)
        transientStreaks[host] = streak
        return CoinLogoRetry.transientStreakPauses(streak)
    }

    func markUnsaved() { unsaved = true }
}

/// Laufende Pausen je Bild-Server (Host → bis, ms) im gemeinsamen Speicher, threadsicher (Bilder laden
/// parallel) — wie Android `imagePauses`.
final class ImagePauseBox: Sendable {
    /// nil = noch nicht aus `SharedStorage.defaults` gelesen.
    private let pauses = OSAllocatedUnfairLock<[String: Int64]?>(initialState: nil)
    private let key = "coin_logos_image_pauses"

    /// Nur mit gehaltener Sperre (aus `pauses.withLock`) aufrufen.
    private func current(_ pauses: [String: Int64]?, now: Int64) -> [String: Int64] {
        pauses ?? CoinLogoRetry.decodePauses(SharedStorage.defaults.string(forKey: key), now: now)
    }

    /// Verbleibende Pause von `host` in ms; nil = nicht pausiert.
    func remainingMillis(_ host: String) -> Int64? {
        pauses.withLock { pauses -> Int64? in
            let now = EpochTime.nowMillis
            let map = current(pauses, now: now)
            pauses = map
            guard let until = map[host], until > now else { return nil }
            return until - now
        }
    }

    func isPaused(_ host: String) -> Bool {
        pauses.withLock { pauses -> Bool in
            let now = EpochTime.nowMillis
            var map = current(pauses, now: now)
            let paused = (map[host] ?? 0) > now
            if !paused { map[host] = nil }
            pauses = map
            return paused
        }
    }

    func pause(_ host: String, retryAfterSeconds: Int64?) {
        guard !host.isEmpty else { return }
        pauses.withLock { pauses in
            let now = EpochTime.nowMillis
            var map = current(pauses, now: now).filter { $0.value > now }
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
}

/// Nicht teures Netz (WLAN, Ethernet: `isExpensive` und `isConstrained` false)? Nur für die Zusatzseiten der
/// Rangliste und deren Logos (`CoinLogos.downloadable`) — wie Android `isActiveNetworkMetered`. Eigener
/// Beobachter, damit `NetworkStatus` (online/offline für die Aktualisierung) unverändert bleibt.
final class UnmeteredNetwork: Sendable {
    static let shared = UnmeteredNetwork()

    private let monitor = NWPathMonitor()
    private let known = OSAllocatedUnfairLock<Bool?>(initialState: nil)

    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let unmetered = path.status == .satisfied && !path.isExpensive && !path.isConstrained
            self?.known.withLock { $0 = unmetered }
        }
        monitor.start(queue: DispatchQueue(label: "cryptochecker.unmetered-network"))
    }

    private var current: Bool? {
        known.withLock { $0 }
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
