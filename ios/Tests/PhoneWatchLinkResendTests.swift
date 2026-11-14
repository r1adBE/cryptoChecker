import XCTest
@testable import CryptoChecker

/// Der Stand des iPhones erreicht die Uhr auch nach einem gescheiterten Senden: erneuter Versuch nach `minGap`
/// (begrenzt), und meldet die Uhr einen anderen iPhone-Teil (`WatchSettings.phoneSideDiffers`), geht alles noch einmal
/// weg — wie Android `PhoneWatchSync`. Uhr-App dazugekommen: `sessionWatchStateDidChange` sendet.
final class PhoneWatchLinkResendTests: XCTestCase {
    func testResendTriggers() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let link = try String(contentsOf: root.appendingPathComponent("App/Services/PhoneWatchLink.swift"), encoding: .utf8)
        XCTAssertTrue(link.contains("if WatchSettings.phoneSideDiffers(settings, watch: remote) { send() }"))
        XCTAssertTrue(link.contains("try session.updateApplicationContext(context)"))
        XCTAssertFalse(link.contains("try? session.updateApplicationContext(context)"))
        XCTAssertTrue(link.contains("if failedSends <= Self.maxRetries { sendSoon() }"))
        XCTAssertTrue(link.contains("nonisolated func sessionWatchStateDidChange(_ session: WCSession) {\n        Task { @MainActor in self.send() }"))
    }
}
