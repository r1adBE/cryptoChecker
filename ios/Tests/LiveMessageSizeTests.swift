import XCTest
@testable import CryptoChecker

/// Grösste Nachricht des Live-Stroms: 1 MiB, ausdrücklich als `maximumMessageSize` gesetzt — wie Android
/// `LiveMessageSizeTest.kt` (`LiveRules.MAX_MESSAGE_BYTES`).
final class LiveMessageSizeTests: XCTestCase {
    func testGrenzeIstEinMebibyteWieAndroid() {
        XCTAssertEqual(LiveRules.maxMessageBytes, 1_048_576)
    }

}
