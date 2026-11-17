import XCTest
@testable import CryptoChecker

/// Live-Aktivität und «Paar ändern» bzw. Wiederherstellen: Name und Börse stehen fest in den Attributen — eine Aktivität
/// passt nur zu derselben Id MIT demselben Paar (`PriceActivityAttributes.matches`); sonst endet sie beim nächsten
/// Aktualisieren und startet (im Vordergrund, z. B. nach «Paar ändern») mit dem neuen Paar neu.
final class LiveActivityPairTests: XCTestCase {
    private func watch(_ id: Int64, _ base: String, exchange: String = "Binance") -> Watch {
        Watch(id: id, exchangeKey: exchange, exchangeName: exchange, baseAsset: base, quoteAsset: "USDT")
    }

    func testSamePairMatches() {
        let btc = watch(1, "BTC")
        XCTAssertTrue(PriceActivityAttributes(watch: btc).matches(btc))
    }

    func testOtherPairWithSameIdDoesNotMatch() {
        let attributes = PriceActivityAttributes(watch: watch(1, "BTC"))
        XCTAssertFalse(attributes.matches(watch(1, "ETH")))
        XCTAssertFalse(attributes.matches(watch(1, "BTC", exchange: "Kraken")))
        XCTAssertFalse(attributes.matches(watch(2, "BTC")))
    }
}
