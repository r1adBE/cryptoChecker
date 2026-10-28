import XCTest
@testable import CryptoChecker

/// Eigener Bitcoin-Server: 403/451 sperrt ihn nicht 6 h (`BlockedSources`); öffentliche Hosts weiter. Speichern bzw.
/// erfolgreicher Test (`clearServerPauses`) hebt eine Sperre auf. Gegenstück: `OwnServerBlockTest.kt`.
final class OwnServerBlockTests: XCTestCase {
    private let source = WatchedAddressSource()

    private func failWith(_ code: Int, entry: String, own: Bool) async {
        do {
            _ = try await source.firstWorking([entry], deadline: nil, own: own) { (_: String) -> Int in
                throw HttpExchangeError(httpCode: code, body: "", retryAfterSeconds: nil)
            }
            XCTFail("erwartet: Fehler")
        } catch {}
    }

    func testEigenerServerWirdNichtGesperrt() async {
        await failWith(403, entry: "https://own-block-test.example/api", own: true)
        XCTAssertFalse(BlockedSources.isBlocked("own-block-test.example"))
        await failWith(451, entry: "https://own-block-test.example/api", own: true)
        XCTAssertFalse(BlockedSources.isBlocked("own-block-test.example"))
    }

    func testOeffentlicherHostWirdGesperrtUndSpeichernHebtAuf() async {
        await failWith(403, entry: "public-block-test.example", own: false)
        XCTAssertTrue(BlockedSources.isBlocked("public-block-test.example"))
        await source.clearServerPauses("https://public-block-test.example:3006/api")
        XCTAssertFalse(BlockedSources.isBlocked("public-block-test.example"))
    }

    func testEigenerServerNachSperreWiederGefragt() async throws {
        // Alte Sperre desselben Hosts (z. B. vor dem Speichern): der eigene Server wird trotzdem gefragt
        await failWith(403, entry: "stale-block-test.example", own: false)
        let (value, _) = try await source.firstWorking(["https://stale-block-test.example"], deadline: nil, own: true) {
            (_: String) -> Int in 7
        }
        XCTAssertEqual(value, 7)
    }
}
