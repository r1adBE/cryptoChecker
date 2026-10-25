import Foundation
import os

/// Antwort einer Börse mit HTTP-Fehlercode (nicht 2xx, oder als Bremse erkannt). Der Text ist technisch (Bericht,
/// gespeicherter Fehler) und erscheint nie roh in der Oberfläche (`ConnectionErrors.display`). Android:
/// `HttpMarketError`.
struct HttpMarketError: LocalizedError {
    let httpCode: Int
    let body: String
    /// Wert von «Retry-After» (bei 429/503) für die Pause je Börse (`ExchangeBackoff`).
    var retryAfterSeconds: Int64? = nil

    /// So viele Zeichen der Antwort kommen höchstens in den Text.
    private static let excerptLength = 160

    var errorDescription: String? {
        let status = "HTTP \(httpCode)" + ExchangeBackoff.retryAfterSuffix(retryAfterSeconds)
        let excerpt = body.prefix(Self.excerptLength).trimmingCharacters(in: .whitespacesAndNewlines)
        return excerpt.isEmpty ? status : status + ": " + excerpt
    }
}

/// Die Antwort überschreitet die erlaubte Grösse (`MarketHTTP.checkBodySize`). Der Text ist fest, weil Fehler als
/// Zeichenkette gespeichert und erst später ausgewertet werden. Android: `ResponseTooLargeError`.
struct ResponseTooLargeError: LocalizedError {
    static let message = "Response too large"

    var errorDescription: String? { Self.message }
}

/// Antwort gelesen, aber ohne gültigen letzten Kurs (fehlt, 0, NaN/∞). Fester Text wie Android
/// `UserFriendlyMarketError.NO_TICKER_DATA`: Der Bericht ordnet ihn als «keine Daten» ein, damit bestätigt die
/// Einzelabfrage «nicht mehr gehandelt» (`NotTraded.confirmedBySingle`), die Anzeige übersetzt ihn
/// (`ConnectionErrors.display`). Art: Lesefehler (`MarketFailure.parse`), wie Android.
struct NoTickerDataError: LocalizedError {
    static let message = "Parsed ticker has no data"

    var errorDescription: String? { Self.message }
}

/// Fehler, dessen Text so in der Oberfläche stehen darf (übersetzt oder Meldung der Börse). Android:
/// `UserFriendlyMarketError`.
struct UserFriendlyMarketError: LocalizedError {
    let message: String

    var errorDescription: String? { message }
}

/// Netzwerkzugriff für Börsen, Zyklus-Daten und Widgets.
enum MarketHTTP {
    /// Einzelabfragen: kurze Zeitgrenze, damit eine hängende Börse nicht alles aufhält.
    static let session: URLSession = makeSession(timeout: 20)
    /// Paarlisten und andere grosse Listen (mehrere MB).
    static let bulkSession: URLSession = makeSession(timeout: 60)
    /// Sammelabfragen der Kurse: 20 s wie Einzelabfragen (auch Gesamtdauer) — eine hängende Börse hielte sonst
    /// die Aktualisierung bis zu 70 s auf (Hintergrund und Widget haben nur rund 30 s).
    static let tickerBulkSession: URLSession = makeSession(timeout: 20, resource: 20)
    /// Adress-Hosts (`HttpUserAgent.isAddressHost`): eigene Sessions mit neutralem User-Agent und
    /// eigenen Verbindungen — Gebühren/Halving an mempool.space, publicnode … nennen die App so nie (wie Android).
    static let addressHostSession: URLSession = makeSession(timeout: 20, userAgent: HttpUserAgent.neutral)
    static let addressHostBulkSession: URLSession = makeSession(timeout: 60, userAgent: HttpUserAgent.neutral)

    /// Session für eine Anfrage an `host`: Adress-Hosts nie über die Sessions mit App-User-Agent.
    static func sessionFor(host: String?, requested: URLSession) -> URLSession {
        guard let host, HttpUserAgent.isAddressHost(host) else { return requested }
        if requested === addressHostSession || requested === addressHostBulkSession { return requested }
        // Nur Paarlisten (bulkSession, 60 s) über die lange Adress-Session; alle anderen behalten 20 s
        return requested === bulkSession ? addressHostBulkSession : addressHostSession
    }

