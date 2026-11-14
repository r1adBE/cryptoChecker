import XCTest
@testable import CryptoChecker

/// «App zurücksetzen» räumt auch die Uhr auf: `PhoneWatchLink.reset` setzt den Stand im Speicher wie nach der
/// Installation (Schalter «Portfolio-Wert auf der Uhr» aus) und sendet der Uhr Löschen, leere iPhone-Liste und leere
/// Kurse. Wie Android `PhoneWatchSync.clearWatch`.
final class WatchResetTests: XCTestCase {
    private func source(_ path: String) throws -> String {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        return try String(contentsOf: root.appendingPathComponent(path), encoding: .utf8)
    }

    func testResetAppResetsWatchLink() throws {
        let reset = try source("App/Services/AppData+Reset.swift")
        let clear = try XCTUnwrap(reset.range(of: "Self.clearStoredFiles()"))
        let link = try XCTUnwrap(reset.range(of: "PhoneWatchLink.shared.reset()"))
        XCTAssertTrue(clear.lowerBound < link.lowerBound, "nach dem Leeren der Einstellungen")
    }

    func testLinkResetClearsAndSends() throws {
        let link = try source("App/Services/PhoneWatchLink.swift")
        let start = try XCTUnwrap(link.range(of: "func reset() {"))
        let body = String(link[start.lowerBound...].prefix(600))
        for part in ["settings = WatchSettings()", "portfolioOnWatch = false", "quotesJson = WatchPhoneQuotes.toJson([])",
                     "portfolioJson = WatchPortfolio.toJson(.clear)", "send()"] {
            XCTAssertTrue(body.contains(part), part)
        }
        // Leere Kurse vom iPhone heissen auf der Uhr: keine Coins mehr vom iPhone
        XCTAssertEqual(WatchPhoneQuotes.fromJson(WatchPhoneQuotes.toJson([])), [])
        XCTAssertEqual(WatchPortfolio.fromJson(WatchPortfolio.toJson(.clear)), .clear)
    }
}
