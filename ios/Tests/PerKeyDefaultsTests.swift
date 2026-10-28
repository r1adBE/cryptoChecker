import XCTest
@testable import CryptoChecker

/// DEX-Liquidität, Token-Adresse und Mark-Kennzeichen: ein Schlüssel je Pool bzw. Paar in den App-Group-Einstellungen
/// (`PerKeyDefaults`), damit gleichzeitige Prozesse (App, Hintergrund, Widget) einander keine Messung überschreiben.
final class PerKeyDefaultsTests: XCTestCase {
    private let suite = "PerKeyDefaultsTests"
    private var defaults: UserDefaults!

    override func setUpWithError() throws {
        defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defaults.removePersistentDomain(forName: suite)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
        defaults = nil
        super.tearDown()
    }

    func testLiquiditaetJePoolEigenerSchluessel() {
        DexLiquidityStore.put("solana/AAA", 12_000, defaults: defaults)
        DexLiquidityStore.put("ethereum/BBB", 500, defaults: defaults)
        XCTAssertEqual(defaults.object(forKey: "dex_liquidity.solana/aaa") as? Double, 12_000)
        XCTAssertEqual(defaults.object(forKey: "dex_liquidity.ethereum/bbb") as? Double, 500)
        XCTAssertNil(defaults.object(forKey: "dex_liquidity"), "kein gemeinsames Wörterbuch")
        XCTAssertEqual(DexLiquidityStore.get("SOLANA/aaa", defaults: defaults), 12_000)
        DexLiquidityStore.put("solana/AAA", nil, defaults: defaults)
        XCTAssertNil(DexLiquidityStore.get("solana/AAA", defaults: defaults))
        XCTAssertEqual(DexLiquidityStore.get("ethereum/BBB", defaults: defaults), 500)
    }

    /// Ein zweiter Prozess, der denselben Stand gelesen hat, überschreibt den anderen Pool nicht mehr.
    func testSchreibenEinesPoolsLaesstAnderePoolsStehen() {
        let other = UserDefaults(suiteName: suite)!
        DexLiquidityStore.put("a/1", 20_000, defaults: defaults)
        DexLiquidityStore.put("b/2", 30_000, defaults: other)
        XCTAssertEqual(DexLiquidityStore.get("a/1", defaults: other), 20_000)
        XCTAssertEqual(DexLiquidityStore.get("b/2", defaults: defaults), 30_000)
    }

    func testTokenAdresseJePool() {
        DexTokenAddressStore.put("solana/AAA", "So11111111111111111111111111111111111111112", defaults: defaults)
        DexTokenAddressStore.put("solana/AAA", "   ", defaults: defaults)
        XCTAssertEqual(DexTokenAddressStore.get("solana/aaa", defaults: defaults), "So11111111111111111111111111111111111111112")
        XCTAssertNil(DexTokenAddressStore.get("solana/BBB", defaults: defaults))
    }

    func testMarkKennzeichenJePaar() {
        MarkFallbackStore.put(7, true, defaults: defaults)
        MarkFallbackStore.put(8, true, defaults: defaults)
        MarkFallbackStore.put(7, false, defaults: defaults)
        XCTAssertFalse(MarkFallbackStore.contains(7, defaults: defaults))
        XCTAssertTrue(MarkFallbackStore.contains(8, defaults: defaults))
        XCTAssertNil(defaults.object(forKey: "mark_fallback.7"))
    }
}