    /// Gleichzeitige Anfragen insgesamt und je Host — wie Android (OkHttp-Dispatcher `maxRequests` 32,
    /// `maxRequestsPerHost` 5). Mehr wartet in `RequestGate`, bevor die URLSession-Aufgabe entsteht.
    static let maxRequests = 32
    static let maxRequestsPerHost = 5

    private static func makeSession(timeout: TimeInterval, resource: TimeInterval? = nil,
                                    userAgent: String = HttpUserAgent.value) -> URLSession {
        let c = PrivateSessionConfig.make(timeout: timeout, resource: resource ?? timeout + 10)
        // Mehr Verbindungen je Host als `RequestGate` je Host durchlässt: Keine Aufgabe wartet in der URLSession auf eine
        // freie Verbindung — dort liefen ihre Fristen schon und endeten als Schein-Zeitüberschreitung (Pause der Börse)
        c.httpMaximumConnectionsPerHost = maxRequestsPerHost + 1
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.httpAdditionalHeaders = [HttpUserAgent.header: userAgent, "Accept": "application/json"]
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }

    /// GET (oder POST, wenn `post` gesetzt) — liefert den Text der Antwort.
    static func call(_ url: String, post: PostRequestInfo? = nil, session: URLSession = MarketHTTP.session) async throws -> String {
        // Für alle gleiche Marktdaten zuerst vom Spiegel auf GitHub (`DataMirror`), sonst beim Anbieter
        if post == nil, let entry = DataMirror.entry(for: url),
           let text = try? await call(entry.url, session: session),
           let body = DataMirror.unwrap(text, now: TimeUtils.nowMillis, maxAgeMillis: entry.maxAgeMillis) {
            return body
        }
        guard let u = URL(string: url) else { throw UserFriendlyMarketError(message: L("something_went_wrong")) }
        // Erst nach dem Spiegel: dessen Host (GitHub) behält die gewünschte Session
        let effective = Self.sessionFor(host: u.host, requested: session)
        var request = URLRequest(url: u)
        if let post {
            request.httpMethod = "POST"
            request.httpBody = post.body.data(using: .utf8)
            if request.value(forHTTPHeaderField: "Content-Type") == nil {
                request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            }
            post.headers?.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
        }
        // Adress-Hosts: neutral auch gegen mitgegebene Kopfzeilen
        if HttpUserAgent.isAddressHost(u.host ?? "") {
            request.setValue(HttpUserAgent.neutral, forHTTPHeaderField: HttpUserAgent.header)
        }
        // Gleiche gleichzeitige Anfragen nur einmal ins Netz (`InFlightRequests`): GETs mit derselben Adresse, POSTs
        // mit derselben Adresse, denselben Kopfzeilen und demselben Körper — etwa Hyperliquid, dessen Einzelkurs für
        // jedes Paar dieselbe «metaAndAssetCtxs»-Antwort (~100 KB) holt
        // Schlüssel ohne Zeitstempel (BingX, `RequestKey`): sonst wären gleiche Anfragen nie gleich
        var key = "\(effective.configuration.timeoutIntervalForRequest)|\(effective.configuration.timeoutIntervalForResource)|\(RequestKey.of(url))"
        if let post {
            let headers = (post.headers ?? [:]).sorted { $0.key < $1.key }.map { $0.key + "=" + $0.value }
            key = "POST|" + key + "|" + headers.joined(separator: ",") + "|" + post.body
        }
        let shared = request
        let text = try await InFlightRequests.shared.value(key) {
            try await gatedLoad(shared, url: url, session: effective)
        }
        // Spiegel-Dateien (auch Altcoin-Saison, coin_meta.json) nur mit gültig signiertem Manifest (`MirrorManifest`)
        if post == nil, MirrorManifest.isSignedFile(url) {
            let file = String(url.dropFirst(DataMirror.base.count))
            let trusted = await verifiedMirror(file: file, text: text, session: session)
            if !trusted { throw MirrorManifest.UnverifiedError() }
        }
        return text
    }

