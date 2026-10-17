import XCTest
@testable import CryptoChecker

/// Build 46: Vorschläge und «Meintest du …?» für Wallet-Namen — wie `PortfolioWalletNamesTest.kt` (Android).
final class PortfolioWalletNamesTests: XCTestCase {
    private let used: [String] = ["Binance", "Ledger Nano", "Kraken"]

    func testFoldIgnoresCaseSpacesAndAccents() {
        XCTAssertEqual(PortfolioWalletNames.fold(" Bin ance "), "binance")
        XCTAssertEqual(PortfolioWalletNames.fold("Lédger"), "ledger")
        XCTAssertEqual(PortfolioWalletNames.fold("KRAKEN"), "kraken")
    }

    func testKnownKeepsFirstSpellingAndDropsEmpty() {
        XCTAssertEqual(PortfolioWalletNames.known([" Binance ", nil, "", "binance", "Kraken", "BIN ANCE"]),
                       ["Binance", "Kraken"])
    }

    func testTypoIsDetected() {
        XCTAssertEqual(PortfolioWalletNames.nearDuplicate("Binanse", known: used), "Binance")
        XCTAssertEqual(PortfolioWalletNames.nearDuplicate("Bianance", known: used), "Binance")
        XCTAssertEqual(PortfolioWalletNames.nearDuplicate("Krakn", known: used), "Kraken")
    }

    func testSpacesAndAccentsCountAsNear() {
        XCTAssertEqual(PortfolioWalletNames.nearDuplicate("Bin ance", known: used), "Binance")
        XCTAssertEqual(PortfolioWalletNames.nearDuplicate("LedgerNano", known: used), "Ledger Nano")
        XCTAssertEqual(PortfolioWalletNames.nearDuplicate("Krakén", known: used), "Kraken")
    }

    func testSameWalletOrUnrelatedNameGivesNoHint() {
        // Nur Gross/klein bzw. Rand-Leerzeichen: dieselbe Wallet (PortfolioWallets.key)
        XCTAssertNil(PortfolioWalletNames.nearDuplicate("binance", known: used))
        XCTAssertNil(PortfolioWalletNames.nearDuplicate(" Binance ", known: used))
        XCTAssertNil(PortfolioWalletNames.nearDuplicate("", known: used))
        XCTAssertNil(PortfolioWalletNames.nearDuplicate("Trezor", known: used))
        // Drei Tippfehler: zu weit weg
        XCTAssertNil(PortfolioWalletNames.nearDuplicate("Bonanxy", known: ["Binance"]))
    }

    func testShortNamesAllowFewOrNoTypos() {
        XCTAssertNil(PortfolioWalletNames.nearDuplicate("OKX", known: ["BTC"]))
        XCTAssertNil(PortfolioWalletNames.nearDuplicate("OKX", known: ["OKEX"]))
        XCTAssertEqual(PortfolioWalletNames.nearDuplicate("Bybitt", known: ["Bybit"]), "Bybit")
        XCTAssertNil(PortfolioWalletNames.nearDuplicate("Bybxtt", known: ["Bybit"]))
        XCTAssertEqual(PortfolioWalletNames.maxDistance(3), 0)
        XCTAssertEqual(PortfolioWalletNames.maxDistance(5), 1)
        XCTAssertEqual(PortfolioWalletNames.maxDistance(6), 2)
    }

    func testTieGoesToFirstKnownName() {
        XCTAssertEqual(PortfolioWalletNames.nearDuplicate("Binancx", known: ["Binance", "Binancy"]), "Binance")
    }

    func testSuggestionsWithoutQueryAreUsedNames() {
        XCTAssertEqual(PortfolioWalletNames.suggestions(used: used, catalog: ["Bitstamp"], query: ""), used)
        XCTAssertEqual(PortfolioWalletNames.suggestions(used: used, catalog: [], query: " ", limit: 1), ["Binance"])
    }

    func testSuggestionsWithQueryPrefixThenContains() {
        let catalog: [String] = ["Bitstamp", "Coinbase", "binance", "Bitget"]
        XCTAssertEqual(PortfolioWalletNames.suggestions(used: used, catalog: catalog, query: "bi"),
                       ["Binance", "Bitstamp", "Bitget"])
        XCTAssertEqual(PortfolioWalletNames.suggestions(used: used, catalog: catalog, query: "base"), ["Coinbase"])
        // Der schon getippte Name selbst nicht
        XCTAssertEqual(PortfolioWalletNames.suggestions(used: used, catalog: catalog, query: "binance"), [])
    }

    func testEditDistance() {
        XCTAssertEqual(PortfolioWalletNames.distance("abc", "abc"), 0)
        XCTAssertEqual(PortfolioWalletNames.distance("binance", "binanse"), 1)
        XCTAssertEqual(PortfolioWalletNames.distance("kraken", "krkn"), 2)
        XCTAssertEqual(PortfolioWalletNames.distance("", "abc"), 3)
    }
}
