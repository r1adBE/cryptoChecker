import XCTest
@testable import CryptoChecker

/// «Symbole erklärt»: die Liste nennt jedes Symbol der Zeile, in deren Reihenfolge — gleich wie Android `WatchlistSymbol`.
final class WatchlistSymbolTests: XCTestCase {
    func testLegendListsTheRowSymbolsInRowOrder() {
        XCTAssertEqual(WatchlistSymbol.allCases.map(\.rawValue),
                       ["FAVORITE", "TOKEN", "TRADFI", "ALPHA", "PRICE_NOTIFICATION_ON", "PRICE_NOTIFICATION_OFF",
                        "ACTIVE_ALARMS", "NOTE", "WARNING"])
    }

    func testEveryAssetKindHasItsSymbolAndTheBellsHaveNone() {
        XCTAssertEqual(WatchlistSymbol.allCases.compactMap(\.kind), AssetKind.allCases)
        XCTAssertNil(WatchlistSymbol.PRICE_NOTIFICATION_ON.kind)
        XCTAssertNil(WatchlistSymbol.PRICE_NOTIFICATION_OFF.kind)
        XCTAssertNil(WatchlistSymbol.ACTIVE_ALARMS.kind)
        XCTAssertNil(WatchlistSymbol.FAVORITE.kind)
        XCTAssertNil(WatchlistSymbol.NOTE.kind)
        XCTAssertNil(WatchlistSymbol.WARNING.kind)
    }

    func testEveryLineHasATranslatedText() {
        for symbol in WatchlistSymbol.allCases {
            let text = L(symbol.textKey)
            XCTAssertFalse(text.isEmpty, symbol.rawValue)
            XCTAssertNotEqual(text, symbol.textKey, symbol.rawValue)
        }
        XCTAssertNotEqual(L("watchlist_symbols_legend"), "watchlist_symbols_legend")
    }
}
