import XCTest
@testable import CryptoChecker

/// PERF3-R1: Schlüssel der Coin-Ansicht hängt nur an den Kursen des Coins und der Coins, deren Tageskurs
/// seine Zeilen brauchen — wie Android `PortfolioCoinDetailFlowTest`.
final class PortfolioCoinLedgerKeyTests: XCTestCase {
    private var trades: [PortfolioTx] {
        let buy = PortfolioTx(id: 1, coin: "BTC", type: .BUY, amountExact: 1, priceExact: 20_000, time: 86_400_000)
        // Tausch BTC → SOL ohne Wert: braucht den Tageskurs von BTC bzw. SOL
        var swap = PortfolioTx(id: 2, coin: "BTC", type: .SWAP, amountExact: 1, time: 2 * 86_400_000)
        swap.toCoin = "SOL"
        swap.toAmountExact = 10
        let eth = PortfolioTx(id: 3, coin: "ETH", type: .BUY, amountExact: 1, priceExact: 1_500, time: 3 * 86_400_000)
        return [buy, swap, eth]
    }

    func testGebuehrCoinsNurAusEigenenZeilen() {
        XCTAssertEqual(PortfolioCoinLedgerKey.feeCoins(for: "BTC", trades: trades), ["BTC", "SOL"])
        XCTAssertEqual(PortfolioCoinLedgerKey.feeCoins(for: "SOL", trades: trades), ["BTC", "SOL"])
        XCTAssertEqual(PortfolioCoinLedgerKey.feeCoins(for: "ETH", trades: trades), [])
    }

    func testFremderKursAendertDieRechnungNicht() {
        let fee = PortfolioCoinLedgerKey.feeCoins(for: "BTC", trades: trades)
        func input(_ prices: [String: Double]) -> PortfolioCoinLedgerInput {
            let needed = fee.union(["BTC"])
            return PortfolioCoinLedgerInput(key: PortfolioCoinLedgerKey(
                coin: "BTC", trades: trades, prices: prices.filter { needed.contains($0.key) }, method: .FIFO,
                closesStamp: 0, feeCoins: fee), closes: [:])
        }
        let a = input(["BTC": 40_000, "SOL": 100, "ETH": 2_000])
        XCTAssertEqual(a.key, input(["BTC": 40_000, "SOL": 100, "ETH": 2_100]).key)
        XCTAssertNotEqual(a.key, input(["BTC": 40_000, "SOL": 110, "ETH": 2_000]).key)
        // Gleiches Ergebnis wie mit allen Kursen (frühere Rechnung)
        let full = PortfolioCoinLedgerInput(key: PortfolioCoinLedgerKey(
            coin: "BTC", trades: trades, prices: ["BTC": 40_000, "SOL": 100, "ETH": 2_000], method: .FIFO,
            closesStamp: 0, feeCoins: fee), closes: [:])
        XCTAssertEqual(PortfolioCoinLedgerInput.ledger(a), PortfolioCoinLedgerInput.ledger(full))
    }
}
