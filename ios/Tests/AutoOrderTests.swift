import XCTest
@testable import CryptoChecker

/// Wie `AutoOrderTest.kt`: dieselben Paare, dieselben Erwartungen.
final class AutoOrderTests: XCTestCase {
    private struct Row { let id: Int; let name: String; let exchange: String; var fav = false }

    // eigene Reihenfolge
    private let rows = [
        Row(id: 1, name: "ETH/USDT", exchange: "Kraken"),
        Row(id: 2, name: "btc/USDT", exchange: "Binance"),
        Row(id: 3, name: "BTC/USDT Perp", exchange: "Bybit"),
        Row(id: 4, name: "BTC/USDT", exchange: "bitstamp"),
        Row(id: 5, name: "SOL/CHF", exchange: "Binance", fav: true),
        Row(id: 6, name: "ADA/USDT", exchange: "Kraken", fav: true),
        Row(id: 7, name: "ETH/USDT", exchange: "Kraken"),
        Row(id: 8, name: "eth/usdt", exchange: "Binance"),
    ]

    private func ids(_ order: AutoOrder, _ items: [Row]? = nil) -> [Int] {
        order.apply(items ?? rows, favorite: { $0.fav }, name: { $0.name }, exchange: { $0.exchange }).map(\.id)
    }

    func testCoinPutsSameCoinsTogether() {
        // Favoriten zuerst (A–Z), dann Name A–Z, bei gleichem Namen Börse A–Z, sonst bisherige Reihenfolge
        XCTAssertEqual(ids(.coin), [6, 5, 2, 4, 3, 8, 1, 7])
    }

    func testExchangeGroupsByExchange() {
        // Favoriten nach Börse (Binance vor Kraken), dann Binance, bitstamp, Bybit, Kraken — je Name A–Z
        XCTAssertEqual(ids(.exchange), [5, 6, 2, 8, 4, 3, 1, 7])
    }

    func testEmptyAndSingleStayUnchanged() {
        XCTAssertEqual(ids(.coin, []), [])
        XCTAssertEqual(ids(.exchange, [rows[4]]), [5])
    }

    func testOrderingTwiceChangesNothing() {
        let once = AutoOrder.coin.apply(rows, favorite: { $0.fav }, name: { $0.name }, exchange: { $0.exchange })
        XCTAssertEqual(ids(.coin, once), once.map(\.id))
    }
}
