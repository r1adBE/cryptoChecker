import XCTest
@testable import CryptoChecker

/// Coin-Favoriten haben einen Besitzer (`AppData.favorites`): Stern im Markt (`CycleViewModel.toggleFavoriteCoin`)
/// und Stern im Explorer (`AppData.toggleFavorite(.COIN, …)`) schreiben dieselbe Menge — keiner überschreibt den
/// anderen mit einem älteren Stand. Wie Android `FavoritesRepository` (ein Singleton für Markt und Explorer).
@MainActor
final class CycleFavoriteCoinTests: XCTestCase {
    private var saved: Set<String> = []

    override func setUp() async throws {
        saved = AppData.shared.favorites[.COIN] ?? []
        AppData.shared.setFavorites(.COIN, [])
    }

    override func tearDown() async throws {
        AppData.shared.setFavorites(.COIN, saved)
    }

    /// BTC im Markt, danach ETH im Explorer: beide bleiben, im Speicher, in `AppData` und im Markt.
    func testMarketThenExplorerKeepsBoth() {
        let data = AppData.shared
        let market = CycleViewModel(data: data)
        market.toggleFavoriteCoin("BTC")
        XCTAssertEqual(data.favorites[.COIN], ["BTC"])
        data.toggleFavorite(.COIN, "ETH")
        XCTAssertEqual(data.favorites[.COIN], ["BTC", "ETH"])
        XCTAssertEqual(SharedStorage.favorites(.COIN), ["BTC", "ETH"])
        XCTAssertEqual(market.favoriteCoins, ["BTC", "ETH"])
    }

    /// Umgekehrt: Explorer zuerst, dann der Markt — und ein zweiter Stern im Markt nimmt nur diesen Coin weg.
    func testExplorerThenMarketKeepsBoth() {
        let data = AppData.shared
        let market = CycleViewModel(data: data)
        data.toggleFavorite(.COIN, "ETH")
        market.toggleFavoriteCoin("BTC")
        XCTAssertEqual(SharedStorage.favorites(.COIN), ["BTC", "ETH"])
        market.toggleFavoriteCoin("BTC")
        XCTAssertEqual(data.favorites[.COIN], ["ETH"])
        XCTAssertEqual(SharedStorage.favorites(.COIN), ["ETH"])
        XCTAssertEqual(market.favoriteCoins, ["ETH"])
    }
}
