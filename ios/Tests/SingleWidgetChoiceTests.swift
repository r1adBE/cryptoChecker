import XCTest
@testable import CryptoChecker

/// Einzel-Widget merkt das Paar, nicht nur die Id (B3-08; Audit A-01, früher Quelltext-Wächter). Wie Android
/// `SingleWidgetChoiceDeviceTest.kt`.
final class SingleWidgetChoiceTests: XCTestCase {
    private func watch(_ id: Int64, _ base: String) -> Watch {
        Watch(id: id, exchangeKey: "Binance", exchangeName: "Binance", baseAsset: base, quoteAsset: "USDT")
    }

    func testSamePairIsShown() {
        let btc = watch(5, "BTC")
        let entity = SingleWidgetChoice.entityId(btc)
        XCTAssertEqual(SingleWidgetChoice.watchId(entity), 5)
        XCTAssertEqual(SingleWidgetChoice.chosen(entity, in: [watch(4, "ETH"), btc])?.baseAsset, "BTC")
    }

    func testOtherPairUnderTheSameIdAsksToChoose() {
        let entity = SingleWidgetChoice.entityId(watch(5, "BTC"))
        // Eingespielte Sicherung: Id 5 ist jetzt ETH
        XCTAssertNil(SingleWidgetChoice.chosen(entity, in: [watch(5, "ETH")]))
        XCTAssertNil(SingleWidgetChoice.chosen(entity, in: []), "Paar gelöscht")
    }

    func testOlderChoiceWithoutPairKeepsWorking() {
        XCTAssertEqual(SingleWidgetChoice.watchId("5"), 5)
        XCTAssertEqual(SingleWidgetChoice.chosen("5", in: [watch(5, "ETH")])?.baseAsset, "ETH")
        XCTAssertNil(SingleWidgetChoice.chosen("x", in: [watch(5, "ETH")]))
    }
}
