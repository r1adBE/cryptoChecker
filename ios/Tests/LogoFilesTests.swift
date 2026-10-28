import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle für Adressen und Abrufe der Logo-Dateien (`coin_logos.json`: urlFor, downloadJobs, futures)
/// und das Nachholen nach Pausen der Bild-Server (`logo_fallback.json`: pauseRetry) — Android:
/// `LogoFilesParityTest.kt`.
final class LogoFilesTests: XCTestCase {

    private typealias S = TestSupport

    /// Zeilen `[a, b, …]` einer Liste; JSON-null → nil.
    private func rows(_ value: Any?) -> [[Any?]] {
        ((value as? [Any]) ?? []).compactMap { item in
            (item as? [Any])?.map { (v: Any) -> Any? in v is NSNull ? nil : v }
        }
    }

    private func int64(_ value: Any?) -> Int64? {
        (value as? NSNumber)?.int64Value
    }

    func testURLForUsesTheMergeKey() throws {
        let u = try XCTUnwrap(try S.fixture("coin_logos")["urlFor"] as? [String: Any])
        let map = try XCTUnwrap(u["map"] as? [String: String])
        for row in rows(u["cases"]) {
            let symbol = row[0] as? String ?? ""
            XCTAssertEqual(CoinLogos.urlFor(map, symbol: symbol), row[1] as? String, "urlFor: \(symbol)")
        }
    }

    func testDownloadJobsOnePerFile() throws {
        let d = try XCTUnwrap(try S.fixture("coin_logos")["downloadJobs"] as? [String: Any])
        let wanted = rows(d["wanted"]).map { ($0[0] as? String ?? "", $0[1] as? String ?? "") }
        let skip = Set(try XCTUnwrap(d["skip"] as? [String]))
        let expected = rows(d["expected"]).map { "\($0[0] as? String ?? "")=\($0[1] as? String ?? "")" }
        let actual = CoinLogos.downloadJobs(wanted) { skip.contains($0) }.map { "\($0.name)=\($0.url)" }
        XCTAssertEqual(actual, expected)
    }

    /// Futures-Kürzel der Merkliste über die echte Zusammenführung: Adresse und Datei je Kürzel.
    func testFuturesSymbolsFindTheirLogo() throws {
        let f = try XCTUnwrap(try S.fixture("coin_logos")["futures"] as? [String: Any])
        let markets = try XCTUnwrap(CoinMeta.parseMarketsPage(f["markets"] as? String))
        let meta = try XCTUnwrap(CoinMeta.parse(f["meta"] as? String))
        let map = CoinMeta.merge(geckoTop: markets, binance: meta.binance, alpha: meta.alpha).map
        for row in rows(f["cases"]) {
            let symbol = row[0] as? String ?? ""
            XCTAssertEqual(CoinLogos.urlFor(map, symbol: symbol), row[1] as? String, "url: \(symbol)")
            XCTAssertEqual(CoinLogos.fileName(symbol), row[2] as? String, "file: \(symbol)")
        }
    }

    func testPauseRetryDelay() throws {
        let f = try S.fixture("logo_fallback")
        for row in rows(f["pauseRetry"]) {
            XCTAssertEqual(CoinLogoRetry.pauseRetryDelayMillis(pausedUntil: int64(row[0]), now: int64(row[1]) ?? 0),
                           int64(row[2]), "pauseRetry: \(row)")
        }
    }
}