    /// Signatur und SHA-256 der Spiegel-Datei prüfen (`MirrorTrust`); Manifest und Signatur über dieselbe Session.
    /// Nicht privat: auch der Wirtschaftskalender (`MacroCalendarSource`) prüft so.
    static func verifiedMirror(file: String, text: String, session: URLSession = MarketHTTP.session) async -> Bool {
        await MirrorTrust.shared.verified(file: file, text: text) { address, limit in
            await loadMirrorMeta(address, limit: limit, session: session)
        }
    }

    /// Manifest oder Signatur mit eigener Höchstgrösse `limit` (wie Android); nil bei jedem Fehler, auch zu gross.
    /// Nicht privat: `MirrorLoadLimitTests` prüft die Grenzen über diesen Weg.
    static func loadMirrorMeta(_ address: String, limit: Int, session: URLSession) async -> String? {
        guard let u = URL(string: address) else { return nil }
        let request = URLRequest(url: u)
        let effective = Self.sessionFor(host: u.host, requested: session)
        return try? await RequestGate.shared.run(host: u.host ?? "") {
            try await load(request, url: address, session: effective, limit: limit)
        }
    }

    /// `load` erst, wenn `RequestGate` einen Platz frei gibt: Die Fristen der URLSession beginnen so erst mit der
    /// Ausführung, nicht schon in der Warteschlange (wie die Wache auf Android, CallGuard).
    private static func gatedLoad(_ request: URLRequest, url: String, session: URLSession) async throws -> String {
        let host = request.url?.host ?? ""
        return try await RequestGate.shared.run(host: host) {
            try await load(request, url: url, session: session)
        }
    }

    /// Höchstgrösse einer Antwort (wie Android `HttpBodyLimit`): Einzelabfragen 2 MB, Listen 16 MB.
    static let maxBodyBytes = 2 * 1024 * 1024
    static let maxBulkBodyBytes = 16 * 1024 * 1024

    /// Widget-Erweiterung (rund 30 MB Speicher): Listen höchstens 3 MB — die Antwort liegt beim Lesen als Data, als
    /// String und als JSON-Baum zugleich im Speicher (bei 8 MB bis 40–50 MB). Jede Sammelabfrage mit fester Adresse
    /// ist ungepackt höchstens 2 MB (Aufnahme `record_exchange_fixtures.py` verwirft Grösseres, keine wurde verworfen);
    /// die grossen ungefilterten Listen holt das Widget ohnehin nicht (`LowMemoryBulk`).
    static let maxExtensionBodyBytes = 3 * 1024 * 1024

    /// Läuft der Code in einer Erweiterung (Widget)?
    static let isAppExtension = Bundle.main.bundlePath.hasSuffix(".appex")

    /// Grenze je Session: Nur die Einzelabfrage-Session hat die kleine Grenze, alle anderen (Listen,
    /// Sammelabfragen) die grosse — in der Widget-Erweiterung höchstens `maxExtensionBodyBytes`.
    static func bodyLimit(session: URLSession, inExtension: Bool = isAppExtension) -> Int {
        let small = session === MarketHTTP.session || session === addressHostSession
        let limit = small ? maxBodyBytes : maxBulkBodyBytes
        return inExtension ? min(limit, maxExtensionBodyBytes) : limit
    }

    /// Antwort zu gross → `ResponseTooLargeError` (fester Text, wird so gespeichert).
    static func checkBodySize(_ byteCount: Int, session: URLSession, inExtension: Bool = isAppExtension) throws {
        if byteCount > bodyLimit(session: session, inExtension: inExtension) { throw ResponseTooLargeError() }
    }

