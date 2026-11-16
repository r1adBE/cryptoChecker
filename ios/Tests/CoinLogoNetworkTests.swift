import XCTest
import ImageIO
@testable import CryptoChecker

/// Coin-Logos bei Fehlern (Audit A-01, früher Quelltext-Wächter), Antworten aus Test-Transporten statt Netz:
/// Netzfehler auf einer Folgeseite der Rangliste = Teil-Ergebnis, aber bald erneut (kein Zeitpunkt, kein Versuch gemerkt);
/// Speicher voll = eigene Art (der Durchgang lädt nichts mehr); lauter «gibt es nicht» ist kein Netzfehler, lauter
/// Störungen schon. Wie Android `LogoRankingNetworkCutTest.kt` und `LogoImageStoreFailureTest.kt` (Gerät).
final class CoinLogoNetworkTests: XCTestCase {
    private let defaults = SharedStorage.defaults
    private var testFiles: [URL] = []

    override func setUp() async throws {
        try await super.setUp()
        clearMapState()
    }

    override func tearDown() async throws {
        ExchangeHTTP.testTransport.withLock { $0 = nil }
        CoinLogoStore.imageTestTransport.withLock { $0 = nil }
        clearMapState()
        defaults.removeObject(forKey: CoinLogoStore.failedKey)
        testFiles.forEach { try? FileManager.default.removeItem(at: $0) }
        // Test-Rangliste nicht liegen lassen (Datei der Zuordnung und der Namen)
        try? FileManager.default.removeItem(at: CoinLogoStore.mapURL)
        try? FileManager.default.removeItem(at: CoinLogoStore.namesURL)
        await CoinLogoStore.shared.forgetMapForTest()
        try await super.tearDown()
    }

    private func clearMapState() {
        defaults.removeObject(forKey: CoinLogoStore.mapTimeKey)
        defaults.removeObject(forKey: CoinLogoStore.mapAttemptKey)
    }

    private func rows(_ page: Int, _ count: Int) -> String {
        "[" + (0..<count).map { i in
            #"{"symbol":"t\#(page)x\#(i)","name":"Test \#(page) \#(i)","image":"https://coin-images.coingecko.com/coins/images/\#(page)\#(i)/large/t.png"}"#
        }.joined(separator: ",") + "]"
    }

    private static func page(_ url: String) -> Int? {
        guard url.hasPrefix("https://api.coingecko.com/api/v3/coins/markets") else { return nil }
        return URLComponents(string: url)?.queryItems?.first { $0.name == "page" }?.value.flatMap(Int.init)
    }

    func testRankingNetworkCutOnALaterPageRetriesSoon() async throws {
        let fullFirstPage = rows(1, CoinLogos.perPage)
        ExchangeHTTP.testTransport.withLock {
            $0 = { url in
                switch Self.page(url) {
                case 1: return fullFirstPage
                case 2: throw URLError(.networkConnectionLost)
                default: throw HttpExchangeError(httpCode: 404, body: "test")
                }
            }
        }
        await CoinLogoStore.shared.forgetMapForTest()
        let map = await CoinLogoStore.shared.symbolMapForTest()
        XCTAssertTrue(map.keys.contains { $0.uppercased().hasPrefix("T1X") }, "Teil-Ergebnis der ersten Seite genutzt")
        let failed = await CoinLogoStore.shared.mapNetworkFailed
        XCTAssertTrue(failed)
        XCTAssertEqual(defaults.double(forKey: CoinLogoStore.mapTimeKey), 0, "kein Stand gemerkt")
        XCTAssertEqual(defaults.double(forKey: CoinLogoStore.mapAttemptKey), 0, "kein Versuch gemerkt (bald erneut)")
    }

    func testCompleteRankingKeepsItsTime() async throws {
        let shortFirstPage = rows(1, 3)
        ExchangeHTTP.testTransport.withLock {
            $0 = { url in
                guard Self.page(url) == 1 else { throw HttpExchangeError(httpCode: 404, body: "test") }
                return shortFirstPage
            }
        }
        await CoinLogoStore.shared.forgetMapForTest()
        _ = await CoinLogoStore.shared.symbolMapForTest()
        let failed = await CoinLogoStore.shared.mapNetworkFailed
        XCTAssertFalse(failed)
        XCTAssertGreaterThan(defaults.double(forKey: CoinLogoStore.mapTimeKey), 0)
    }

    // MARK: Bilder

