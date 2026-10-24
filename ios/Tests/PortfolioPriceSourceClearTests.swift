import XCTest
@testable import CryptoChecker

/// Review IOS2-04: «Portfolio leeren» (`PortfolioPriceSource.clearStored`) vergisst auch die Coins
/// mit «Kurs nicht verfügbar» — sie nennen gehaltene Coins. Android: `PortfolioPriceSourceStore.kt` › `clearStored`.
final class PortfolioPriceSourceClearTests: XCTestCase {

    override func tearDown() {
        PortfolioPriceSource.lock.lock()
        PortfolioPriceSource.unavailableCoins = []
        PortfolioPriceSource.uncheckedCoins = []
        PortfolioPriceSource.lock.unlock()
        super.tearDown()
    }

    func testClearStoredForgetsUnavailableCoins() {
        PortfolioPriceSource.lock.lock()
        PortfolioPriceSource.unavailableCoins = ["ZZTESTA", "ZZTESTB"]
        PortfolioPriceSource.uncheckedCoins = ["ZZTESTC"]
        PortfolioPriceSource.lock.unlock()
        // Gegenprobe: vor dem Leeren gesetzt (gelesen unter der Sperre wie jeder Zugriff)
        let before = PortfolioPriceSource.lock.withLock { PortfolioPriceSource.unavailableCoins }
        XCTAssertEqual(before, ["ZZTESTA", "ZZTESTB"])

        PortfolioPriceSource.clearStored()

        PortfolioPriceSource.lock.lock()
        let unavailable = PortfolioPriceSource.unavailableCoins
        let unchecked = PortfolioPriceSource.uncheckedCoins
        PortfolioPriceSource.lock.unlock()
        XCTAssertTrue(unavailable.isEmpty, "unavailableCoins nach clearStored: \(unavailable)")
        XCTAssertTrue(unchecked.isEmpty, "uncheckedCoins nach clearStored: \(unchecked)")
    }
}