    /// Antwort gestreamt lesen und abbrechen, sobald sie die Grenze überschreitet — eine angekündigte zu grosse
    /// Antwort (`expectedContentLength`) gar nicht erst lesen. So landet nie mehr als die Grenze (plus ein
    /// Block) im Speicher. `limit` nil = Grenze der Session (`bodyLimit`); eigene Sessions (beobachtete Adressen)
    /// geben ihre Grenze mit.
    static func readLimited(_ request: URLRequest, session: URLSession,
                            limit: Int? = nil) async throws -> (Data, URLResponse) {
        let (bytes, response) = try await session.bytes(for: request)
        let limit = limit ?? bodyLimit(session: session)
        do {
            let data = try await collectLimited(bytes, limit: limit, expected: response.expectedContentLength)
            return (data, response)
        } catch {
            // Zu gross oder abgebrochen: die Übertragung sofort beenden, nicht im Hintergrund weiterladen
            bytes.task.cancel()
            throw error
        }
    }

    /// Grösse eines Blocks beim Lesen: Bytes erst in einem kleinen Puffer sammeln und blockweise an `Data`
    /// hängen — Byte für Byte in ein Array mit Grenzprüfung je Byte war bei grossen Listen (16 MB) spürbar langsam.
    static let readChunkBytes = 64 * 1024

    /// Liest `bytes` bis zum Ende, höchstens `limit` Bytes; mehr → `ResponseTooLargeError`. `expected` > `limit`
    /// (angekündigte Länge) → sofort Fehler, ohne zu lesen. Prüft Grenze und Abbruch des Tasks je Block.
    static func collectLimited<S: AsyncSequence>(_ bytes: S, limit: Int, expected: Int64,
                                                 chunkSize: Int = readChunkBytes) async throws -> Data
    where S.Element == UInt8 {
        if expected > Int64(limit) { throw ResponseTooLargeError() }
        var data = Data()
        if expected > 0 { data.reserveCapacity(Int(expected)) }
        let size = max(1, chunkSize)
        var chunk: [UInt8] = []
        chunk.reserveCapacity(size)
        for try await byte in bytes {
            chunk.append(byte)
            if chunk.count >= size {
                if data.count + chunk.count > limit { throw ResponseTooLargeError() }
                data.append(contentsOf: chunk)
                chunk.removeAll(keepingCapacity: true)
                try Task.checkCancellation()
            }
        }
        if data.count + chunk.count > limit { throw ResponseTooLargeError() }
        data.append(contentsOf: chunk)
        return data
    }

    private static func load(_ request: URLRequest, url: String, session: URLSession,
                             limit: Int? = nil) async throws -> String {
        let (data, response) = try await readLimited(request, session: session, limit: limit)
        let text = String(data: data, encoding: .utf8) ?? String(decoding: data, as: UTF8.self)
        if let http = response as? HTTPURLResponse {
            // «Zu viele Anfragen» auch ohne 429/418 (Kraken 200, Bybit 403/10006, OKX 50011) als 429 weiterreichen
            let rateLimitCode = RateLimitSignal.httpCode(status: http.statusCode, body: text)
            if !(200..<300).contains(http.statusCode) || rateLimitCode != nil {
                HttpLog.add("\(http.statusCode) \(HttpLog.redacted(url))")
                let retryAfter = ExchangeBackoff.parseRetryAfterSeconds(http.value(forHTTPHeaderField: "Retry-After"),
                                                                        now: TimeUtils.nowMillis)
                throw HttpMarketError(httpCode: rateLimitCode ?? http.statusCode, body: text, retryAfterSeconds: retryAfter)
            }
        }
        HttpLog.add("200 \(HttpLog.redacted(url)) · \(data.count / 1024) KB")
        return text
    }
}