    /// Rotes 64×64-PNG (CoreGraphics/ImageIO, ohne UIKit-Hauptfaden).
    private static let png: Data = {
        let ctx = CGContext(data: nil, width: 64, height: 64, bitsPerComponent: 8, bytesPerRow: 0,
                            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.setFillColor(red: 1, green: 0, blue: 0, alpha: 1)
        ctx.fill(CGRect(x: 0, y: 0, width: 64, height: 64))
        let out = NSMutableData()
        let dest = CGImageDestinationCreateWithData(out, "public.png" as CFString, 1, nil)!
        CGImageDestinationAddImage(dest, ctx.makeImage()!, nil)
        CGImageDestinationFinalize(dest)
        return out as Data
    }()

    private static func respond(_ url: URL, _ code: Int, _ body: Data?) -> (response: HTTPURLResponse, body: Data?) {
        (HTTPURLResponse(url: url, statusCode: code, httpVersion: "HTTP/1.1", headerFields: nil)!, body)
    }

    func testStorageFullIsItsOwnKindAndStopsThePass() async throws {
        let requests = OSAllocatedUnfairLock(initialState: 0)
        let png = Self.png
        CoinLogoStore.imageTestTransport.withLock {
            $0 = { url in
                requests.withLock { $0 += 1 }
                return Self.respond(url, 200, png)
            }
        }
        let streaks = ForbiddenStreaks()
        // Ordner fehlt: das Bild kommt, lässt sich aber nicht speichern (wie «Speicher voll»)
        let first = await CoinLogoStore.download(name: "missing-\(UUID().uuidString)/A.png",
                                                 url: "https://coin-images.coingecko.com/coins/images/1/large/a.png", streaks: streaks)
        XCTAssertEqual(first, .unsaved)
        let afterFirst = requests.withLock { $0 }
        let second = await CoinLogoStore.download(name: "B.png", url: "https://coin-images.coingecko.com/coins/images/2/large/b.png",
                                                  streaks: streaks)
        XCTAssertEqual(second, .unsaved, "nach dem ersten nicht gespeicherten Bild lädt der Durchgang nichts mehr")
        XCTAssertEqual(requests.withLock { $0 }, afterFirst)
        // Neuer Durchgang mit Platz: gespeichert
        let name = "TESTLOGO\(Int.random(in: 1000...9999)).png"
        testFiles.append(CoinLogoStore.directory.appendingPathComponent(name))
        let fresh = await CoinLogoStore.download(name: name, url: "https://coin-images.coingecko.com/coins/images/3/large/c.png",
                                                 streaks: ForbiddenStreaks())
        XCTAssertEqual(fresh, .ok)
    }

    private func wanted(_ prefix: String) -> [String: String] {
        Dictionary(uniqueKeysWithValues: (0..<CoinLogoRetry.allFailedMin).map { i in
            let key = "\(prefix)\(i)\(Int.random(in: 100_000...999_999))"
            // Je Bild ein eigener Server: keine Störungsfolge, die einen Server pausierte
            return (key, "https://l\(i).coingecko.com/coins/images/\(i)/large/\(key).png")
        })
    }

    private func trackFiles(_ all: [String: String]) {
        testFiles += all.keys.compactMap { CoinLogos.fileName($0) }.map { CoinLogoStore.directory.appendingPathComponent($0) }
    }

    func testOnlyMissingImagesAreNoNetworkFailure() async throws {
        try XCTSkipUnless(NetworkStatus.shared.isOnline, "Simulator meldet kein Netz")
        CoinLogoStore.imageTestTransport.withLock { $0 = { url in Self.respond(url, 404, nil) } }
        let all = wanted("NOLOGO")
        trackFiles(all)
        let result = await CoinLogoStore.downloadMissing(all, order: Array(all.keys))
        XCTAssertFalse(result.network, "lauter «gibt es nicht» ist kein Netzfehler")
        XCTAssertEqual(result.added, 0)
    }

    func testOnlyDisturbancesAreANetworkFailure() async throws {
        CoinLogoStore.imageTestTransport.withLock { $0 = { _ in throw URLError(.timedOut) } }
        let all = wanted("SLOWLOGO")
        trackFiles(all)
        let result = await CoinLogoStore.downloadMissing(all, order: Array(all.keys))
        XCTAssertTrue(result.network, "alle gestört: am Netz gescheitert, keine Tagessperre")
        XCTAssertTrue(result.incomplete)
        let failures = CoinLogoStore.loadFailures(now: EpochTime.nowMillis)
        XCTAssertTrue(all.keys.compactMap { CoinLogos.fileName($0) }.allSatisfy { failures[$0] == nil }, "nichts gesperrt")
    }
}