/// Gleiche Anfragen, die gleichzeitig laufen, nur einmal schicken (Mini-Chart, Blatt und
/// Aktualisierung für dasselbe Paar; POST mit gleichen Kopfzeilen und gleichem Körper, z. B. Hyperliquid). Nichts wird zwischengespeichert: Ist die Anfrage fertig,
/// fällt der Eintrag weg. Bricht der einzige Wartende ab (Blatt zu), wird die Anfrage abgebrochen.
actor InFlightRequests {
    static let shared = InFlightRequests()

    private struct Entry {
        let id: UUID
        let task: Task<String, Error>
        var waiters: Int
    }

    private var entries: [String: Entry] = [:]

    func value(_ key: String, load: @escaping @Sendable () async throws -> String) async throws -> String {
        do {
            return try await join(key, load: load)
        } catch let error where Self.isCancellation(error) && !Task.isCancelled {
            // Geerbte Anfrage wurde abgebrochen, obwohl dieser Wartende weiter will (er kam gerade an, als der
            // einzige frühere ging): einmal selbst neu senden, statt «abgebrochen» als Börsenfehler zu speichern
            return try await join(key, load: load)
        }
    }

    /// Abbruch statt Antwort (Task abgebrochen oder URLSession-Übertragung beendet)?
    static func isCancellation(_ error: Error) -> Bool {
        error is CancellationError || (error as? URLError)?.code == .cancelled
    }

    private func join(_ key: String, load: @escaping @Sendable () async throws -> String) async throws -> String {
        let id: UUID
        let task: Task<String, Error>
        if var entry = entries[key] {
            entry.waiters += 1
            entries[key] = entry
            id = entry.id
            task = entry.task
        } else {
            id = UUID()
            task = Task { try await load() }
            entries[key] = Entry(id: id, task: task, waiters: 1)
        }
        defer { leave(key, id: id) }
        return try await withTaskCancellationHandler {
            try await task.value
        } onCancel: {
            Task { await self.cancelIfAlone(key, id: id) }
        }
    }

    private func leave(_ key: String, id: UUID) {
        guard var entry = entries[key], entry.id == id else { return }
        entry.waiters -= 1
        if entry.waiters <= 0 {
            entries[key] = nil
        } else {
            entries[key] = entry
        }
    }

    /// Der einzige Wartende bricht ab: Anfrage abbrechen und den Eintrag sofort entfernen — wer danach dieselbe
    /// Anfrage stellt, startet eine neue, statt die abgebrochene zu erben (bis `leave` lief, stand sie sonst noch da).
    private func cancelIfAlone(_ key: String, id: UUID) {
        guard let entry = entries[key], entry.id == id, entry.waiters <= 1 else { return }
        entries[key] = nil
        entry.task.cancel()
    }

    /// Laufende Einträge (nur Tests).
    var count: Int { entries.count }
}

/// Mitgeschnittene HTTP-Zeilen für das Entwickler-Protokoll im Explorer; nur im Speicher, die jüngsten `capacity`
/// Zeilen. Android: `HttpLogger`.
enum HttpLog {
    private static let capacity = 200
    private static let entries = OSAllocatedUnfairLock(initialState: [String]())

    /// Hängt `line` mit der Uhrzeit («HH:mm:ss») an; die ältesten Zeilen fallen weg. Nur in Debug-Builds (das Protokoll
    /// ist nur dort sichtbar, `DeveloperOptions.available`) — im Release wird nichts mitgeschnitten, auch nicht im Speicher.
    static func add(_ line: String) {
        #if DEBUG
        let clock = DateFormatter()
        clock.dateFormat = "HH:mm:ss"
        let stamped = clock.string(from: Date()) + " " + line
        let limit = capacity
        entries.withLock { lines in
            lines.append(stamped)
            let excess = lines.count - limit
            if excess > 0 { lines.removeFirst(excess) }
        }
        #endif
    }

    static var all: [String] { entries.withLock { $0 } }

    /// Adresse für das Protokoll: Host und Pfad ohne Abfrage; lange Pfadteile (Adressen, Schlüssel) gekürzt.
    static func redacted(_ text: String) -> String {
        guard let url = URL(string: text) else { return "?" }
        let path = url.path.split(separator: "/").map { part -> String in
            part.count > 20 ? String(part.prefix(4)) + "…" + String(part.suffix(4)) : String(part)
        }.joined(separator: "/")
        return (url.host ?? "") + (path.isEmpty ? "" : "/" + path) + (url.query == nil ? "" : "?…")
    }

    static func clear() {
        entries.withLock { $0.removeAll() }
    }
}
